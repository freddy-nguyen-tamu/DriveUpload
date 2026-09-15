package com.xong.driveupload

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import java.io.Closeable
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream
import java.nio.channels.FileChannel
import kotlin.math.min

/**
 * Google Drive upload core shared by the foreground-service fallback (Android <= 13)
 * and Android 14+ user-initiated data-transfer JobScheduler job.
 */
class UploadEngine(
    context: Context,
    private val cancelCheck: () -> Boolean,
    private val onStateChanged: (UploadSnapshot) -> Unit
) {
    private val appContext = context.applicationContext
    private val stateStore = UploadStateStore(appContext)
    private val auth = AuthManager(appContext)
    private val drive = DriveApi()

    suspend fun run(): UploadSnapshot {
        var snapshot = stateStore.load().copy(status = UploadStatus.RUNNING, errorMessage = "")
        persist(snapshot)
        ensureNotCanceled()

        var token = auth.tokenForAccountBlocking(snapshot.accountEmail)

        // Re-check quota immediately before transfer because storage may have changed after the
        // file picker preflight. This remains constant-memory regardless of file size.
        val quota = drive.getAccountAndQuota(token)
        val maxUpload = quota.maxUploadBytes ?: Utils.maxDriveFileBytes()
        if (snapshot.totalBytes > maxUpload) {
            throw IOException("Tệp lớn hơn giới hạn tải lên hiện tại của Google Drive (${Utils.formatBytes(maxUpload)}).")
        }
        quota.availableBytes?.let { available ->
            if (snapshot.totalBytes > available) {
                throw IOException(
                    "Không đủ dung lượng Google Drive: tệp cần ${Utils.formatBytes(snapshot.totalBytes)} nhưng chỉ còn khoảng ${Utils.formatBytes(available)}."
                )
            }
        }

        var folderId = snapshot.folderId
        if (folderId.isBlank()) {
            folderId = drive.ensureUploadFolder(token)
            snapshot = snapshot.copy(folderId = folderId)
            persist(snapshot)
        }

        if (snapshot.totalBytes == 0L) {
            val emptyFile = drive.createEmptyFile(
                token,
                folderId,
                snapshot.fileName,
                snapshot.mimeType
            )
            return completeUpload(snapshot.copy(uploadedBytes = 0L), emptyFile)
        }

        var sessionUrl = snapshot.sessionUrl
        if (sessionUrl.isBlank()) {
            sessionUrl = drive.createResumableSession(
                token,
                folderId,
                snapshot.fileName,
                snapshot.mimeType,
                snapshot.totalBytes
            )
            snapshot = snapshot.copy(sessionUrl = sessionUrl, uploadedBytes = 0L)
            persist(snapshot)
        }

        var resume = queryWithTokenRefresh(sessionUrl, token, snapshot.totalBytes) { token = it }
        if (resume.expired) {
            sessionUrl = drive.createResumableSession(
                token,
                folderId,
                snapshot.fileName,
                snapshot.mimeType,
                snapshot.totalBytes
            )
            resume = ResumeResult(0L, null, completed = false, expired = false)
            snapshot = snapshot.copy(sessionUrl = sessionUrl, uploadedBytes = 0L)
            persist(snapshot)
        }
        if (resume.completed && resume.file != null) {
            return completeUpload(snapshot, resume.file)
        }

        var offset = resume.confirmedOffset.coerceIn(0L, snapshot.totalBytes)
        snapshot = snapshot.copy(uploadedBytes = offset)
        persist(snapshot)

        UriSourceReader(appContext, Uri.parse(snapshot.sourceUri)).use { source ->
            source.position(offset)
            val buffer = ByteArray(CHUNK_BYTES)

            while (offset < snapshot.totalBytes) {
                ensureNotCanceled()
                val wanted = min(buffer.size.toLong(), snapshot.totalBytes - offset).toInt()
                val read = source.readFully(buffer, wanted)
                if (read <= 0) throw IOException("Không đọc được dữ liệu tệp từ Android.")

                var attempts = 0
                var chunkHandled = false
                while (!chunkHandled) {
                    ensureNotCanceled()
                    try {
                        val result = drive.uploadChunk(
                            sessionUrl,
                            token,
                            snapshot.mimeType,
                            snapshot.totalBytes,
                            offset,
                            buffer,
                            read
                        )
                        if (result.completed) {
                            val file = result.file
                                ?: throw IOException("Google Drive không trả lại thông tin tệp sau khi tải xong.")
                            snapshot = snapshot.copy(uploadedBytes = snapshot.totalBytes)
                            persist(snapshot)
                            return completeUpload(snapshot, file)
                        }
                        offset = result.confirmedOffset.coerceAtLeast(offset)
                        snapshot = snapshot.copy(uploadedBytes = offset)
                        persist(snapshot)
                        source.position(offset)
                        chunkHandled = true
                    } catch (_: DriveAuthExpiredException) {
                        token = auth.tokenForAccountBlocking(snapshot.accountEmail)
                    } catch (e: Throwable) {
                        if (!e.isRetryableUploadError()) throw e
                        attempts++
                        if (attempts > MAX_RETRIES_PER_CHUNK) throw e
                        delay((attempts * attempts * 1000L).coerceAtMost(20_000L))
                        ensureNotCanceled()
                        try {
                            val check = queryWithTokenRefresh(sessionUrl, token, snapshot.totalBytes) { token = it }
                            when {
                                check.expired -> {
                                    sessionUrl = drive.createResumableSession(
                                        token,
                                        folderId,
                                        snapshot.fileName,
                                        snapshot.mimeType,
                                        snapshot.totalBytes
                                    )
                                    offset = 0L
                                    snapshot = snapshot.copy(sessionUrl = sessionUrl, uploadedBytes = 0L)
                                    persist(snapshot)
                                    source.position(0L)
                                    chunkHandled = true
                                }
                                check.completed && check.file != null -> {
                                    return completeUpload(
                                        snapshot.copy(uploadedBytes = snapshot.totalBytes),
                                        check.file
                                    )
                                }
                                check.confirmedOffset != offset -> {
                                    offset = check.confirmedOffset.coerceIn(0L, snapshot.totalBytes)
                                    snapshot = snapshot.copy(uploadedBytes = offset)
                                    persist(snapshot)
                                    source.position(offset)
                                    chunkHandled = true
                                }
                            }
                        } catch (_: DriveAuthExpiredException) {
                            token = auth.tokenForAccountBlocking(snapshot.accountEmail)
                        } catch (_: Throwable) {
                            // Keep retrying the last Drive-confirmed chunk. The resumable session is persisted.
                        }
                    }
                }
            }
        }

        val finalCheck = queryWithTokenRefresh(sessionUrl, token, snapshot.totalBytes) { token = it }
        if (finalCheck.completed && finalCheck.file != null) {
            return completeUpload(snapshot.copy(uploadedBytes = snapshot.totalBytes), finalCheck.file)
        }
        throw IOException("Google Drive chưa xác nhận tệp đã tải xong.")
    }

    fun markError(error: Throwable): UploadSnapshot {
        val failed = stateStore.load().copy(
            status = UploadStatus.ERROR,
            errorMessage = when (error) {
                is UserAuthorizationRequiredException -> appContext.getString(R.string.permission_needed)
                is DriveApiException -> when (error.statusCode) {
                    403 -> "Google Drive từ chối thao tác. Có thể tài khoản đã hết dung lượng, chạm giới hạn tải lên, hoặc không còn quyền với tệp."
                    404, 410 -> "Phiên tải lên đã hết hạn. Hãy bấm Thử lại để tạo phiên mới."
                    else -> "Tải lên bị gián đoạn (mã ${error.statusCode}). Bấm Thử lại để tiếp tục từ phần Google Drive đã nhận."
                }
                is IOException -> error.message?.takeIf { it.isNotBlank() }
                    ?: "Mạng bị gián đoạn. Bấm Thử lại để tiếp tục từ phần Google Drive đã nhận."
                else -> "Tải lên bị gián đoạn. Bấm Thử lại để tiếp tục."
            }
        )
        persist(failed)
        return failed
    }

    fun markCanceled(): UploadSnapshot {
        val canceled = stateStore.load().copy(status = UploadStatus.CANCELED, errorMessage = "")
        persist(canceled)
        return canceled
    }

    private fun queryWithTokenRefresh(
        sessionUrl: String,
        initialToken: String,
        totalBytes: Long,
        onTokenChanged: (String) -> Unit
    ): ResumeResult {
        var token = initialToken
        repeat(2) { attempt ->
            try {
                return drive.queryResumableOffset(sessionUrl, token, totalBytes)
            } catch (_: DriveAuthExpiredException) {
                if (attempt == 1) throw UserAuthorizationRequiredException()
                val email = stateStore.load().accountEmail
                token = auth.tokenForAccountBlocking(email)
                onTokenChanged(token)
            }
        }
        throw UserAuthorizationRequiredException()
    }

    private fun completeUpload(snapshot: UploadSnapshot, file: DriveFileItem): UploadSnapshot {
        // Uploading a file creates it privately. The post-upload screen defaults to
        // "Ai có link đều xem được" and applies the user's chosen mode only after confirmation.
        val link = file.webViewLink ?: "https://drive.google.com/file/d/${file.id}/view"
        val completed = snapshot.copy(
            status = UploadStatus.COMPLETED,
            uploadedBytes = snapshot.totalBytes,
            fileId = file.id,
            webViewLink = link,
            folderId = file.parentId ?: snapshot.folderId,
            errorMessage = ""
        )
        persist(completed)
        return completed
    }

    private fun persist(snapshot: UploadSnapshot) {
        stateStore.save(snapshot)
        onStateChanged(snapshot)
    }

    private fun ensureNotCanceled() {
        if (cancelCheck() || stateStore.load().status == UploadStatus.CANCELED) {
            throw CancellationException("Canceled by user")
        }
    }

    private fun Throwable.isRetryableUploadError(): Boolean = when (this) {
        is DriveApiException -> statusCode == 408 || statusCode == 429 || statusCode in 500..599
        is IOException -> true
        else -> false
    }

    companion object {
        private const val CHUNK_BYTES = 8 * 1024 * 1024
        private const val MAX_RETRIES_PER_CHUNK = 5
    }
}

