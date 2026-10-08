package io.github.glaforge.jixoo.controller.gcs

import android.accounts.Account
import android.accounts.AccountManager
import android.app.Activity
import android.content.Context
import io.github.glaforge.jixoo.controller.image.ImageFrameProcessor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

data class GcsLocation(
    val bucket: String,
    val prefix: String
)

data class ResolvedGcsAuth(
    val accountLabel: String,
    val bearerToken: String?
)

data class GcsObjectMeta(
    val fullName: String,
    val fileName: String,
    val size: Long,
    val etag: String
)

/**
 * Manages GCS bucket synchronization and local `slideshow_cache` directory,
 * faithfully implementing `resolve_gcs_account()` and `refresh_cache()` from `simple-control.sh`.
 */
object GcsCacheManager {

    private const val GCS_SCOPE = "oauth2:https://www.googleapis.com/auth/devstorage.read_only"

    fun getCacheDir(context: Context): File {
        val dir = File(context.filesDir, "work/slideshow_cache")
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return dir
    }

    /**
     * Clears previous cache on startup (matching `simple-control.sh` cache cleanup).
     */
    fun clearCacheOnStartup(context: Context) {
        val dir = getCacheDir(context)
        dir.listFiles()?.forEach { file ->
            runCatching { file.deleteRecursively() }
        }
        dir.mkdirs()
    }

    fun parseGcsUrl(gcsUrl: String): GcsLocation? {
        val trimmed = gcsUrl.trim()
        if (trimmed.isEmpty()) return null
        val withoutScheme = if (trimmed.startsWith("gs://", ignoreCase = true)) {
            trimmed.substring(5).trimEnd('/')
        } else {
            trimmed.trim('/')
        }
        if (withoutScheme.isEmpty()) return null
        val slashIdx = withoutScheme.indexOf('/')
        return if (slashIdx < 0) {
            GcsLocation(bucket = withoutScheme, prefix = "")
        } else {
            val bucket = withoutScheme.substring(0, slashIdx)
            val prefix = withoutScheme.substring(slashIdx + 1).trim('/')
            GcsLocation(
                bucket = bucket,
                prefix = if (prefix.isEmpty()) "" else "$prefix/"
            )
        }
    }

    /**
     * Lists Google accounts available on the Android device via AccountManager.
     */
    fun getDeviceGoogleAccounts(context: Context): List<String> {
        return runCatching {
            val am = AccountManager.get(context)
            am.getAccountsByType("com.google").map { it.name }.distinct()
        }.getOrDefault(emptyList())
    }

    /**
     * Requests an OAuth2 access token for a specific Google account on the device.
     */
    suspend fun requestAccountToken(
        context: Context,
        accountEmail: String,
        activity: Activity? = null
    ): String? = withContext(Dispatchers.IO) {
        runCatching {
            val am = AccountManager.get(context)
            val account = Account(accountEmail.trim(), "com.google")
            val future = if (activity != null) {
                am.getAuthToken(account, GCS_SCOPE, null, activity, null, null)
            } else {
                am.getAuthToken(account, GCS_SCOPE, null, false, null, null)
            }
            val bundle = future.getResult(5, java.util.concurrent.TimeUnit.SECONDS)
            bundle.getString(AccountManager.KEY_AUTHTOKEN)
        }.getOrNull()
    }

    /**
     * Equivalent to `resolve_gcs_account()` in `simple-control.sh` (lines 239-266):
     * 1. Tests manual Bearer token if provided.
     * 2. Tests preferred `GCS_ACCOUNT` if provided.
     * 3. Tests anonymous/public access to the GCS bucket.
     * 4. Iterates through other credentialed Google accounts on the device if default gets 403.
     */
    suspend fun resolveGcsAccount(
        context: Context,
        gcsUrl: String,
        preferredAccount: String,
        manualBearerToken: String,
        activity: Activity? = null
    ): ResolvedGcsAuth? = withContext(Dispatchers.IO) {
        val loc = parseGcsUrl(gcsUrl) ?: return@withContext null

        // 1. Manual Bearer token override (e.g. injected via ADB from gcloud auth print-access-token)
        if (manualBearerToken.isNotBlank()) {
            val token = manualBearerToken.trim().removePrefix("Bearer ").trim()
            if (canAccessBucket(loc, token)) {
                val label = preferredAccount.ifBlank { "Bearer Token" }
                return@withContext ResolvedGcsAuth(label, token)
            }
        }

        // 2. Check pushed gcloud adc.json (refresh_token) in filesDir if present
        val adcAuth = refreshAdcToken(context)
        if (adcAuth != null && canAccessBucket(loc, adcAuth.bearerToken)) {
            return@withContext adcAuth
        }

        // 3. User-specified GCS_ACCOUNT (-a / --account)
        if (preferredAccount.isNotBlank()) {
            val token = requestAccountToken(context, preferredAccount, activity)
            if (!token.isNullOrBlank() && canAccessBucket(loc, token)) {
                return@withContext ResolvedGcsAuth(preferredAccount.trim(), token)
            }
        }

        // 4. Check public/unauthenticated bucket access
        if (canAccessBucket(loc, null)) {
            return@withContext ResolvedGcsAuth("public (unauthenticated)", null)
        }

        // 5. Search among other credentialed Google accounts on device (like `gcloud auth list`)
        val accounts = getDeviceGoogleAccounts(context)
        for (acc in accounts) {
            if (acc.equals(preferredAccount.trim(), ignoreCase = true)) continue
            val token = requestAccountToken(context, acc, activity)
            if (!token.isNullOrBlank() && canAccessBucket(loc, token)) {
                return@withContext ResolvedGcsAuth(acc, token)
            }
        }

        null
    }

