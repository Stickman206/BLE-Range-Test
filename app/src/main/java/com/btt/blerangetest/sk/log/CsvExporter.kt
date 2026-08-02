package com.btt.blerangetest.sk.log

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File

/**
 * CSV 日志导出：通过系统分享（微信/文件管理器等）发送日志文件。
 */
object CsvExporter {

    /**
     * 分享最新日志文件；无文件时返回 false。
     */
    fun shareLatest(context: Context): Boolean {
        val logger = CsvLogger(context)
        val file = logger.currentFile ?: logger.listLogFiles().firstOrNull() ?: return false
        return shareFile(context, file)
    }

    /**
     * 分享指定文件。
     */
    fun shareFile(context: Context, file: File): Boolean {
        if (!file.exists()) return false
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file
        )
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/csv"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, file.name)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(send, "导出 CSV 日志"))
        return true
    }

    /**
     * 列出所有历史日志文件。
     */
    fun listLogFiles(context: Context): List<File> = CsvLogger(context).listLogFiles()
}