private class UriSourceReader(
    private val context: Context,
    private val uri: Uri
) : Closeable {
    private var pfd: ParcelFileDescriptor? = null
    private var fileInput: FileInputStream? = null
    private var channel: FileChannel? = null
    private var fallbackInput: InputStream? = null
    private var currentOffset = 0L
    private var seekable = true

    init {
        openSeekable()
    }

    fun position(target: Long) {
        if (target == currentOffset) return
        if (seekable) {
            try {
                channel?.position(target) ?: throw IOException("No channel")
                currentOffset = target
                return
            } catch (_: Throwable) {
                seekable = false
                closeSeekable()
            }
        }
        reopenFallbackAt(target)
    }

    fun readFully(buffer: ByteArray, wanted: Int): Int {
        var total = 0
        while (total < wanted) {
            val read = if (seekable) {
                fileInput?.read(buffer, total, wanted - total) ?: -1
            } else {
                fallbackInput?.read(buffer, total, wanted - total) ?: -1
            }
            if (read < 0) break
            if (read == 0) continue
            total += read
            currentOffset += read.toLong()
        }
        return total
    }

    private fun openSeekable() {
        val opened = context.contentResolver.openFileDescriptor(uri, "r")
            ?: throw IOException("Android không thể mở tệp đã chọn.")
        pfd = opened
        fileInput = FileInputStream(opened.fileDescriptor)
        channel = fileInput?.channel
        runCatching { channel?.position(0L) }.onFailure {
            seekable = false
            closeSeekable()
            reopenFallbackAt(0L)
            return
        }
    }

    private fun reopenFallbackAt(target: Long) {
        fallbackInput?.close()
        fallbackInput = context.contentResolver.openInputStream(uri)
            ?: throw IOException("Android không thể mở lại tệp đã chọn.")
        var remaining = target
        val scratch = ByteArray(1024 * 1024)
        while (remaining > 0) {
            val read = fallbackInput?.read(scratch, 0, min(scratch.size.toLong(), remaining).toInt()) ?: -1
            if (read < 0) throw IOException("Không thể tìm tới vị trí cần tiếp tục trong tệp.")
            remaining -= read.toLong()
        }
        currentOffset = target
    }

    private fun closeSeekable() {
        runCatching { channel?.close() }
        runCatching { fileInput?.close() }
        runCatching { pfd?.close() }
        channel = null
        fileInput = null
        pfd = null
    }

    override fun close() {
        closeSeekable()
        runCatching { fallbackInput?.close() }
        fallbackInput = null
    }
}
