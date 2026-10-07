#!/usr/bin/env zsh
# ==============================================================================
# Pixoo 64 Slideshow Streamer
#
# Powered by jixoo (https://github.com/glaforge/jixoo)
#
# Slideshow:
#  - Selects random image/animation from local cache or local directory
#  - Avoids immediate consecutive repeats
#  - Plays animated GIFs to full cycle completion (0 loading screens)
#  - Supports driving two Pixoo devices with identical content in lockstep sync
#
# Cache & GCS:
#  - Clears previous cache on startup (ignoring any stale downloads)
#  - Periodically refreshes cache from GCS bucket (1 min default, configurable)
#  - Automatically resolves authorized Google account if default gets 403
# ==============================================================================

set -e
setopt extended_glob

SCRIPT_DIR="${0:a:h}"
PARENT_DIR="${SCRIPT_DIR:h}"
SCRIPT_NAME="${0:t}"

WORK_DIR="${SCRIPT_DIR}/work"
CACHE_DIR="${WORK_DIR}/slideshow_cache"
RAW_FRAME="${WORK_DIR}/current_frame.raw"

# Parse duration strings (e.g. "1m", "90s", "60") into seconds
parse_duration() {
    local d="$1"
    if [[ "$d" =~ ^([0-9]+)[mM]$ ]]; then
        echo $(( match[1] * 60 ))
    elif [[ "$d" =~ ^([0-9]+)[sS]?$ ]]; then
        echo "${match[1]}"
    else
        echo "$d"
    fi
}

