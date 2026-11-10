package com.github.libretube.db.obj

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "queuedDownload")
data class QueuedDownload(
    @PrimaryKey val videoId: String,
    val title: String,
    val source: String,
    val screenWidth: Int,
    val screenHeight: Int,
    val createdAt: Long,
    val state: String = "QUEUED",
    val selection: String? = null,
    val downloadedBytes: Long = 0,
    val totalBytes: Long = -1
)