    private fun refreshAdcToken(context: Context): ResolvedGcsAuth? {
        val adcFile = File(context.filesDir, "adc.json")
        if (!adcFile.exists()) return null
        return try {
            val json = JSONObject(adcFile.readText())
            val clientId = json.optString("client_id", "")
            val clientSecret = json.optString("client_secret", "")
            val refreshToken = json.optString("refresh_token", "")
            val account = json.optString("account", "ADC Refresh Token")
            if (clientId.isEmpty() || clientSecret.isEmpty() || refreshToken.isEmpty()) return null

            val postBody = "client_id=${URLEncoder.encode(clientId, "UTF-8")}" +
                "&client_secret=${URLEncoder.encode(clientSecret, "UTF-8")}" +
                "&refresh_token=${URLEncoder.encode(refreshToken, "UTF-8")}" +
                "&grant_type=refresh_token"

            val conn = (URL("https://oauth2.googleapis.com/token").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 8000
                readTimeout = 8000
                doOutput = true
                setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            }
            conn.outputStream.use { it.write(postBody.toByteArray(StandardCharsets.UTF_8)) }
            if (conn.responseCode in 200..299) {
                val resp = BufferedReader(InputStreamReader(conn.inputStream, StandardCharsets.UTF_8)).use { it.readText() }
                val token = JSONObject(resp).optString("access_token", "")
                if (token.isNotEmpty()) {
                    ResolvedGcsAuth("$account (ADC)", token)
                } else null
            } else null
        } catch (_: Exception) {
            null
        }
    }

    private fun canAccessBucket(location: GcsLocation, bearerToken: String?): Boolean {
        val encodedPrefix = URLEncoder.encode(location.prefix, "UTF-8")
        val urlStr = "https://storage.googleapis.com/storage/v1/b/${location.bucket}/o?prefix=$encodedPrefix&maxResults=1"
        val (code, _) = httpGet(urlStr, bearerToken)
        return code in 200..299
    }

    /**
     * Equivalent to `refresh_cache()` in `simple-control.sh` (lines 268-293):
     * Synchronizes objects from `gs://bucket/prefix` into `slideshow_cache`,
     * downloading new/updated images and deleting unmatched local files
     * (`--delete-unmatched-destination-objects`).
     */
    suspend fun refreshCache(
        context: Context,
        gcsUrl: String,
        bearerToken: String?,
        onLog: (String) -> Unit,
        onProgress: ((String) -> Unit)? = null
    ): List<File> = withContext(Dispatchers.IO) {
        val loc = parseGcsUrl(gcsUrl) ?: return@withContext emptyList()
        val cacheDir = getCacheDir(context)
        val metaFile = File(context.filesDir, "work/cache_etags.json")
        val existingEtags = loadEtags(metaFile)
        val newEtags = mutableMapOf<String, String>()

        onProgress?.invoke("Listing objects in $gcsUrl...")
        val remoteObjects = listBucketObjects(loc, bearerToken)
        if (remoteObjects == null) {
            onLog("Warning: Failed to list objects from $gcsUrl (keeping existing cache).")
            return@withContext listValidCachedFiles(cacheDir)
        }

        val validRemote = remoteObjects.filter { obj ->
            // Only direct children or files with valid image extension
            obj.fileName.isNotEmpty() &&
                !obj.fileName.contains('/') &&
                ImageFrameProcessor.isValidImageFilename(obj.fileName)
        }

        val remoteFileNames = validRemote.map { it.fileName }.toSet()

        // Delete unmatched destination objects (--delete-unmatched-destination-objects)
        cacheDir.listFiles()?.forEach { localFile ->
            if (localFile.isFile && localFile.name !in remoteFileNames) {
                localFile.delete()
            }
        }

        // Determine which files need downloading
        val toDownload = mutableListOf<GcsObjectMeta>()
        for (obj in validRemote) {
            val targetFile = File(cacheDir, obj.fileName)
            val prevEtag = existingEtags[obj.fileName]
            if (targetFile.exists() && targetFile.length() == obj.size && (prevEtag.isNullOrEmpty() || prevEtag == obj.etag)) {
                newEtags[obj.fileName] = obj.etag
            } else {
                toDownload.add(obj)
            }
        }

        if (toDownload.isNotEmpty()) {
            val alreadyUpToDate = validRemote.size - toDownload.size
            if (alreadyUpToDate > 0) {
                onLog("$alreadyUpToDate cached file(s) already up-to-date; downloading ${toDownload.size} new/modified file(s)...")
            } else {
                onLog("Downloading ${toDownload.size} file(s) from bucket...")
            }

            for ((idx, obj) in toDownload.withIndex()) {
                val progressMsg = "Downloading [${idx + 1}/${toDownload.size}] ${obj.fileName} (${obj.size / 1024} KB)..."
                onProgress?.invoke(progressMsg)
                onLog(progressMsg)

                val targetFile = File(cacheDir, obj.fileName)
                val downloaded = downloadObject(loc.bucket, obj.fullName, bearerToken, targetFile)
                if (downloaded) {
                    newEtags[obj.fileName] = obj.etag
                } else {
                    onLog("Warning: Failed to download ${obj.fileName}.")
                }
            }
        } else {
            onLog("All ${validRemote.size} cached file(s) are already up-to-date.")
        }

        saveEtags(metaFile, newEtags)
        listValidCachedFiles(cacheDir)
    }

