package com.xong.driveupload

data class DriveQuota(
    val email: String,
    val displayName: String?,
    val limitBytes: Long?,
    val usedBytes: Long,
    val maxUploadBytes: Long?
) {
    val availableBytes: Long?
        get() = limitBytes?.let { (it - usedBytes).coerceAtLeast(0L) }
}

data class DriveFileItem(
    val id: String,
    val name: String,
    val sizeBytes: Long?,
    val webViewLink: String?,
    val createdTime: String?,
    val parentId: String?
)

enum class ShareMode {
    PRIVATE,
    ANYONE_WITH_LINK,
    SPECIFIC_PEOPLE
}

data class SharingInfo(
    val mode: ShareMode,
    val emails: List<String>
)

data class LocalFileMeta(
    val uri: String,
    val name: String,
    val mimeType: String,
    val sizeBytes: Long
)

enum class UploadStatus {
    IDLE,
    RUNNING,
    COMPLETED,
    ERROR,
    CANCELED
}

data class UploadSnapshot(
    val status: UploadStatus = UploadStatus.IDLE,
    val sourceUri: String = "",
    val fileName: String = "",
    val mimeType: String = "application/octet-stream",
    val totalBytes: Long = 0L,
    val uploadedBytes: Long = 0L,
    val accountEmail: String = "",
    val sessionUrl: String = "",
    val folderId: String = "",
    val fileId: String = "",
    val webViewLink: String = "",
    val errorMessage: String = ""
)