# Locate jixoo / pixoo-cli
find_pixoo_cli() {
    # 1. User-specified environment variable
    if [[ -n "${PIXOO_CLI:-}" && -x "$PIXOO_CLI" ]]; then
        echo "$PIXOO_CLI"
        return 0
    fi
    # 2. System PATH
    if command -v pixoo-cli >/dev/null 2>&1; then
        echo "$(command -v pixoo-cli)"
        return 0
    fi
    # 3. Parent directory native binary (GraalVM build)
    if [[ -x "${PARENT_DIR}/target/pixoo-cli" ]]; then
        echo "${PARENT_DIR}/target/pixoo-cli"
        return 0
    fi
    # 4. Parent directory root binary/symlink
    if [[ -x "${PARENT_DIR}/pixoo-cli" ]]; then
        echo "${PARENT_DIR}/pixoo-cli"
        return 0
    fi
    # 5. Parent directory executable Fat JAR
    local jar_file=("${PARENT_DIR}"/target/jixoo64-*-cli.jar(N))
    if (( ${#jar_file} > 0 )) && command -v java >/dev/null 2>&1; then
        echo "java -jar ${jar_file[1]}"
        return 0
    fi
    return 1
}

usage() {
    cat <<EOF
Usage: ${SCRIPT_NAME} [options] [PIXOO_IP] [PIXOO_IP2] [IMAGE_SOURCE]

Pixoo 64 Slideshow Streamer using jixoo / pixoo-cli

Arguments:
  PIXOO_IP                 Target primary device IP (auto-discovered via pixoo-cli if omitted)
  PIXOO_IP2                Optional secondary device IP to drive in sync
  IMAGE_SOURCE             GCS bucket URL or local directory (default: gs://conference-pics/gravidots/visuals)

Options:
  -d, --device2 <ip>       Second Pixoo device IP to drive with identical content in sync
  -r, --refresh <duration> Cache refresh interval from GCS (e.g., 60s, 1m; default: 1m)
  -i, --interval <sec>     Interval between slides in seconds (default: 3)
  -a, --account <email>    Google Cloud account for GCS bucket access (auto-detected if omitted)
  -p, --port <port>        Pixoo HTTP port (default: 80)
  -s, --source <src>       Image source (gs://bucket or directory)
  -h, --help               Show this help message

Environment Variables:
  PIXOO_IP / PIXOO_HOST    Primary device IP address
  PIXOO_IP2 / SECOND_PIXOO_IP Secondary device IP address
  REFRESH_INTERVAL         Cache refresh interval in seconds or duration format (default: 60)
  INTERVAL                 Slide interval in seconds (default: 3)
  GCS_ACCOUNT              Google Cloud account email for GCS access
  PIXOO_PORT               Device HTTP port (default: 80)
  IMAGE_SOURCE             GCS bucket URL or local directory
  GCS_BUCKET               Default GCS bucket URL
  PIXOO_CLI                Path to pixoo-cli binary or JAR
EOF
    exit 0
}

# Defaults
INTERVAL="${INTERVAL:-3}"
REFRESH_INTERVAL=$(parse_duration "${REFRESH_INTERVAL:-${CACHE_REFRESH_INTERVAL:-60}}")
PIXOO_IP="${PIXOO_IP:-${PIXOO_HOST:-}}"
PIXOO_IP2="${PIXOO_IP2:-${SECOND_PIXOO_IP:-}}"
PIXOO_PORT="${PIXOO_PORT:-80}"
DEFAULT_GCS_BUCKET="${GCS_BUCKET:-gs://conference-pics/gravidots/visuals}"
IMAGE_SOURCE="${IMAGE_SOURCE:-${IMAGE_DIR:-${DEFAULT_GCS_BUCKET}}}"
GCS_ACCOUNT="${GCS_ACCOUNT:-${CLOUDSDK_CORE_ACCOUNT:-}}"

# If PIXOO_IP contains comma-separated IPs (e.g. "192.168.1.10,192.168.1.11")
if [[ "$PIXOO_IP" == *","* ]]; then
    PIXOO_IP2="${PIXOO_IP#*,}"
    PIXOO_IP="${PIXOO_IP%%,*}"
fi

# Parse command-line arguments
while (( $# > 0 )); do
    case "$1" in
        -h|--help)
            usage
            ;;
        -d|--device2|--second-device|--sync)
            shift
            PIXOO_IP2="$1"
            ;;
        -r|--refresh|--refresh-interval|--cache-refresh)
            shift
            REFRESH_INTERVAL=$(parse_duration "${1:-60}")
            ;;
        -a|--account)
            shift
            GCS_ACCOUNT="$1"
            ;;
        -i|--interval)
            shift
            INTERVAL="${1:-3}"
            ;;
        -p|--port)
            shift
            PIXOO_PORT="${1:-80}"
            ;;
        -s|--source)
            shift
            IMAGE_SOURCE="$1"
            ;;
        *)
            if [[ "$1" == *","* ]]; then
                PIXOO_IP="${1%%,*}"
                PIXOO_IP2="${1#*,}"
            elif [[ "$1" =~ ^[0-9]+\.[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
                if [[ -z "$PIXOO_IP" ]]; then
                    PIXOO_IP="$1"
                elif [[ -z "$PIXOO_IP2" ]]; then
                    PIXOO_IP2="$1"
                fi
            elif [[ -z "$IMAGE_SOURCE" || "$IMAGE_SOURCE" == "$DEFAULT_GCS_BUCKET" ]]; then
                IMAGE_SOURCE="$1"
            elif [[ -z "$PIXOO_IP" ]]; then
                PIXOO_IP="$1"
            elif [[ -z "$PIXOO_IP2" ]]; then
                PIXOO_IP2="$1"
            fi
            ;;
    esac
    shift
done

# Verify prerequisites
for cmd in curl ffmpeg; do
    if ! command -v "$cmd" >/dev/null 2>&1; then
        echo "Error: Required command '$cmd' is not installed or not in PATH." >&2
        exit 1
    fi
done

# Resolve pixoo-cli
PIXOO_CLI_BIN="$(find_pixoo_cli 2>/dev/null || true)"

# Device connectivity and discovery functions
check_device() {
    local ip="$1"
    local port="${2:-80}"
    [[ -n "$ip" ]] && curl -s -m 2 -X POST -H "Content-Type: application/json" \
         -d '{"Command":"Channel/GetIndex"}' "http://${ip}:${port}/post" >/dev/null 2>&1
}

discover_device() {
    if [[ -n "$PIXOO_CLI_BIN" ]]; then
        echo "Searching local network for Pixoo 64 via pixoo-cli..."
        local discovered
        discovered=$(${=PIXOO_CLI_BIN} discover 2>/dev/null | grep -E '^[0-9]+\.[0-9]+\.[0-9]+\.[0-9]+' | awk '{print $1}' | head -n 1)
        if [[ -n "$discovered" ]]; then
            echo "Found Pixoo at ${discovered}!"
            PIXOO_IP="$discovered"
            return 0
        fi
    else
        echo "Warning: pixoo-cli not found; network discovery unavailable."
    fi
    return 1
}

# Determine and verify primary target IP
if [[ -z "$PIXOO_IP" ]]; then
    if ! discover_device; then
        echo "Warning: No Pixoo device auto-discovered. Falling back to default IP 192.168.1.49."
        PIXOO_IP="192.168.1.49"
    fi
fi

if ! check_device "$PIXOO_IP" "$PIXOO_PORT"; then
    echo "Warning: Primary Pixoo at ${PIXOO_IP}:${PIXOO_PORT} is not responding."
    if discover_device && check_device "$PIXOO_IP" "$PIXOO_PORT"; then
        echo "Reconnected to Pixoo at ${PIXOO_IP}!"
    else
        echo "Error: Could not connect to Pixoo 64 at ${PIXOO_IP}:${PIXOO_PORT}." >&2
        echo "Please verify device is powered on and connected to local Wi-Fi." >&2
        exit 1
    fi
fi

# Verify secondary device IP if configured
if [[ -n "$PIXOO_IP2" ]]; then
    if check_device "$PIXOO_IP2" "$PIXOO_PORT"; then
        echo "Connected to Secondary Pixoo at ${PIXOO_IP2}!"
    else
        echo "Warning: Secondary Pixoo at ${PIXOO_IP2}:${PIXOO_PORT} is not responding (will still attempt sync)."
    fi
fi

# GCS account resolution and helpers
resolve_gcs_account() {
    local bucket="$1"
    if [[ -n "$GCS_ACCOUNT" ]]; then
        if CLOUDSDK_CORE_ACCOUNT="$GCS_ACCOUNT" noglob gcloud storage ls "${bucket%/}/" >/dev/null 2>&1; then
            echo "$GCS_ACCOUNT"
            return 0
        fi
    fi
    # Check default active gcloud account
    if command -v gcloud >/dev/null 2>&1; then
        if noglob gcloud storage ls "${bucket%/}/" >/dev/null 2>&1; then
            local def_acc
            def_acc="$(noglob gcloud config get-value account 2>/dev/null || true)"
            echo "${def_acc:-default}"
            return 0
        fi
        # Search among other credentialed accounts
        local accounts=(${(f)"$(noglob gcloud auth list --format="value(account)" 2>/dev/null)"})
        for acc in "${accounts[@]}"; do
            [[ -z "$acc" ]] && continue
            if CLOUDSDK_CORE_ACCOUNT="$acc" noglob gcloud storage ls "${bucket%/}/" >/dev/null 2>&1; then
                echo "$acc"
                return 0
            fi
        done
    fi
    return 1
}

refresh_cache() {
    if [[ ! "$IMAGE_SOURCE" =~ ^gs:// ]]; then
        return 0
    fi

    echo "[$(date '+%H:%M:%S')] Refreshing cache from ${IMAGE_SOURCE}..."
    mkdir -p "$CACHE_DIR"

    if [[ -n "$GCS_ACCOUNT" ]]; then
        if command -v gcloud >/dev/null 2>&1; then
            CLOUDSDK_CORE_ACCOUNT="$GCS_ACCOUNT" noglob gcloud storage rsync --delete-unmatched-destination-objects -q "${IMAGE_SOURCE%/}/" "${CACHE_DIR}/" 2>/dev/null || {
                CLOUDSDK_CORE_ACCOUNT="$GCS_ACCOUNT" noglob gcloud storage cp "${IMAGE_SOURCE%/}/"* "${CACHE_DIR}/" 2>/dev/null || true
            }
        elif command -v gsutil >/dev/null 2>&1; then
            CLOUDSDK_CORE_ACCOUNT="$GCS_ACCOUNT" gsutil -q -m rsync -d "${IMAGE_SOURCE%/}/" "${CACHE_DIR}/" 2>/dev/null || true
        fi
    else
        if command -v gcloud >/dev/null 2>&1; then
            noglob gcloud storage rsync --delete-unmatched-destination-objects -q "${IMAGE_SOURCE%/}/" "${CACHE_DIR}/" 2>/dev/null || {
                noglob gcloud storage cp "${IMAGE_SOURCE%/}/"* "${CACHE_DIR}/" 2>/dev/null || true
            }
        elif command -v gsutil >/dev/null 2>&1; then
            gsutil -q -m rsync -d "${IMAGE_SOURCE%/}/" "${CACHE_DIR}/" 2>/dev/null || true
        fi
    fi
}

# If using GCS, resolve authorized account
if [[ "$IMAGE_SOURCE" =~ ^gs:// ]]; then
    if ! command -v gcloud >/dev/null 2>&1 && ! command -v gsutil >/dev/null 2>&1; then
        echo "Error: Neither 'gcloud' nor 'gsutil' is installed for GCS access." >&2
        exit 1
    fi

    resolved_acc="$(resolve_gcs_account "$IMAGE_SOURCE" 2>/dev/null || true)"
    if [[ -n "$resolved_acc" ]]; then
        GCS_ACCOUNT="$resolved_acc"
    else
        echo "Error: Cannot access GCS bucket ${IMAGE_SOURCE} with any authenticated Google account." >&2
        echo "Please run 'gcloud auth login' or specify an authorized account via -a / --account." >&2
        exit 1
    fi
fi

# Clear old cache directory on startup to ignore previous downloads
mkdir -p "$WORK_DIR"
if [[ -d "$CACHE_DIR" ]]; then
    rm -rf "${CACHE_DIR:?}"/*
fi
mkdir -p "$CACHE_DIR"

# Cleanup on exit
trap 'echo "\nSlideshow stopped."; exit 0' INT TERM

echo "=============================================="
echo " Starting Pixoo 64 Slideshow"
if [[ -n "$PIXOO_IP2" ]]; then
echo " Target Devices: http://${PIXOO_IP}:${PIXOO_PORT} & http://${PIXOO_IP2}:${PIXOO_PORT} (Synchronized)"
else
echo " Target Device : http://${PIXOO_IP}:${PIXOO_PORT}"
fi
echo " Image Source  : ${IMAGE_SOURCE}"
if [[ "$IMAGE_SOURCE" =~ ^gs:// ]]; then
if [[ -n "$GCS_ACCOUNT" ]]; then
echo " GCS Account   : ${GCS_ACCOUNT}"
fi
echo " Cache Refresh : Every ${REFRESH_INTERVAL}s"
fi
echo " Slide Interval: ${INTERVAL}s"
if [[ -n "$PIXOO_CLI_BIN" ]]; then
echo " Jixoo CLI     : ${PIXOO_CLI_BIN}"
fi
echo " Cache Status  : Cleared on startup"
echo "=============================================="

# Initial cache population
if [[ "$IMAGE_SOURCE" =~ ^gs:// ]]; then
    refresh_cache
    last_cache_refresh=$(date +%s)
fi

# Ensure display is ON and set to Custom Channel (Channel 3 for HTTP buffer)
init_display() {
    local ip="$1"
    local port="${2:-80}"
    curl -s -m 3 -X POST -H "Content-Type: application/json" \
         -d '{"Command":"Channel/OnOffScreen","OnOff":1}' "http://${ip}:${port}/post" >/dev/null 2>&1 || true

    local cur_ch
    cur_ch=$(curl -s -m 2 -X POST -H "Content-Type: application/json" \
         -d '{"Command":"Channel/GetIndex"}' "http://${ip}:${port}/post" 2>/dev/null | grep -o '"SelectIndex": *[0-9]*' | awk -F: '{print $2}' | tr -d ' ')

    if [[ "$cur_ch" != "3" ]]; then
        curl -s -m 3 -X POST -H "Content-Type: application/json" \
             -d '{"Command":"Channel/SetIndex","SelectIndex":3}' "http://${ip}:${port}/post" >/dev/null 2>&1 || true
    fi
}

echo "Initializing Pixoo display channel(s)..."
init_display "$PIXOO_IP" "$PIXOO_PORT"
if [[ -n "$PIXOO_IP2" ]]; then
    init_display "$PIXOO_IP2" "$PIXOO_PORT"
fi

pic_id=1
last_selected=""

while true; do
    # Check if cache refresh is due
    if [[ "$IMAGE_SOURCE" =~ ^gs:// ]]; then
        now=$(date +%s)
        if (( now - last_cache_refresh >= REFRESH_INTERVAL )); then
            refresh_cache
            last_cache_refresh=$(date +%s)
        fi
        images_dir="$CACHE_DIR"
    else
        images_dir="$IMAGE_SOURCE"
    fi

    valid_files=(${images_dir}/*.(#i)(jpg|jpeg|png|svg|gif|bmp|webp)(.N))
    total_files=${#valid_files}

    if (( total_files == 0 )); then
        echo "No image files found in ${images_dir}. Waiting ${INTERVAL}s..."
        sleep "$INTERVAL"
        continue
    fi

    # Select one file at random (avoid immediate repeat if more than 1 image)
    rand_idx=$(( RANDOM % total_files + 1 ))
    selected_file="${valid_files[$rand_idx]}"
    if (( total_files > 1 )); then
        while [[ "$selected_file" == "$last_selected" ]]; do
            rand_idx=$(( RANDOM % total_files + 1 ))
            selected_file="${valid_files[$rand_idx]}"
        done
    fi
    last_selected="$selected_file"
    filename="${selected_file:t}"

    echo "----------------------------------------------"
    echo "[$(date '+%H:%M:%S')] Slide: ${filename} (${rand_idx} of ${total_files})"

    image_input="$selected_file"

    # If SVG, rasterize first
    if [[ "${filename:e:l}" == "svg" ]]; then
        if command -v qlmanage >/dev/null 2>&1; then
            qlmanage -t -s 64 -o "$WORK_DIR" "$image_input" >/dev/null 2>&1
            image_input="${WORK_DIR}/${filename}.png"
        fi
    fi

    # If it is an animated GIF, stream all frames until the animation cycle is completely finished
    if [[ "${filename:e:l}" == "gif" ]]; then
        if command -v python3 >/dev/null 2>&1; then
            echo "Animating GIF on Pixoo (playing full animation cycle)..."
            anim_output=$(python3 -c "
import http.client, time, base64, math, sys, concurrent.futures
from PIL import Image, ImageSequence

gif_path = sys.argv[1]
ip1 = sys.argv[2]
port = int(sys.argv[3])
min_dur = float(sys.argv[4])
pic_id = int(sys.argv[5])
ip2 = sys.argv[6] if len(sys.argv) > 6 and sys.argv[6] else None

try:
    im = Image.open(gif_path)
    frames = []
    for frame in ImageSequence.Iterator(im):
        d = frame.info.get('duration', 100) or 100
        f = frame.convert('RGB').resize((64, 64), Image.Resampling.NEAREST)
        b64 = base64.b64encode(f.tobytes()).decode()
        frames.append((b64, d / 1000.0))

    if len(frames) > 1:
        cycle_dur = sum(d for _, d in frames)
        num_cycles = max(1, math.ceil(min_dur / cycle_dur)) if cycle_dur > 0 else 1
        target_desc = f'both devices ({ip1} & {ip2})' if ip2 else f'device ({ip1})'
        print(f'Playing {len(frames)} frames across {num_cycles} full cycle(s) (~{num_cycles * cycle_dur:.1f}s) to {target_desc} in sync...', file=sys.stderr)

        conn1 = http.client.HTTPConnection(ip1, port, timeout=2)
        conn2 = http.client.HTTPConnection(ip2, port, timeout=2) if ip2 else None

        def send_frame(conn, p):
            try:
                conn.request('POST', '/post', p, {'Content-Type': 'application/json'})
                conn.getresponse().read()
            except Exception:
                pass

        if conn2:
            with concurrent.futures.ThreadPoolExecutor(max_workers=2) as executor:
                for cycle in range(num_cycles):
                    for frame_b64, frame_delay in frames:
                        pic_id = (pic_id % 65535) + 1
                        payload = f'{{\"Command\":\"Draw/SendHttpGif\",\"PicNum\":1,\"PicWidth\":64,\"PicOffset\":0,\"PicID\":{pic_id},\"PicSpeed\":1000,\"PicData\":\"{frame_b64}\"}}'
                        t0 = time.time()
                        f1 = executor.submit(send_frame, conn1, payload)
                        f2 = executor.submit(send_frame, conn2, payload)
                        f1.result()
                        f2.result()
                        elapsed = time.time() - t0
                        rem = frame_delay - elapsed
                        if rem > 0:
                            time.sleep(rem)
        else:
            for cycle in range(num_cycles):
                for frame_b64, frame_delay in frames:
                    pic_id = (pic_id % 65535) + 1
                    payload = f'{{\"Command\":\"Draw/SendHttpGif\",\"PicNum\":1,\"PicWidth\":64,\"PicOffset\":0,\"PicID\":{pic_id},\"PicSpeed\":1000,\"PicData\":\"{frame_b64}\"}}'
                    t0 = time.time()
                    send_frame(conn1, payload)
                    elapsed = time.time() - t0
                    rem = frame_delay - elapsed
                    if rem > 0:
                        time.sleep(rem)

        print(pic_id)
        sys.exit(0)
    else:
        sys.exit(2)
except Exception as e:
    print(f'GIF playback error: {e}', file=sys.stderr)
    sys.exit(1)
" "$image_input" "$PIXOO_IP" "$PIXOO_PORT" "$INTERVAL" "$pic_id" "${PIXOO_IP2:-}" 2>&1)

            ret=$?
            if (( ret == 0 )); then
                last_line=$(echo "$anim_output" | tail -n 1)
                if [[ "$last_line" =~ ^[0-9]+$ ]]; then
                    pic_id=$last_line
                    echo "$anim_output" | sed '$d'
                else
                    echo "$anim_output"
                fi
                echo "Animation finished completely. Moving to next slide..."
                sleep 0.5
                continue
            fi
        fi
    fi

    # Scale/crop to 64x64 raw RGB24 buffer via ffmpeg for static images
    if ! ffmpeg -y -v error -i "$image_input" \
           -vframes 1 \
           -vf "scale=64:64:force_original_aspect_ratio=increase,crop=64:64,format=rgb24" \
           -f rawvideo "$RAW_FRAME"; then
        echo "Warning: ffmpeg failed to process ${image_input}. Skipping."
        sleep "$INTERVAL"
        continue
    fi

    # Base64 encode the 12,288 raw bytes
    b64_data=$(base64 < "$RAW_FRAME" | tr -d "\r\n")

    # Reset internal buffer state machine
    curl -s -m 2 -X POST -H "Content-Type: application/json" \
         -d '{"Command":"Draw/ResetHttpGifId"}' "http://${PIXOO_IP}:${PIXOO_PORT}/post" >/dev/null 2>&1 &
    if [[ -n "$PIXOO_IP2" ]]; then
        curl -s -m 2 -X POST -H "Content-Type: application/json" \
             -d '{"Command":"Draw/ResetHttpGifId"}' "http://${PIXOO_IP2}:${PIXOO_PORT}/post" >/dev/null 2>&1 &
    fi
    wait

    pic_id=$(( (pic_id % 65535) + 1 ))
    payload="{\"Command\":\"Draw/SendHttpGif\",\"PicNum\":1,\"PicWidth\":64,\"PicOffset\":0,\"PicID\":${pic_id},\"PicSpeed\":1000,\"PicData\":\"${b64_data}\"}"

    # Dispatch to both devices concurrently
    curl -s -m 4 -X POST -H "Content-Type: application/json" \
         -d "$payload" "http://${PIXOO_IP}:${PIXOO_PORT}/post" >/dev/null 2>&1 &
    if [[ -n "$PIXOO_IP2" ]]; then
        curl -s -m 4 -X POST -H "Content-Type: application/json" \
             -d "$payload" "http://${PIXOO_IP2}:${PIXOO_PORT}/post" >/dev/null 2>&1 &
    fi
    wait

    if [[ -n "$PIXOO_IP2" ]]; then
        echo "Successfully displayed on both Pixoo devices in sync."
    else
        echo "Successfully displayed on Pixoo."
    fi

    echo "Sleeping for ${INTERVAL}s..."
    sleep "$INTERVAL"
done
