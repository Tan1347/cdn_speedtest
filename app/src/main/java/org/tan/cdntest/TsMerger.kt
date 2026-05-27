package org.tan.cdntest

import android.content.Context
import android.util.Log
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

    private const val TAG = "TsMerger"

    fun merge(
        context: Context,
        segments: List<TsDownloadResult>,
        outputName: String,
        format: TsOutputFormat
    ): File? {
        val tempDir = File(context.cacheDir, "ts_temp_${System.currentTimeMillis()}")
        tempDir.mkdirs()

        try {
            Log.i(TAG, "开始合并 ${segments.size} 个分片, 格式: ${format.label}")

            // Validate segments
            val emptySegments = segments.filter { it.data.isEmpty() }
            if (emptySegments.isNotEmpty()) {
                Log.e(TAG, "${emptySegments.size} 个分片数据为空")
            }

            // Write segments to temp files
            val segmentFiles = segments.map { result ->
                val file = File(tempDir, "seg_%05d.ts".format(result.index))
                file.writeBytes(result.data)
                file
            }

            Log.i(TAG, "分片已写入临时目录: ${tempDir.absolutePath}, 总大小: ${segmentFiles.sumOf { it.length() }} bytes")

            // Create concat list file
            val concatFile = File(tempDir, "concat.txt")
            concatFile.writeText(segmentFiles.joinToString("\n") { "file '${it.absolutePath}'" })

            val outputDir = DownloadHelper.getDownloadDir(context)
            if (!outputDir.exists()) outputDir.mkdirs()
            val baseName = outputName.substringBeforeLast(".")
            val outputFile = File(outputDir, "$baseName.${format.extension}")

            val command = buildCommand(concatFile.absolutePath, outputFile.absolutePath, format)
            Log.i(TAG, "FFmpeg 命令: $command")

            val session = FFmpegKit.execute(command)
            val returnCode = session.returnCode
            val output = session.output

            if (ReturnCode.isSuccess(returnCode)) {
                Log.i(TAG, "FFmpeg 合并成功: ${outputFile.absolutePath}, 大小: ${outputFile.length()} bytes")
                tempDir.deleteRecursively()
                return outputFile
            } else {
                Log.e(TAG, "FFmpeg 失败, 返回码: $returnCode")
                Log.e(TAG, "FFmpeg 输出: ${output?.takeLast(2000)}")
                tempDir.deleteRecursively()
                return null
            }
        } catch (e: Exception) {
            Log.e(TAG, "合并异常: ${e.message}", e)
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
