package io.github.glaforge.jixoo.controller.image

import android.util.Base64
import java.util.Arrays
import kotlin.math.min

data class GifFrameData(
    val rgb24Bytes: ByteArray,
    val base64Data: String,
    val delaySeconds: Double,
    val previewArgb: IntArray
)

/**
 * Pure-Kotlin GIF87a/GIF89a frame decoder that extracts every frame and its exact delay,
 * composites disposal methods, and resizes to 64x64 using Nearest-Neighbor resampling
 * (`Image.Resampling.NEAREST` in `simple-control.sh`).
 */
object GifStreamDecoder {

    fun decodeGifFrames(data: ByteArray): List<GifFrameData> {
        if (data.size < 13) return emptyList()
        val header = String(data, 0, 6, Charsets.US_ASCII)
        if (!header.startsWith("GIF")) return emptyList()

        var pos = 6
        val logicalWidth = readInt16LE(data, pos)
        val logicalHeight = readInt16LE(data, pos + 2)
        val packed = data[pos + 4].toInt() and 0xFF
        val bgColorIndex = data[pos + 5].toInt() and 0xFF
        pos += 7

        if (logicalWidth <= 0 || logicalHeight <= 0) return emptyList()

        val hasGlobalColorTable = (packed and 0x80) != 0
        val globalColorTableSize = 2 shl (packed and 0x07)
        var globalColorTable: IntArray? = null

        if (hasGlobalColorTable) {
            globalColorTable = readColorTable(data, pos, globalColorTableSize)
            pos += globalColorTableSize * 3
        }

        var masterCanvas = IntArray(logicalWidth * logicalHeight)
        var previousCanvas = IntArray(logicalWidth * logicalHeight)

        var defaultBg = 0xFF000000.toInt()
        if (hasGlobalColorTable && globalColorTable != null && bgColorIndex < globalColorTable.size) {
            defaultBg = globalColorTable[bgColorIndex]
        }
        Arrays.fill(masterCanvas, defaultBg)
        System.arraycopy(masterCanvas, 0, previousCanvas, 0, masterCanvas.size)

        val frames = mutableListOf<GifFrameData>()

        var delayMs = 100
        var disposalMethod = 0
        var hasTransparency = false
        var transparentColorIndex = -1

        while (pos < data.size) {
            val blockType = data[pos++].toInt() and 0xFF
            if (blockType == 0x3B) break // Trailer

            if (blockType == 0x21) {
                if (pos >= data.size) break
                val extType = data[pos++].toInt() and 0xFF
                if (extType == 0xF9) {
                    val blockSize = data[pos++].toInt() and 0xFF
                    if (blockSize >= 4 && pos + blockSize <= data.size) {
                        val gcePacked = data[pos].toInt() and 0xFF
                        disposalMethod = (gcePacked shr 2) and 0x07
                        hasTransparency = (gcePacked and 0x01) != 0
                        val rawDelay = readInt16LE(data, pos + 1)
                        delayMs = if (rawDelay > 0) rawDelay * 10 else 100
                        transparentColorIndex = data[pos + 3].toInt() and 0xFF
                    }
                    pos += blockSize
                    while (pos < data.size && (data[pos].toInt() and 0xFF) != 0) {
                        val skip = data[pos].toInt() and 0xFF
                        pos += 1 + skip
                    }
                    if (pos < data.size) pos++
                } else {
                    while (pos < data.size && (data[pos].toInt() and 0xFF) != 0) {
                        val skip = data[pos].toInt() and 0xFF
                        pos += 1 + skip
                    }
                    if (pos < data.size) pos++
                }
            } else if (blockType == 0x2C) {
                if (pos + 9 > data.size) break
                val imageLeft = readInt16LE(data, pos)
                val imageTop = readInt16LE(data, pos + 2)
                val imageWidth = readInt16LE(data, pos + 4)
                val imageHeight = readInt16LE(data, pos + 6)
                val imgPacked = data[pos + 8].toInt() and 0xFF
                pos += 9

                val hasLocalColorTable = (imgPacked and 0x80) != 0
                val interlace = (imgPacked and 0x40) != 0
                var activeColorTable = globalColorTable

                if (hasLocalColorTable) {
                    val localColorTableSize = 2 shl (imgPacked and 0x07)
                    activeColorTable = readColorTable(data, pos, localColorTableSize)
                    pos += localColorTableSize * 3
                }
                if (activeColorTable == null) {
                    activeColorTable = IntArray(256) { 0xFF000000.toInt() }
                }

                if (pos >= data.size) break
                val lzwMinCodeSize = data[pos++].toInt() and 0xFF
                val lzwData = readSubBlocks(data, pos)
                pos += countSubBlockTotalBytes(data, pos)

                val pixelIndices = lzwDecompress(lzwData, lzwMinCodeSize, imageWidth * imageHeight)
                val currentCanvas = masterCanvas.copyOf()

                renderFramePixels(
                    pixelIndices = pixelIndices,
                    imgW = imageWidth,
                    imgH = imageHeight,
                    left = imageLeft,
                    top = imageTop,
                    canvasW = logicalWidth,
                    canvasH = logicalHeight,
                    interlace = interlace,
                    colorTable = activeColorTable,
                    hasTransparency = hasTransparency,
                    transparentIdx = transparentColorIndex,
                    canvas = currentCanvas
                )

                // Nearest-neighbor resize to 64x64 matching `Image.Resampling.NEAREST` in simple-control.sh
                val resizedArgb = resizeNearest64(currentCanvas, logicalWidth, logicalHeight)
                val rgb24 = argbToRgb24(resizedArgb)
                val b64 = Base64.encodeToString(rgb24, Base64.NO_WRAP)

                frames.add(
                    GifFrameData(
                        rgb24Bytes = rgb24,
                        base64Data = b64,
                        delaySeconds = delayMs / 1000.0,
                        previewArgb = resizedArgb
                    )
                )

                when (disposalMethod) {
                    3 -> masterCanvas = previousCanvas.copyOf()
                    2 -> {
                        previousCanvas = masterCanvas.copyOf()
                        masterCanvas = currentCanvas.copyOf()
                        clearBoundingBox(
                            masterCanvas,
                            logicalWidth,
                            logicalHeight,
                            imageLeft,
                            imageTop,
                            imageWidth,
                            imageHeight,
                            defaultBg
                        )
                    }
                    else -> {
                        previousCanvas = masterCanvas.copyOf()
                        masterCanvas = currentCanvas.copyOf()
                    }
                }

                delayMs = 100
                disposalMethod = 0
                hasTransparency = false
                transparentColorIndex = -1
            }
        }

        return frames
    }

