package io.github.glaforge.jixoo.controller.model

import android.content.Context

enum class ImageSourceType {
    GCS_BUCKET,
    LOCAL_DIRECTORY
}

/**
 * Mirrors all command-line flags, positional arguments, and environment variables
 * supported by `simple-control.sh`.
 */
data class SlideshowConfig(
    val primaryIp: String = "",
    val secondaryIpEnabled: Boolean = false,
    val secondaryIp: String = "",
    val port: Int = 80,
    val sourceType: ImageSourceType = ImageSourceType.GCS_BUCKET,
    val gcsBucketUrl: String = DEFAULT_GCS_BUCKET,
    val localDirectoryUri: String = "",
    val refreshIntervalRaw: String = "1m",
    val slideIntervalSeconds: Int = 3,
    val gcsAccount: String = "",
    val gcsBearerToken: String = ""
) {
    val refreshIntervalSeconds: Int
        get() = parseDuration(refreshIntervalRaw)

    val effectiveSecondaryIp: String?
        get() = if (secondaryIpEnabled && secondaryIp.isNotBlank()) secondaryIp.trim() else null

    companion object {
        const val DEFAULT_GCS_BUCKET = "gs://conference-pics/gravidots/visuals"
        const val DEFAULT_FALLBACK_IP = "192.168.1.49"
        const val DEFAULT_PORT = 80

        /**
         * Parses duration strings (e.g. "1m", "90s", "60") into seconds,
         * matching `parse_duration()` in `simple-control.sh`.
         */
        fun parseDuration(d: String): Int {
            val trimmed = d.trim()
            if (trimmed.isEmpty()) return 60
            val minutesMatch = Regex("^([0-9]+)[mM]$").matchEntire(trimmed)
            if (minutesMatch != null) {
                return (minutesMatch.groupValues[1].toIntOrNull() ?: 1) * 60
            }
            val secondsMatch = Regex("^([0-9]+)[sS]?$").matchEntire(trimmed)
            if (secondsMatch != null) {
                return (secondsMatch.groupValues[1].toIntOrNull() ?: 60).coerceAtLeast(1)
            }
            return (trimmed.toIntOrNull() ?: 60).coerceAtLeast(1)
        }

        /**
         * Splits comma-separated IPs (e.g. "192.168.1.10,192.168.1.11") into primary and secondary IPs,
         * matching `simple-control.sh` lines 118-121 and 154-156.
         */
        fun splitCommaSeparatedIps(input: String): Pair<String, String?> {
            val trimmed = input.trim()
            return if (trimmed.contains(",")) {
                val primary = trimmed.substringBefore(",").trim()
                val secondary = trimmed.substringAfter(",").trim().ifEmpty { null }
                primary to secondary
            } else {
                trimmed to null
            }
        }
    }
}

class SlideshowPreferences(context: Context) {
    private val prefs = context.getSharedPreferences("pixoo_slideshow_prefs", Context.MODE_PRIVATE)

    fun load(): SlideshowConfig {
        val sourceTypeName = prefs.getString("source_type", ImageSourceType.GCS_BUCKET.name)
            ?: ImageSourceType.GCS_BUCKET.name
        val sourceType = runCatching { ImageSourceType.valueOf(sourceTypeName) }
            .getOrDefault(ImageSourceType.GCS_BUCKET)

        return SlideshowConfig(
            primaryIp = prefs.getString("primary_ip", "") ?: "",
            secondaryIpEnabled = prefs.getBoolean("secondary_ip_enabled", false),
            secondaryIp = prefs.getString("secondary_ip", "") ?: "",
            port = prefs.getInt("port", SlideshowConfig.DEFAULT_PORT),
            sourceType = sourceType,
            gcsBucketUrl = prefs.getString("gcs_bucket_url", SlideshowConfig.DEFAULT_GCS_BUCKET)
                ?: SlideshowConfig.DEFAULT_GCS_BUCKET,
            localDirectoryUri = prefs.getString("local_dir_uri", "") ?: "",
            refreshIntervalRaw = prefs.getString("refresh_interval_raw", "1m") ?: "1m",
            slideIntervalSeconds = prefs.getInt("slide_interval_seconds", 3),
            gcsAccount = prefs.getString("gcs_account", "") ?: "",
            gcsBearerToken = prefs.getString("gcs_bearer_token", "") ?: ""
        )
    }

    fun save(config: SlideshowConfig) {
        prefs.edit()
            .putString("primary_ip", config.primaryIp)
            .putBoolean("secondary_ip_enabled", config.secondaryIpEnabled)
            .putString("secondary_ip", config.secondaryIp)
            .putInt("port", config.port)
            .putString("source_type", config.sourceType.name)
            .putString("gcs_bucket_url", config.gcsBucketUrl)
            .putString("local_dir_uri", config.localDirectoryUri)
            .putString("refresh_interval_raw", config.refreshIntervalRaw)
            .putInt("slide_interval_seconds", config.slideIntervalSeconds)
            .putString("gcs_account", config.gcsAccount)
            .putString("gcs_bearer_token", config.gcsBearerToken)
            .apply()
    }
}
