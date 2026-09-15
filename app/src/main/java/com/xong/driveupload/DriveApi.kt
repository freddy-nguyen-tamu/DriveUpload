package com.xong.driveupload

import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

class DriveApi {
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS)
        .writeTimeout(0, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private val apiBase = "https://www.googleapis.com/drive/v3".toHttpUrl()
    private val uploadBase = "https://www.googleapis.com/upload/drive/v3".toHttpUrl()
    private val jsonType = "application/json; charset=utf-8".toMediaType()

    fun getAccountAndQuota(token: String): DriveQuota {
        val url = apiBase.newBuilder()
            .addPathSegment("about")
            .addQueryParameter("fields", "user(emailAddress,displayName),storageQuota(limit,usage),maxUploadSize")
            .build()
        val json = executeJson(authorizedGet(url.toString(), token))
        val user = json.optJSONObject("user") ?: JSONObject()
        val quota = json.optJSONObject("storageQuota") ?: JSONObject()
        return DriveQuota(
            email = user.optString("emailAddress", ""),
            displayName = user.optString("displayName").takeIf { it.isNotBlank() },
            limitBytes = quota.stringLongOrNull("limit"),
            usedBytes = quota.stringLongOrNull("usage") ?: 0L,
            maxUploadBytes = json.stringLongOrNull("maxUploadSize")
        )
    }

    fun listAppFiles(token: String): List<DriveFileItem> {
        val result = mutableListOf<DriveFileItem>()
        var pageToken: String? = null
        do {
            val builder = apiBase.newBuilder()
                .addPathSegment("files")
                .addQueryParameter(
                    "q",
                    "trashed=false and appProperties has { key='xongDriveUpload' and value='1' }"
                )
                .addQueryParameter("spaces", "drive")
                .addQueryParameter("pageSize", "1000")
                .addQueryParameter("orderBy", "createdTime desc")
                .addQueryParameter(
                    "fields",
                    "nextPageToken,files(id,name,size,webViewLink,createdTime,parents)"
                )
            if (!pageToken.isNullOrBlank()) builder.addQueryParameter("pageToken", pageToken)
            val json = executeJson(authorizedGet(builder.build().toString(), token))
            val files = json.optJSONArray("files") ?: JSONArray()
            for (i in 0 until files.length()) {
                result += parseDriveFile(files.getJSONObject(i))
            }
            pageToken = json.optString("nextPageToken").takeIf { it.isNotBlank() }
        } while (pageToken != null)
        return result
    }

    fun ensureUploadFolder(token: String): String {
        val queryUrl = apiBase.newBuilder()
            .addPathSegment("files")
            .addQueryParameter(
                "q",
                "mimeType='application/vnd.google-apps.folder' and trashed=false and appProperties has { key='xongDriveFolder' and value='1' }"
            )
            .addQueryParameter("spaces", "drive")
            .addQueryParameter("pageSize", "1")
            .addQueryParameter("fields", "files(id,name)")
            .build()
        val found = executeJson(authorizedGet(queryUrl.toString(), token))
            .optJSONArray("files")
        if (found != null && found.length() > 0) {
            return found.getJSONObject(0).getString("id")
        }

        val body = JSONObject()
            .put("name", "Tệp từ ứng dụng Tải lên Drive")
            .put("mimeType", "application/vnd.google-apps.folder")
            .put("appProperties", JSONObject().put("xongDriveFolder", "1"))
        val createUrl = apiBase.newBuilder()
            .addPathSegment("files")
            .addQueryParameter("fields", "id")
            .build()
        val request = Request.Builder()
            .url(createUrl)
            .header("Authorization", "Bearer $token")
            .post(body.toString().toRequestBody(jsonType))
            .build()
        return executeJson(request).getString("id")
    }


    fun createEmptyFile(
        token: String,
        folderId: String,
        name: String,
        mimeType: String
    ): DriveFileItem {
        val url = uploadBase.newBuilder()
            .addPathSegment("files")
            .addQueryParameter("uploadType", "multipart")
            .addQueryParameter("fields", "id,name,size,webViewLink,createdTime,parents")
            .build()
        val metadata = JSONObject()
            .put("name", name)
            .put("parents", JSONArray().put(folderId))
            .put("appProperties", JSONObject().put("xongDriveUpload", "1"))
        val boundary = "xong-drive-empty-boundary"
        val multipart = buildString {
            append("--").append(boundary).append("\r\n")
            append("Content-Type: application/json; charset=UTF-8\r\n\r\n")
            append(metadata.toString()).append("\r\n")
            append("--").append(boundary).append("\r\n")
            append("Content-Type: ").append(mimeType).append("\r\n\r\n")
            append("\r\n")
            append("--").append(boundary).append("--\r\n")
        }
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $token")
            .post(multipart.toRequestBody("multipart/related; boundary=$boundary".toMediaType()))
            .build()
        return parseDriveFile(executeJson(request))
    }

    fun createResumableSession(
        token: String,
        folderId: String,
        name: String,
        mimeType: String,
        sizeBytes: Long
    ): String {
        val url = uploadBase.newBuilder()
            .addPathSegment("files")
            .addQueryParameter("uploadType", "resumable")
            .addQueryParameter("fields", "id,name,size,webViewLink,createdTime,parents")
            .build()
        val metadata = JSONObject()
            .put("name", name)
            .put("parents", JSONArray().put(folderId))
            .put("appProperties", JSONObject().put("xongDriveUpload", "1"))
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $token")
            .header("X-Upload-Content-Type", mimeType)
            .header("X-Upload-Content-Length", sizeBytes.toString())
            .post(metadata.toString().toRequestBody(jsonType))
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw DriveApiException(response.code, response.body?.string().orEmpty())
            return response.header("Location")
                ?: throw IOException("Google Drive did not return a resumable upload URL")
        }
    }

    fun queryResumableOffset(sessionUrl: String, token: String, totalBytes: Long): ResumeResult {
        val request = Request.Builder()
            .url(sessionUrl)
            .header("Authorization", "Bearer $token")
            .header("Content-Range", "bytes */$totalBytes")
            .put(ByteArray(0).toRequestBody(null))
            .build()
        client.newCall(request).execute().use { response ->
            return when (response.code) {
                200, 201 -> {
                    val body = response.body?.string().orEmpty()
                    val file = if (body.isBlank()) null else parseDriveFile(JSONObject(body))
                    ResumeResult(totalBytes, file, completed = true, expired = false)
                }
                308 -> ResumeResult(parseConfirmedOffset(response.header("Range")), null, completed = false, expired = false)
                404, 410 -> ResumeResult(0L, null, completed = false, expired = true)
                401 -> throw DriveAuthExpiredException()
                else -> throw DriveApiException(response.code, response.body?.string().orEmpty())
            }
        }
    }

    fun uploadChunk(
        sessionUrl: String,
        token: String,
        mimeType: String,
        totalBytes: Long,
        startOffset: Long,
        bytes: ByteArray,
        length: Int
    ): ChunkResult {
        val endOffset = startOffset + length - 1
        val body = bytes.toRequestBody(mimeType.toMediaTypeOrNull(), 0, length)
        val request = Request.Builder()
            .url(sessionUrl)
            .header("Authorization", "Bearer $token")
            .header("Content-Length", length.toString())
            .header("Content-Range", "bytes $startOffset-$endOffset/$totalBytes")
            .put(body)
            .build()
        client.newCall(request).execute().use { response ->
            return when (response.code) {
                200, 201 -> {
                    val json = JSONObject(response.body?.string().orEmpty())
                    ChunkResult(totalBytes, parseDriveFile(json), completed = true)
                }
                308 -> ChunkResult(parseConfirmedOffset(response.header("Range")), null, completed = false)
                401 -> throw DriveAuthExpiredException()
                else -> throw DriveApiException(response.code, response.body?.string().orEmpty())
            }
        }
    }

    fun getFile(token: String, fileId: String): DriveFileItem {
        val url = apiBase.newBuilder()
            .addPathSegment("files")
            .addPathSegment(fileId)
            .addQueryParameter("fields", "id,name,size,webViewLink,createdTime,parents")
            .build()
        return parseDriveFile(executeJson(authorizedGet(url.toString(), token)))
    }

    fun getSharingInfo(token: String, fileId: String): SharingInfo {
        val permissions = listPermissions(token, fileId)
        val anyone = permissions.any { it.optString("type") == "anyone" && it.optString("role") != "owner" }
        val emails = permissions.mapNotNull { permission ->
            val role = permission.optString("role")
            val type = permission.optString("type")
            val email = permission.optString("emailAddress")
            if (role != "owner" && (type == "user" || type == "group") && email.isNotBlank()) email else null
        }.distinct()
        val mode = when {
            anyone -> ShareMode.ANYONE_WITH_LINK
            emails.isNotEmpty() -> ShareMode.SPECIFIC_PEOPLE
            else -> ShareMode.PRIVATE
        }
        return SharingInfo(mode, emails)
    }

    fun applySharing(token: String, fileId: String, mode: ShareMode, emails: List<String>): String {
        clearNonOwnerPermissions(token, fileId)
        when (mode) {
            ShareMode.PRIVATE -> Unit
            ShareMode.ANYONE_WITH_LINK -> createPermission(
                token,
                fileId,
                JSONObject()
                    .put("type", "anyone")
                    .put("role", "reader")
                    .put("allowFileDiscovery", false),
                notify = false
            )
            ShareMode.SPECIFIC_PEOPLE -> {
                try {
                    emails.forEach { email ->
                        createPermission(
                            token,
                            fileId,
                            JSONObject()
                                .put("type", "user")
                                .put("role", "reader")
                                .put("emailAddress", email),
                            notify = true
                        )
                    }
                } catch (error: Throwable) {
                    // Avoid leaving a half-applied recipient list if Drive rejects one address.
                    runCatching { clearNonOwnerPermissions(token, fileId) }
                    throw error
                }
            }
        }
        return getFile(token, fileId).webViewLink
            ?: "https://drive.google.com/file/d/$fileId/view"
    }

    private fun clearNonOwnerPermissions(token: String, fileId: String) {
        val permissions = listPermissions(token, fileId)
        permissions.forEach { permission ->
            if (permission.optString("role") == "owner") return@forEach
            val id = permission.optString("id")
            if (id.isBlank()) return@forEach
            val url = apiBase.newBuilder()
                .addPathSegment("files")
                .addPathSegment(fileId)
                .addPathSegment("permissions")
                .addPathSegment(id)
                .build()
            val request = Request.Builder()
                .url(url)
                .header("Authorization", "Bearer $token")
                .delete()
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw DriveApiException(response.code, response.body?.string().orEmpty())
                }
            }
        }
    }

    private fun listPermissions(token: String, fileId: String): List<JSONObject> {
        val url = apiBase.newBuilder()
            .addPathSegment("files")
            .addPathSegment(fileId)
            .addPathSegment("permissions")
            .addQueryParameter("pageSize", "100")
            .addQueryParameter("fields", "permissions(id,type,role,emailAddress,allowFileDiscovery)")
            .build()
        val json = executeJson(authorizedGet(url.toString(), token))
        val array = json.optJSONArray("permissions") ?: JSONArray()
        return buildList {
            for (i in 0 until array.length()) add(array.getJSONObject(i))
        }
    }

    private fun createPermission(token: String, fileId: String, permission: JSONObject, notify: Boolean) {
        val url = apiBase.newBuilder()
            .addPathSegment("files")
            .addPathSegment(fileId)
            .addPathSegment("permissions")
            .addQueryParameter("sendNotificationEmail", notify.toString())
            .addQueryParameter("fields", "id")
            .build()
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $token")
            .post(permission.toString().toRequestBody(jsonType))
            .build()
        executeJson(request)
    }

    private fun authorizedGet(url: String, token: String): Request = Request.Builder()
        .url(url)
        .header("Authorization", "Bearer $token")
        .get()
        .build()

    private fun executeJson(request: Request): JSONObject {
        client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                if (response.code == 401) throw DriveAuthExpiredException()
                throw DriveApiException(response.code, body)
            }
            return if (body.isBlank()) JSONObject() else JSONObject(body)
        }
    }

    private fun parseDriveFile(json: JSONObject): DriveFileItem {
        val parents = json.optJSONArray("parents")
        return DriveFileItem(
            id = json.optString("id"),
            name = json.optString("name", "tệp"),
            sizeBytes = json.stringLongOrNull("size"),
            webViewLink = json.optString("webViewLink").takeIf { it.isNotBlank() },
            createdTime = json.optString("createdTime").takeIf { it.isNotBlank() },
            parentId = if (parents != null && parents.length() > 0) parents.optString(0).takeIf { it.isNotBlank() } else null
        )
    }

    private fun parseConfirmedOffset(range: String?): Long {
        if (range.isNullOrBlank()) return 0L
        val last = range.substringAfterLast('-').toLongOrNull() ?: return 0L
        return last + 1L
    }

    private fun JSONObject.stringLongOrNull(key: String): Long? {
        val raw = optString(key, "")
        return raw.toLongOrNull()
    }
}

data class ResumeResult(
    val confirmedOffset: Long,
    val file: DriveFileItem?,
    val completed: Boolean,
    val expired: Boolean
)

data class ChunkResult(
    val confirmedOffset: Long,
    val file: DriveFileItem?,
    val completed: Boolean
)

class DriveApiException(val statusCode: Int, val responseBody: String) :
    IOException("Google Drive API error $statusCode: $responseBody")

class DriveAuthExpiredException : IOException("Google Drive authorization expired")
