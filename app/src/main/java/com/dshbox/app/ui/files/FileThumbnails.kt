package com.dshbox.app.ui.files

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.Drawable
import android.media.MediaMetadataRetriever
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dshbox.app.common.R as CommonR
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 列表缩略图 / 类型徽标（[补丁] 本轮新增）。
 *
 * 用户诉求：「不点击也要能看出这是什么」——文件列表与「选择沙箱文件」列表里，
 * 图片给真缩略图、视频给首帧、APK 给应用图标，其余类型至少按类型给不同底色的
 * 扩展名徽标（此前清一色 `ic_file` 通用图标，连 jpg 也认不出来）。
 *
 * 三条硬约束：
 * 1. **防 OOM**：两遍解码（先 `inJustDecodeBounds` 读尺寸算 `inSampleSize`，再下采样解码），
 *    解码目标最长边仅 [THUMB_TARGET_PX]（192px），单个缩略图 ≈ 数十 KB；
 * 2. **不在主线程做磁盘 IO**：解码/读 APK/取首帧全在 [Dispatchers.IO]；
 * 3. **复用即取消**：用 `produceState` 承载，列表回收/换项时协程被取消，不会把
 *    过期结果写回状态，也不需要手工 `recycle()`（缓存里的位图仍被 LRU 持有）。
 *
 * 只读：不解码就不写任何文件，也不接触 WebView / JavascriptInterface。
 */

/** 缩略图解码目标最长边（px）。列表方块 ~40dp，2x 屏取 192 已有余量。 */
private const val THUMB_TARGET_PX = 192

private val IMAGE_EXTS = setOf(
    "jpg", "jpeg", "jfif", "png", "webp", "gif", "bmp", "heic", "heif", "avif",
)
private val VIDEO_EXTS = setOf("mp4", "mkv", "webm", "avi", "mov", "3gp", "m4v", "ts")
private val AUDIO_EXTS = setOf("mp3", "wav", "ogg", "flac", "m4a", "aac", "opus")
private val ARCHIVE_EXTS = setOf(
    "zip", "jar", "7z", "rar", "gz", "tgz", "bz2", "xz", "zst", "tar", "apk", "apks", "xapk",
)

/** 扩展名（小写，无点）；无扩展名给空串。 */
internal fun thumbExtensionOf(name: String): String =
    name.substringAfterLast('.', "").lowercase()

/** 内存 LRU：按位图字节数计权，上限取堆的 1/16 并夹在 4–12 MiB。 */
internal object ThumbnailCache {
    private val maxBytes: Int = run {
        val heap = Runtime.getRuntime().maxMemory()
        (heap / 16).coerceIn(4L * 1024 * 1024, 12L * 1024 * 1024).toInt()
    }

    private val lru = object : LruCache<String, Bitmap>(maxBytes) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    /** 缓存键只用绝对路径：避免在主线程为 key 做 `length()/lastModified()` 磁盘 IO。 */
    fun get(path: String): Bitmap? = runCatching { lru.get(path) }.getOrNull()

    fun put(path: String, bitmap: Bitmap) {
        runCatching { lru.put(path, bitmap) }
    }
}

/** 下采样系数：最长边反复折半，直到不小于目标值。 */
private fun sampleSizeFor(width: Int, height: Int, targetPx: Int): Int {
    var sample = 1
    var longest = maxOf(width, height)
    while (longest / 2 >= targetPx) {
        longest /= 2
        sample *= 2
    }
    return sample.coerceAtLeast(1)
}

/** 等比缩到最长边不超过 [maxPx]；缩放产生新位图时回收自己刚解码的那份。 */
private fun scaleToMax(source: Bitmap, maxPx: Int): Bitmap {
    val longest = maxOf(source.width, source.height)
    if (longest <= maxPx || longest <= 0) return source
    val ratio = maxPx.toFloat() / longest.toFloat()
    val w = (source.width * ratio).toInt().coerceAtLeast(1)
    val h = (source.height * ratio).toInt().coerceAtLeast(1)
    val scaled = runCatching { Bitmap.createScaledBitmap(source, w, h, true) }.getOrNull() ?: return source
    if (scaled !== source) runCatching { source.recycle() }
    return scaled
}

