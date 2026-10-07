package io.github.glaforge.jixoo.controller.image

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.util.Base64
import com.caverock.androidsvg.SVG
import java.io.ByteArrayInputStream
import kotlin.math.max

sealed class ProcessedMedia {
    data class StaticImage(
        val rgb24Bytes: ByteArray,
        val base64Data: String,
        val previewArgb: IntArray
    ) : ProcessedMedia()

    data class AnimatedGif(
        val frames: List<GifFrameData>
    ) : ProcessedMedia()
}

/**
 * Processes static images (`jpg`, `jpeg`, `png`, `bmp`, `webp`), vector graphics (`svg`),
 * and animated `gif` files into 64x64 RGB24 buffers matching `simple-control.sh`.
 */
object ImageFrameProcessor {

    val VALID_EXTENSIONS = setOf("jpg", "jpeg", "png", "svg", "gif", "bmp", "webp")

    fun isValidImageFilename(filename: String): Boolean {
        val ext = filename.substringAfterLast('.', "").lowercase()
        return ext in VALID_EXTENSIONS
    }

    fun processImageBytes(filename: String, bytes: ByteArray): ProcessedMedia? {
        val ext = filename.substringAfterLast('.', "").lowercase()

        // 1. If animated GIF, decode all frames for full-cycle playback
        if (ext == "gif") {
            val frames = runCatching { GifStreamDecoder.decodeGifFrames(bytes) }.getOrNull()
            if (frames != null && frames.size > 1) {
                return ProcessedMedia.AnimatedGif(frames)
            }
            if (frames != null && frames.size == 1) {
                val f = frames.first()
                return ProcessedMedia.StaticImage(
                    rgb24Bytes = f.rgb24Bytes,
                    base64Data = f.base64Data,
                    previewArgb = f.previewArgb
                )
            }
        }

        // 2. If SVG, rasterize to 64x64 Bitmap first (matching qlmanage -t -s 64 in simple-control.sh)
        if (ext == "svg") {
            val svgBitmap = rasterizeSvg64(bytes) ?: return null
            return bitmap64ToStaticImage(svgBitmap)
        }

        // 3. Static images: scale=64:64:force_original_aspect_ratio=increase,crop=64:64,format=rgb24
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
        val cropped64 = scaleAndCenterCrop64(decoded)
        if (cropped64 !== decoded) {
            decoded.recycle()
        }
        return bitmap64ToStaticImage(cropped64)
    }

    private fun rasterizeSvg64(bytes: ByteArray): Bitmap? {
        return try {
            val svg = SVG.getFromInputStream(ByteArrayInputStream(bytes))
            val bitmap = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            canvas.drawColor(Color.BLACK)

            if (svg.documentViewBox == null) {
                val docW = if (svg.documentWidth > 0) svg.documentWidth else 64f
                val docH = if (svg.documentHeight > 0) svg.documentHeight else 64f
                svg.setDocumentViewBox(0f, 0f, docW, docH)
            }
            svg.setDocumentWidth("64px")
            svg.setDocumentHeight("64px")
            svg.renderToCanvas(canvas)
            bitmap
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Equivalent to ffmpeg filter:
     * `scale=64:64:force_original_aspect_ratio=increase,crop=64:64`
     */
    private fun scaleAndCenterCrop64(source: Bitmap): Bitmap {
        val srcW = source.width.coerceAtLeast(1)
        val srcH = source.height.coerceAtLeast(1)
        if (srcW == 64 && srcH == 64) return source

        val scale = max(64f / srcW.toFloat(), 64f / srcH.toFloat())
        val scaledW = srcW * scale
        val scaledH = srcH * scale
        val dx = (64f - scaledW) / 2f
        val dy = (64f - scaledH) / 2f

        val target = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(target)
        canvas.drawColor(Color.BLACK)

        val matrix = Matrix().apply {
            postScale(scale, scale)
            postTranslate(dx, dy)
        }
        val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
        canvas.drawBitmap(source, matrix, paint)
        return target
    }

    private fun bitmap64ToStaticImage(bitmap64: Bitmap): ProcessedMedia.StaticImage {
        val argbPixels = IntArray(64 * 64)
        bitmap64.getPixels(argbPixels, 0, 64, 0, 0, 64, 64)

        // Composite any transparency onto opaque black for preview consistency
        for (i in argbPixels.indices) {
            val c = argbPixels[i]
            val a = (c ushr 24) and 0xFF
            if (a < 255) {
                val r = (((c ushr 16) and 0xFF) * a) / 255
                val g = (((c ushr 8) and 0xFF) * a) / 255
                val b = ((c and 0xFF) * a) / 255
                argbPixels[i] = 0xFF000000.toInt() or (r shl 16) or (g shl 8) or b
            }
        }

        val rgb24 = GifStreamDecoder.argbToRgb24(argbPixels)
        val b64 = Base64.encodeToString(rgb24, Base64.NO_WRAP)
        return ProcessedMedia.StaticImage(
            rgb24Bytes = rgb24,
            base64Data = b64,
            previewArgb = argbPixels
        )
    }
}
