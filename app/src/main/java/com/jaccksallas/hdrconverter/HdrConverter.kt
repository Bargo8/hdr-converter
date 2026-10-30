package com.jaccksallas.hdrconverter

import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.OpenableColumns
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.FFmpegKitConfig
import com.arthenica.ffmpegkit.FFprobeKit
import com.arthenica.ffmpegkit.ReturnCode
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import kotlin.coroutines.resume

private const val HDR_FILTER =
    "scale=out_color_matrix=bt709:out_range=tv," +
    "colorlevels=romax=0.9:gomax=0.9:bomax=0.9," +
    "format=yuv420p10le"

private const val X265_PARAMS =
    "hdr-opt=1:repeat-headers=1:colorprim=bt2020:transfer=smpte2084:" +
    "colormatrix=bt2020nc:" +
    "master-display=G(13250,34500)B(7500,3000)R(34000,16000)WP(15635,16450)L(10000000,1):" +
    "max-cll=1000,400"

fun displayName(context: Context, uri: Uri): String? =
    context.contentResolver.query(uri, null, null, null, null)?.use { c ->
        val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
        if (c.moveToFirst() && idx >= 0) c.getString(idx) else null
    }

// content:// uri tidak bisa dibaca ffmpeg langsung, jadi disalin dulu ke cache
fun copyToCache(context: Context, uri: Uri): File {
    val dst = File(context.cacheDir, "input_src")
    context.contentResolver.openInputStream(uri)?.use { input ->
        dst.outputStream().use { input.copyTo(it) }
    } ?: error("gagal buka file input")
    return dst
}

fun outputFileFor(context: Context, inputName: String?): File {
    val dir = context.getExternalFilesDir(Environment.DIRECTORY_MOVIES) ?: context.filesDir
    val base = inputName?.substringBeforeLast('.') ?: "video"
    return File(dir, "${base}_HDR_Natural.mp4")
}

fun probeDurationMs(path: String): Long =
    FFprobeKit.getMediaInformation(path).mediaInformation
        ?.duration?.toDoubleOrNull()
        ?.times(1000)?.toLong() ?: 0L

suspend fun runHdr(
    input: String,
    output: String,
    durationMs: Long,
    onProgress: (Float) -> Unit,
    onSession: (Long) -> Unit,
): Boolean = suspendCancellableCoroutine { cont ->
    val cmd = "-i \"$input\" -vf \"$HDR_FILTER\" " +
            "-c:v libx265 -preset fast -crf 20 " +
            "-x265-params \"$X265_PARAMS\" " +
            "-c:a copy \"$output\""

    FFmpegKitConfig.enableStatisticsCallback { stats ->
        if (durationMs > 0) {
            onProgress((stats.time.toFloat() / durationMs).coerceIn(0f, 1f))
        }
    }

    val session = FFmpegKit.executeAsync(cmd) { s ->
        FFmpegKitConfig.enableStatisticsCallback(null)
        if (cont.isActive) cont.resume(ReturnCode.isSuccess(s.returnCode))
    }
    onSession(session.sessionId)

    cont.invokeOnCancellation { FFmpegKit.cancel(session.sessionId) }
}