/**
 * 图片缩略图：先读边界算 `inSampleSize`，再下采样解码（两遍，绝不整图进内存）。
 * 解码失败（损坏/无权限/非图片）返回 null，调用方退回类型徽标。
 */
internal fun decodeImageThumbnail(file: File, targetPx: Int = THUMB_TARGET_PX): Bitmap? = runCatching {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.absolutePath, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    val options = BitmapFactory.Options().apply {
        inSampleSize = sampleSizeFor(bounds.outWidth, bounds.outHeight, targetPx)
        inScaled = false
    }
    val decoded = BitmapFactory.decodeFile(file.absolutePath, options) ?: return null
    scaleToMax(decoded, targetPx * 2)
}.getOrNull()

/** 视频首帧（同步帧）缩略图；取不到返回 null。 */
internal fun decodeVideoThumbnail(file: File, targetPx: Int = THUMB_TARGET_PX): Bitmap? = runCatching {
    val retriever = MediaMetadataRetriever()
    try {
        retriever.setDataSource(file.absolutePath)
        val frame = retriever.getFrameAtTime(0L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC) ?: return null
        scaleToMax(frame, targetPx * 2)
    } finally {
        runCatching { retriever.release() }
    }
}.getOrNull()

/** APK 的图标 + 应用名（读不到就是 null，绝不因此变慢或崩）。 */
internal class ApkThumb(val icon: Bitmap?, val label: String?)

/**
 * 用 `PackageManager.getPackageArchiveInfo` 读 APK 的 label 与 icon。
 * 必须先把 `sourceDir/publicSourceDir` 指回文件本身，否则 `loadIcon` 取不到资源。
 */
internal fun readApkThumb(context: Context, file: File, targetPx: Int = THUMB_TARGET_PX): ApkThumb? = runCatching {
    val pm = context.packageManager
    @Suppress("DEPRECATION")
    val info = pm.getPackageArchiveInfo(file.absolutePath, 0) ?: return null
    val app = info.applicationInfo ?: return null
    app.sourceDir = file.absolutePath
    app.publicSourceDir = file.absolutePath
    val label = runCatching { app.loadLabel(pm).toString() }.getOrNull()
    val icon = runCatching { app.loadIcon(pm) }.getOrNull()?.let { drawableToThumb(it, targetPx) }
    ApkThumb(icon, label)
}.getOrNull()

private fun drawableToThumb(drawable: Drawable, targetPx: Int): Bitmap? = runCatching {
    val width = drawable.intrinsicWidth.takeIf { it > 0 } ?: targetPx
    val height = drawable.intrinsicHeight.takeIf { it > 0 } ?: targetPx
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val canvas = android.graphics.Canvas(bitmap)
    drawable.setBounds(0, 0, canvas.width, canvas.height)
    drawable.draw(canvas)
    scaleToMax(bitmap, targetPx)
}.getOrNull()

private sealed class ThumbState {
    object None : ThumbState()
    class Ready(val bitmap: Bitmap, val label: String?) : ThumbState()
}

/**
 * 文件行左侧的「缩略图 / 类型徽标」。目录仍用文件夹图标（[tint] 沿用各调用点原色）。
 *
 * @param file 目标文件（目录只用于取名字）。
 * @param isDirectory 由调用方传入（避免在 Compose 里做磁盘 IO）。
 * @param size 图标方块边长。
 * @param tint 目录图标着色。
 */