    private fun resizeNearest64(src: IntArray, srcW: Int, srcH: Int): IntArray {
        val dst = IntArray(64 * 64)
        for (y in 0 until 64) {
            val sy = (y * srcH) / 64
            val rowOffset = sy * srcW
            val dstRowOffset = y * 64
            for (x in 0 until 64) {
                val sx = (x * srcW) / 64
                dst[dstRowOffset + x] = src[rowOffset + sx] or 0xFF000000.toInt()
            }
        }
        return dst
    }

    fun argbToRgb24(argbPixels: IntArray): ByteArray {
        val rawRgb = ByteArray(64 * 64 * 3)
        var idx = 0
        for (argb in argbPixels) {
            val a = (argb ushr 24) and 0xFF
            var r = (argb ushr 16) and 0xFF
            var g = (argb ushr 8) and 0xFF
            var b = argb and 0xFF
            if (a < 255) {
                r = (r * a) / 255
                g = (g * a) / 255
                b = (b * a) / 255
            }
            rawRgb[idx++] = r.toByte()
            rawRgb[idx++] = g.toByte()
            rawRgb[idx++] = b.toByte()
        }
        return rawRgb
    }

    private fun renderFramePixels(
        pixelIndices: ByteArray,
        imgW: Int,
        imgH: Int,
        left: Int,
        top: Int,
        canvasW: Int,
        canvasH: Int,
        interlace: Boolean,
        colorTable: IntArray,
        hasTransparency: Boolean,
        transparentIdx: Int,
        canvas: IntArray
    ) {
        val passStart = intArrayOf(0, 4, 2, 1)
        val passStep = intArrayOf(8, 8, 4, 2)
        var srcIdx = 0
        val totalPixels = imgW * imgH

        if (!interlace) {
            var y = 0
            while (y < imgH && srcIdx < totalPixels) {
                val destY = top + y
                var x = 0
                while (x < imgW && srcIdx < totalPixels) {
                    val destX = left + x
                    val colorIdx = pixelIndices[srcIdx++].toInt() and 0xFF
                    if (!(hasTransparency && colorIdx == transparentIdx)) {
                        if (destX in 0 until canvasW && destY in 0 until canvasH) {
                            val color = if (colorIdx < colorTable.size) colorTable[colorIdx] else 0xFF000000.toInt()
                            canvas[destY * canvasW + destX] = color
                        }
                    }
                    x++
                }
                y++
            }
        } else {
            for (pass in 0 until 4) {
                var y = passStart[pass]
                val step = passStep[pass]
                while (y < imgH && srcIdx < totalPixels) {
                    val destY = top + y
                    var x = 0
                    while (x < imgW && srcIdx < totalPixels) {
                        val destX = left + x
                        val colorIdx = pixelIndices[srcIdx++].toInt() and 0xFF
                        if (!(hasTransparency && colorIdx == transparentIdx)) {
                            if (destX in 0 until canvasW && destY in 0 until canvasH) {
                                val color = if (colorIdx < colorTable.size) colorTable[colorIdx] else 0xFF000000.toInt()
                                canvas[destY * canvasW + destX] = color
                            }
                        }
                        x++
                    }
                    y += step
                }
            }
        }
    }

