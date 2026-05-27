package org.tan.cdntest

import android.content.Context
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import java.io.File

enum class TsOutputFormat(val label: String, val extension: String) {
    ORIGINAL("原格式 (.ts)", "ts"),
    MP4("MP4 容器", "mp4"),
    H264("H.264 编码 (.mp4)", "mp4"),
    HEVC("H.265/HEVC 编码 (.mp4)", "mp4"),
    AV1("AV1 编码 (.mp4)", "mp4")
}

object TsMerger {

    fun merge(
        context: Context,
        segments: List<TsDownloadResult>,
        outputName: String,
        format: TsOutputFormat,
        onProgress: ((percent: Int) -> Unit)? = null
    ): File? {
        val tempDir = File(context.cacheDir, "ts_temp_${System.currentTimeMillis()}")
        tempDir.mkdirs()

        try {
            // Write segments to temp files
            val segmentFiles = segments.map { result ->
                val file = File(tempDir, "seg_%05d.ts".format(result.index))
                file.writeBytes(result.data)
                file
            }

            // Create concat list file
            val concatFile = File(tempDir, "concat.txt")
            concatFile.writeText(segmentFiles.joinToString("\n") { "file '${it.absolutePath}'" })

            val outputDir = DownloadHelper.getDownloadDir(context)
            val baseName = outputName.substringBeforeLast(".")
            val outputFile = File(outputDir, "$baseName.${format.extension}")

            val command = buildCommand(concatFile.absolutePath, outputFile.absolutePath, format)
            val session = FFmpegKit.execute(command)

            return if (ReturnCode.isSuccess(session.returnCode)) {
                // Clean up temp files
                tempDir.deleteRecursively()
                outputFile
            } else {
                tempDir.deleteRecursively()
                null
            }
        } catch (_: Exception) {
            tempDir.deleteRecursively()
            return null
        }
    }

    private fun buildCommand(inputPath: String, outputPath: String, format: TsOutputFormat): String {
        return when (format) {
            TsOutputFormat.ORIGINAL ->
                "-f concat -safe 0 -i \"$inputPath\" -c copy \"$outputPath\""
            TsOutputFormat.MP4 ->
                "-f concat -safe 0 -i \"$inputPath\" -c copy -movflags +faststart \"$outputPath\""
            TsOutputFormat.H264 ->
                "-f concat -safe 0 -i \"$inputPath\" -c:v libx264 -preset medium -crf 23 -c:a aac -b:a 128k -movflags +faststart \"$outputPath\""
            TsOutputFormat.HEVC ->
                "-f concat -safe 0 -i \"$inputPath\" -c:v libx265 -preset medium -crf 28 -c:a aac -b:a 128k -movflags +faststart \"$outputPath\""
            TsOutputFormat.AV1 ->
                "-f concat -safe 0 -i \"$inputPath\" -c:v libsvtav1 -crf 35 -c:a libopus -b:a 128k -movflags +faststart \"$outputPath\""
        }
    }
}
