#!/usr/bin/env bash
set -euo pipefail

# Check for GEMINI_API_KEY
if [[ -z "${GEMINI_API_KEY:-}" ]]; then
  echo "Error: GEMINI_API_KEY environment variable is not set." >&2
  exit 1
fi

# Check prompt/subject argument
if [[ $# -lt 1 || -z "$1" ]]; then
  echo "Usage: $0 \"<subject_or_prompt>\" [output_file]" >&2
  exit 1
fi

USER_INPUT="$1"

# Strip redundant prefix/suffix if provided out of habit
CLEAN_INPUT=$(echo "$USER_INPUT" | sed -E 's/^[Aa] pixel art 64x64 style image of //')
CLEAN_INPUT="${CLEAN_INPUT%.}"

# Factor in the pixel art 64x64 style and black background requirement
PROMPT="A pixel art 64x64 style image of ${CLEAN_INPUT}. The background should be black."

echo "Prompt: \"$PROMPT\"" >&2

# Build JSON payload safely with jq to prevent escaping issues
PAYLOAD=$(jq -n --arg prompt "$PROMPT" '{
  model: "models/gemini-3.1-flash-image",
  input: $prompt,
  response_format: {
    type: "image",
    aspect_ratio: "1:1"
  }
}')

# Call Gemini Interactions API
RESPONSE=$(curl -s -X POST "https://generativelanguage.googleapis.com/v1beta/interactions?key=${GEMINI_API_KEY}" \
  -H 'Content-Type: application/json' \
  -d "$PAYLOAD")

# Check for API error
if echo "$RESPONSE" | jq -e '.error' >/dev/null 2>&1; then
  echo "API Error:" >&2
  echo "$RESPONSE" | jq '.error' >&2
  exit 1
fi

# Extract image content block
IMAGE_CONTENT=$(echo "$RESPONSE" | jq '.steps[].content[]? | select(.type == "image")')

if [[ -z "$IMAGE_CONTENT" || "$IMAGE_CONTENT" == "null" ]]; then
  echo "Error: No image found in API response." >&2
  echo "Response was:" >&2
  echo "$RESPONSE" >&2
  exit 1
fi

IMAGE_DATA=$(echo "$IMAGE_CONTENT" | jq -r '.data')
MIME_TYPE=$(echo "$IMAGE_CONTENT" | jq -r '.mime_type // "image/jpeg"')

# Determine file extension based on MIME type
case "$MIME_TYPE" in
  "image/png") EXT="png" ;;
  "image/webp") EXT="webp" ;;
  *) EXT="jpg" ;;
esac

# Determine unique output filename if not provided
if [[ $# -ge 2 && -n "$2" ]]; then
  OUTPUT_FILE="$2"
else
  TIMESTAMP=$(date +"%Y%m%d_%H%M%S")
  RAND_SUFFIX=$(uuidgen | tr '[:upper:]' '[:lower:]' | cut -c1-6)
  OUTPUT_FILE="image_${TIMESTAMP}_${RAND_SUFFIX}.${EXT}"
fi

# Decode and write to file
echo "$IMAGE_DATA" | base64 -d > "$OUTPUT_FILE"

echo "Image successfully saved to $OUTPUT_FILE"
open $OUTPUT_FILE
