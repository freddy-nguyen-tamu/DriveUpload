package com.xong.driveupload

import android.accounts.Account
import android.content.Context
import android.content.Intent
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.auth.api.identity.RevokeAccessRequest
import com.google.android.gms.common.api.Scope
import com.google.android.gms.tasks.Tasks
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class AuthManager(context: Context) {
    private val client = Identity.getAuthorizationClient(context.applicationContext)

    fun beginInteractive(
        onSuccess: (AuthorizationResult) -> Unit,
        onError: (Throwable) -> Unit
    ) {
        val request = AuthorizationRequest.builder()
            .setRequestedScopes(SCOPES)
            .setOptOutIncludingGrantedScopes(true)
            .setPrompt(AuthorizationRequest.Prompt.SELECT_ACCOUNT)
            .build()

        client.authorize(request)
            .addOnSuccessListener(onSuccess)
            .addOnFailureListener(onError)
    }

    fun resultFromIntent(data: Intent): AuthorizationResult =
        client.getAuthorizationResultFromIntent(data)

    suspend fun tokenForAccount(email: String): String = withContext(Dispatchers.IO) {
        val request = AuthorizationRequest.builder()
            .setRequestedScopes(SCOPES)
            .setOptOutIncludingGrantedScopes(true)
            .setAccount(Account(email, GOOGLE_ACCOUNT_TYPE))
            .build()
        val result = Tasks.await(client.authorize(request))
        if (result.hasResolution()) throw UserAuthorizationRequiredException()
        result.accessToken ?: throw UserAuthorizationRequiredException()
    }

    fun tokenForAccountBlocking(email: String): String {
        val request = AuthorizationRequest.builder()
            .setRequestedScopes(SCOPES)
            .setOptOutIncludingGrantedScopes(true)
            .setAccount(Account(email, GOOGLE_ACCOUNT_TYPE))
            .build()
        val result = Tasks.await(client.authorize(request))
        if (result.hasResolution()) throw UserAuthorizationRequiredException()
        return result.accessToken ?: throw UserAuthorizationRequiredException()
    }

    suspend fun revoke(email: String) = withContext(Dispatchers.IO) {
        val request = RevokeAccessRequest.builder()
            .setAccount(Account(email, GOOGLE_ACCOUNT_TYPE))
            .setScopes(SCOPES)
            .build()
        Tasks.await(client.revokeAccess(request))
        Unit
    }

    companion object {
        private const val GOOGLE_ACCOUNT_TYPE = "com.google"
        val SCOPES: List<Scope> = listOf(
            Scope("https://www.googleapis.com/auth/drive.file")
        )
    }
}

class UserAuthorizationRequiredException : Exception("Google authorization requires user interaction")
