package org.tan.cdntest

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query

@Entity(tableName = "download_records")
data class DownloadRecordEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val url: String,
    val path: String,
    val size: Long,
    val date: Long
)

@Dao
interface DownloadRecordDao {
    @Query("SELECT * FROM download_records ORDER BY date DESC")
    fun getAll(): List<DownloadRecordEntity>

    @Query("SELECT * FROM download_records WHERE url = :url LIMIT 1")
    fun getByUrl(url: String): DownloadRecordEntity?

    @Query("SELECT * FROM download_records WHERE name = :name LIMIT 1")
    fun getByName(name: String): DownloadRecordEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insert(record: DownloadRecordEntity)

    @Query("DELETE FROM download_records WHERE url = :url")
    fun deleteByUrl(url: String)

    @Query("DELETE FROM download_records")
    fun deleteAll()
}