    fun listValidCachedFiles(cacheDir: File): List<File> {
        return cacheDir.listFiles()
            ?.filter { it.isFile && ImageFrameProcessor.isValidImageFilename(it.name) }
            ?.sortedBy { it.name }
            ?: emptyList()
    }

    private fun listBucketObjects(location: GcsLocation, bearerToken: String?): List<GcsObjectMeta>? {
        val results = mutableListOf<GcsObjectMeta>()
        var pageToken: String? = null

        do {
            val encodedPrefix = URLEncoder.encode(location.prefix, "UTF-8")
            val pageParam = if (pageToken != null) "&pageToken=${URLEncoder.encode(pageToken, "UTF-8")}" else ""
            val urlStr = "https://storage.googleapis.com/storage/v1/b/${location.bucket}/o?prefix=$encodedPrefix$pageParam"

            val (code, body) = httpGet(urlStr, bearerToken)
            if (code !in 200..299 || body == null) {
                return null
            }

            val json = JSONObject(body)
            val items = json.optJSONArray("items")
            if (items != null) {
                for (i in 0 until items.length()) {
                    val item = items.getJSONObject(i)
                    val fullName = item.optString("name", "")
                    if (fullName.isEmpty() || fullName.endsWith("/")) continue
                    val relativeName = if (location.prefix.isNotEmpty() && fullName.startsWith(location.prefix)) {
                        fullName.removePrefix(location.prefix)
                    } else {
                        fullName.substringAfterLast('/')
                    }
                    val size = item.optString("size", "0").toLongOrNull() ?: 0L
                    val etag = item.optString("etag", item.optString("md5Hash", ""))
                    results.add(
                        GcsObjectMeta(
                            fullName = fullName,
                            fileName = relativeName,
                            size = size,
                            etag = etag
                        )
                    )
                }
            }
            pageToken = json.optString("nextPageToken", "").ifEmpty { null }
        } while (pageToken != null)

        return results
    }

    private fun downloadObject(
        bucket: String,
        fullObjectName: String,
        bearerToken: String?,
        destFile: File
    ): Boolean {
        val encodedObject = URLEncoder.encode(fullObjectName, "UTF-8")
        val urlStr = "https://storage.googleapis.com/storage/v1/b/$bucket/o/$encodedObject?alt=media"
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 10000
                readTimeout = 15000
                if (!bearerToken.isNullOrBlank()) {
                    setRequestProperty("Authorization", "Bearer $bearerToken")
                }
            }
            if (conn.responseCode in 200..299) {
                val tempFile = File(destFile.parentFile, "${destFile.name}.tmp")
                conn.inputStream.use { input ->
                    FileOutputStream(tempFile).use { output ->
                        input.copyTo(output)
                    }
                }
                tempFile.renameTo(destFile)
                true
            } else {
                false
            }
        } catch (_: Exception) {
            false
        } finally {
            conn?.disconnect()
        }
    }

    private fun httpGet(urlStr: String, bearerToken: String?): Pair<Int, String?> {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 8000
                readTimeout = 10000
                setRequestProperty("Accept", "application/json")
                if (!bearerToken.isNullOrBlank()) {
                    setRequestProperty("Authorization", "Bearer $bearerToken")
                }
            }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val body = stream?.let {
                BufferedReader(InputStreamReader(it, StandardCharsets.UTF_8)).use { r -> r.readText() }
            }
            code to body
        } catch (_: Exception) {
            -1 to null
        } finally {
            conn?.disconnect()
        }
    }

    private fun loadEtags(file: File): Map<String, String> {
        if (!file.exists()) return emptyMap()
        return try {
            val json = JSONObject(file.readText())
            val map = mutableMapOf<String, String>()
            for (key in json.keys()) {
                map[key] = json.optString(key, "")
            }
            map
        } catch (_: Exception) {
            emptyMap()
        }
    }

    private fun saveEtags(file: File, map: Map<String, String>) {
        runCatching {
            file.parentFile?.mkdirs()
            val json = JSONObject()
            for ((k, v) in map) {
                json.put(k, v)
            }
            file.writeText(json.toString())
        }
    }
}
