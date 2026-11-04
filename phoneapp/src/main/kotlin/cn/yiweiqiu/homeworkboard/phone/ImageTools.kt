package cn.yiweiqiu.homeworkboard.phone

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * 图片压缩：长边 ≤1600px、JPEG q=0.8。
 *
 * "解码再重新编码"本身就剥离了 EXIF（含拍照定位），所以不需要额外读 ExifInterface。
 * 方向问题：EXIF 里的 Orientation 也一起丢了，这里先按 EXIF 旋转像素再编码，否则竖拍的图会在电视上躺倒。
 */
object ImageTools {

    private const val MAX_EDGE = 1600
    private const val QUALITY = 80
    private const val TAG = "ImageTools"

    /** 从相册 Uri 读，压成 JPEG 字节 */
    fun compress(context: Context, uri: Uri): ByteArray? = runCatching {
        val resolver = context.contentResolver
        // 第一遍只读尺寸，算出采样率，避免把 5000px 的整图读进内存
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri).use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0) return null

        val opts = BitmapFactory.Options().apply {
            inSampleSize = sampleSizeFor(bounds.outWidth, bounds.outHeight)
        }
        val decoded = resolver.openInputStream(uri).use { BitmapFactory.decodeStream(it, null, opts) }
            ?: return null

        encode(decoded, readRotation(resolver, uri))
    }.onFailure { Log.w(TAG, "compress failed", it) }.getOrNull()

    /** 拍照临时文件走这条：已经是我们自己的文件，不需要 contentResolver */
    fun compress(file: File): ByteArray? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0) return null

        val opts = BitmapFactory.Options().apply {
            inSampleSize = sampleSizeFor(bounds.outWidth, bounds.outHeight)
        }
        val decoded = BitmapFactory.decodeFile(file.absolutePath, opts) ?: return null
        encode(decoded, 0)
    }.onFailure { Log.w(TAG, "compress file failed", it) }.getOrNull()

    private fun encode(source: Bitmap, rotationDegrees: Int): ByteArray {
        val scaled = scaleToMaxEdge(source)
        val rotated = if (rotationDegrees % 360 == 0) {
            scaled
        } else {
            Bitmap.createBitmap(
                scaled, 0, 0, scaled.width, scaled.height,
                Matrix().apply { postRotate(rotationDegrees.toFloat()) },
                true,
            )
        }

        val out = ByteArrayOutputStream()
        rotated.compress(Bitmap.CompressFormat.JPEG, QUALITY, out)
        if (rotated !== scaled && !scaled.isRecycled) scaled.recycle()
        if (rotated !== source && !source.isRecycled) source.recycle()
        return out.toByteArray()
    }

    private fun scaleToMaxEdge(src: Bitmap): Bitmap {
        val longEdge = maxOf(src.width, src.height)
        if (longEdge <= MAX_EDGE) return src
        val ratio = MAX_EDGE.toFloat() / longEdge
        return Bitmap.createScaledBitmap(src, (src.width * ratio).toInt(), (src.height * ratio).toInt(), true)
    }

    private fun sampleSizeFor(width: Int, height: Int): Int {
        var sample = 1
        var longEdge = maxOf(width, height)
        // 先降到 2 倍目标尺寸内，再交给 createScaledBitmap 精确缩放
        while (longEdge / 2 >= MAX_EDGE * 2) {
            sample *= 2
            longEdge /= 2
        }
        return sample
    }

    private fun readRotation(resolver: android.content.ContentResolver, uri: Uri): Int = runCatching {
        resolver.openInputStream(uri).use { input ->
            val exif = androidx.exifinterface.media.ExifInterface(input ?: return 0)
            when (exif.getAttributeInt(ExifInterfaceTAG, 1)) {
                6 -> 90
                3 -> 180
                8 -> 270
                else -> 0
            }
        }
    }.getOrDefault(0)

    /** 避免顶部再引一个 import 名字冲突 */
    private const val ExifInterfaceTAG = "Orientation"
}
