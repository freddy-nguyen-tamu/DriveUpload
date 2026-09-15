package com.xong.driveupload

import android.content.Context

class UploadStateStore(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun load(): UploadSnapshot {
        val rawStatus = prefs.getString(KEY_STATUS, UploadStatus.IDLE.name) ?: UploadStatus.IDLE.name
        val status = runCatching { UploadStatus.valueOf(rawStatus) }.getOrDefault(UploadStatus.IDLE)
        return UploadSnapshot(
            status = status,
            sourceUri = prefs.getString(KEY_URI, "") ?: "",
            fileName = prefs.getString(KEY_NAME, "") ?: "",
            mimeType = prefs.getString(KEY_MIME, "application/octet-stream") ?: "application/octet-stream",
            totalBytes = prefs.getLong(KEY_TOTAL, 0L),
            uploadedBytes = prefs.getLong(KEY_UPLOADED, 0L),
            accountEmail = prefs.getString(KEY_EMAIL, "") ?: "",
            sessionUrl = prefs.getString(KEY_SESSION, "") ?: "",
            folderId = prefs.getString(KEY_FOLDER, "") ?: "",
            fileId = prefs.getString(KEY_FILE_ID, "") ?: "",
            webViewLink = prefs.getString(KEY_LINK, "") ?: "",
            errorMessage = prefs.getString(KEY_ERROR, "") ?: ""
        )
    }

    fun save(snapshot: UploadSnapshot) {
        prefs.edit()
            .putString(KEY_STATUS, snapshot.status.name)
            .putString(KEY_URI, snapshot.sourceUri)
            .putString(KEY_NAME, snapshot.fileName)
            .putString(KEY_MIME, snapshot.mimeType)
            .putLong(KEY_TOTAL, snapshot.totalBytes)
            .putLong(KEY_UPLOADED, snapshot.uploadedBytes)
            .putString(KEY_EMAIL, snapshot.accountEmail)
            .putString(KEY_SESSION, snapshot.sessionUrl)
            .putString(KEY_FOLDER, snapshot.folderId)
            .putString(KEY_FILE_ID, snapshot.fileId)
            .putString(KEY_LINK, snapshot.webViewLink)
            .putString(KEY_ERROR, snapshot.errorMessage)
            .apply()
    }

    fun clear() {
        prefs.edit().clear().apply()
    }

    companion object {
        const val ACTION_UPLOAD_STATE_CHANGED = "com.xong.driveupload.UPLOAD_STATE_CHANGED"
        private const val PREFS = "drive_upload_state"
        private const val KEY_STATUS = "status"
        private const val KEY_URI = "uri"
        private const val KEY_NAME = "name"
        private const val KEY_MIME = "mime"
        private const val KEY_TOTAL = "total"
        private const val KEY_UPLOADED = "uploaded"
        private const val KEY_EMAIL = "email"
        private const val KEY_SESSION = "session"
        private const val KEY_FOLDER = "folder"
        private const val KEY_FILE_ID = "file_id"
        private const val KEY_LINK = "link"
        private const val KEY_ERROR = "error"
    }
}