    private fun clearBoundingBox(
        canvas: IntArray,
        canvasW: Int,
        canvasH: Int,
        left: Int,
        top: Int,
        width: Int,
        height: Int,
        clearColor: Int
    ) {
        val endX = min(canvasW, left + width)
        val endY = min(canvasH, top + height)
        for (y in maxOf(0, top) until endY) {
            for (x in maxOf(0, left) until endX) {
                canvas[y * canvasW + x] = clearColor
            }
        }
    }

    private fun readColorTable(data: ByteArray, offset: Int, count: Int): IntArray {
        val table = IntArray(count)
        for (i in 0 until count) {
            val p = offset + i * 3
            if (p + 2 >= data.size) break
            val r = data[p].toInt() and 0xFF
            val g = data[p + 1].toInt() and 0xFF
            val b = data[p + 2].toInt() and 0xFF
            table[i] = 0xFF000000.toInt() or (r shl 16) or (g shl 8) or b
        }
        return table
    }

    private fun readSubBlocks(data: ByteArray, offset: Int): ByteArray {
        var pos = offset
        var totalLen = 0
        while (pos < data.size && (data[pos].toInt() and 0xFF) != 0) {
            val len = data[pos].toInt() and 0xFF
            totalLen += len
            pos += 1 + len
        }
        val result = ByteArray(totalLen)
        pos = offset
        var destPos = 0
        while (pos < data.size && (data[pos].toInt() and 0xFF) != 0) {
            val len = data[pos].toInt() and 0xFF
            pos++
            val copyLen = min(len, data.size - pos)
            if (copyLen > 0) {
                System.arraycopy(data, pos, result, destPos, copyLen)
                destPos += copyLen
            }
            pos += len
        }
        return result
    }

    private fun countSubBlockTotalBytes(data: ByteArray, offset: Int): Int {
        var pos = offset
        while (pos < data.size && (data[pos].toInt() and 0xFF) != 0) {
            val len = data[pos].toInt() and 0xFF
            pos += 1 + len
        }
        if (pos < data.size) pos++
        return pos - offset
    }

    private fun lzwDecompress(lzwData: ByteArray, minCodeSize: Int, expectedLength: Int): ByteArray {
        val output = ByteArray(expectedLength)
        val clearCode = 1 shl minCodeSize
        val endCode = clearCode + 1

        var codeSize = minCodeSize + 1
        var maxCode = 1 shl codeSize
        var available = clearCode + 2

        val prefix = IntArray(4096)
        val suffix = ByteArray(4096)
        val pixelStack = ByteArray(4097)

        for (i in 0 until min(clearCode, 4096)) {
            prefix[i] = -1
            suffix[i] = i.toByte()
        }

        var bitBuffer = 0
        var bitCount = 0
        var dataPos = 0
        var outPos = 0
        var top = 0
        var oldCode = -1
        var firstChar = 0

        while (outPos < expectedLength) {
            if (top == 0) {
                while (bitCount < codeSize) {
                    if (dataPos >= lzwData.size) break
                    bitBuffer = bitBuffer or ((lzwData[dataPos++].toInt() and 0xFF) shl bitCount)
                    bitCount += 8
                }
                if (bitCount < codeSize) break

                var code = bitBuffer and ((1 shl codeSize) - 1)
                bitBuffer = bitBuffer ushr codeSize
                bitCount -= codeSize

                if (code == clearCode) {
                    codeSize = minCodeSize + 1
                    maxCode = 1 shl codeSize
                    available = clearCode + 2
                    oldCode = -1
                    continue
                }
                if (code == endCode) break

                if (oldCode == -1) {
                    if (code >= available) code = 0
                    firstChar = suffix[code].toInt() and 0xFF
                    output[outPos++] = firstChar.toByte()
                    oldCode = code
                    continue
                }

                val inCode = code
                if (code >= available) {
                    pixelStack[top++] = firstChar.toByte()
                    code = oldCode
                }

                while (code >= clearCode && top < pixelStack.size - 1) {
                    pixelStack[top++] = suffix[code]
                    code = prefix[code]
                }
                firstChar = suffix[code].toInt() and 0xFF
                pixelStack[top++] = firstChar.toByte()

                if (available < 4096) {
                    prefix[available] = oldCode
                    suffix[available] = firstChar.toByte()
                    available++
                    if (available >= maxCode && codeSize < 12) {
                        codeSize++
                        maxCode = 1 shl codeSize
                    }
                }
                oldCode = inCode
            }
            output[outPos++] = pixelStack[--top]
        }
        return output
    }

    private fun readInt16LE(data: ByteArray, offset: Int): Int {
        return (data[offset].toInt() and 0xFF) or ((data[offset + 1].toInt() and 0xFF) shl 8)
    }
}
