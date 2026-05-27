package org.tan.cdntest

import android.content.Context

data class DownloadRecord(
    val name: String,
    val url: String,
    val path: String,
    val size: Long,
    val date: Long
)

object DownloadRecordStore {

    private fun dao(context: Context): DownloadRecordDao {
        return AppDatabase.getInstance(context).downloadRecordDao()
    }

    fun add(context: Context, record: DownloadRecord) {
        val existing = dao(context).getByUrl(record.url)
        if (existing != null) {
            dao(context).deleteByUrl(record.url)
        }
        dao(context).insert(
            DownloadRecordEntity(
                name = record.name,
                url = record.url,
                path = record.path,
                size = record.size,
                date = record.date
            )
        )
    }

    fun getAll(context: Context): List<DownloadRecord> {
        return dao(context).getAll().map {
            DownloadRecord(
                name = it.name,
                url = it.url,
                path = it.path,
                size = it.size,
                date = it.date
            )
        }
    }

    fun deleteByUrl(context: Context, url: String) {
        dao(context).deleteByUrl(url)
    }
}