@Composable
internal fun FileThumb(
    file: File,
    isDirectory: Boolean,
    size: Dp,
    tint: Color,
    modifier: Modifier = Modifier,
) {
    if (isDirectory) {
        Icon(
            imageVector = ImageVector.vectorResource(CommonR.drawable.ic_folder),
            contentDescription = null,
            tint = tint,
            modifier = modifier.size(size),
        )
        return
    }

    val context = LocalContext.current
    val extension = thumbExtensionOf(file.name)
    val path = file.absolutePath
    val state by produceState<ThumbState>(
        initialValue = ThumbnailCache.get(path)?.let { ThumbState.Ready(it, null) } ?: ThumbState.None,
        path,
        extension,
    ) {
        if (value is ThumbState.Ready) return@produceState
        val loaded: ThumbState? = withContext(Dispatchers.IO) {
            when {
                extension in IMAGE_EXTS -> decodeImageThumbnail(file)
                    ?.let { ThumbState.Ready(it, null) }
                extension in VIDEO_EXTS -> decodeVideoThumbnail(file)
                    ?.let { ThumbState.Ready(it, null) }
                extension == "apk" -> readApkThumb(context, file)
                    ?.takeIf { it.icon != null }
                    ?.let { ThumbState.Ready(it.icon!!, it.label) }
                else -> null
            }
        }
        if (loaded is ThumbState.Ready) {
            ThumbnailCache.put(path, loaded.bitmap)
            value = loaded
        }
    }

    val ready = state as? ThumbState.Ready
    if (ready != null) {
        Image(
            bitmap = ready.bitmap.asImageBitmap(),
            // APK 读到的应用名进无障碍描述（"看得出是什么"的一部分）
            contentDescription = ready.label ?: file.name,
            contentScale = ContentScale.Crop,
            modifier = modifier
                .size(size)
                .clip(RoundedCornerShape(6.dp)),
        )
    } else {
        TypeBadge(extension = extension, size = size, modifier = modifier)
    }
}

/** 类型徽标：按类型给不同底色 + 扩展名文字，替代清一色的通用文件图标。 */
@Composable
private fun TypeBadge(extension: String, size: Dp, modifier: Modifier = Modifier) {
    val text = extension.take(4).uppercase().ifEmpty { "?" }
    Box(
        modifier = modifier
            .size(size)
            .clip(RoundedCornerShape(6.dp))
            .background(badgeColorFor(extension)),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            fontSize = (size.value * 0.30f).sp,
            fontWeight = FontWeight.SemiBold,
            color = Color.White,
            maxLines = 1,
        )
    }
}

private val BADGE_IMAGE = Color(0xFF0E7490) // cyan-700
private val BADGE_VIDEO = Color(0xFF7C3AED) // violet-600
private val BADGE_AUDIO = Color(0xFFEA580C) // orange-600
private val BADGE_PDF = Color(0xFFDC2626) // red-600
private val BADGE_DOC = Color(0xFF2563EB) // blue-600
private val BADGE_SHEET = Color(0xFF15803D) // green-700
private val BADGE_SLIDE = Color(0xFFC2410C) // orange-700
private val BADGE_ARCHIVE = Color(0xFF92400E) // amber-800
private val BADGE_APK = Color(0xFF047857) // emerald-700
private val BADGE_TEXT = Color(0xFF475569) // slate-600
private val BADGE_OTHER = Color(0xFF6B7280) // gray-500

private fun badgeColorFor(extension: String): Color = when (extension) {
    in IMAGE_EXTS -> BADGE_IMAGE
    in VIDEO_EXTS -> BADGE_VIDEO
    in AUDIO_EXTS -> BADGE_AUDIO
    "pdf" -> BADGE_PDF
    "apk", "apks", "xapk", "idsig" -> BADGE_APK
    "doc", "docx", "odt", "rtf" -> BADGE_DOC
    "xls", "xlsx", "ods", "csv" -> BADGE_SHEET
    "ppt", "pptx", "odp" -> BADGE_SLIDE
    in ARCHIVE_EXTS -> BADGE_ARCHIVE
    "md", "markdown", "txt", "log", "json", "xml", "yml", "yaml", "toml", "ini",
    "kt", "java", "js", "ts", "py", "sh", "c", "cpp", "h", "go", "rs", "html", "htm", "css", "sql",
    -> BADGE_TEXT
    else -> BADGE_OTHER
}
