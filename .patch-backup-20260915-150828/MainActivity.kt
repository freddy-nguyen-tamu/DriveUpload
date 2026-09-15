package com.xong.driveupload

import android.Manifest
import android.app.Activity
import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageButton
import android.widget.ProgressBar
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {
    private lateinit var session: SessionStore
    private lateinit var uploadStore: UploadStateStore
    private lateinit var auth: AuthManager
    private val drive = DriveApi()
    private lateinit var content: android.widget.FrameLayout
    private lateinit var toolbar: MaterialToolbar
    private var screen = Screen.NONE
    private var receiverRegistered = false

    private val authResolutionLauncher = registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            runCatching { auth.resultFromIntent(result.data!!) }
                .onSuccess(::finishInteractiveLogin)
                .onFailure { showLoginError() }
        } else {
            showLoginError()
        }
    }

    private val filePicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) handlePickedFile(uri)
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* Upload works even if the user chooses not to show normal notifications. */ }

    private val uploadReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            handleUploadStateChanged()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        session = SessionStore(this)
        uploadStore = UploadStateStore(this)
        auth = AuthManager(this)
        content = findViewById(R.id.content)
        toolbar = findViewById(R.id.toolbar)
        toolbar.inflateMenu(R.menu.main_menu)
        toolbar.setOnMenuItemClickListener { item ->
            if (item.itemId == R.id.action_logout) {
                requestLogout()
                true
            } else false
        }
        renderFromStoredState()
    }

    override fun onStart() {
        super.onStart()
        if (!receiverRegistered) {
            ContextCompat.registerReceiver(
                this,
                uploadReceiver,
                IntentFilter(UploadStateStore.ACTION_UPLOAD_STATE_CHANGED),
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
            receiverRegistered = true
        }
    }

    override fun onResume() {
        super.onResume()
        // UIDT jobs must be scheduled while the app is visible. This also resumes a persisted
        // Drive resumable session after Android killed the previous process.
        if (session.accountEmail != null && uploadStore.load().status == UploadStatus.RUNNING) {
            content.post { startUploadTransfer() }
        }
    }

    override fun onStop() {
        if (receiverRegistered) {
            unregisterReceiver(uploadReceiver)
            receiverRegistered = false
        }
        super.onStop()
    }

    private fun renderFromStoredState() {
        val email = session.accountEmail
        if (email.isNullOrBlank()) {
            showLogin()
            return
        }
        val state = uploadStore.load()
        when (state.status) {
            UploadStatus.RUNNING -> showUpload(state)
            UploadStatus.ERROR -> showUpload(state)
            UploadStatus.COMPLETED -> {
                if (state.fileId.isNotBlank()) {
                    showShareScreen(
                        fileId = state.fileId,
                        fileName = state.fileName,
                        accountEmail = state.accountEmail.ifBlank { email },
                        webLink = state.webViewLink,
                        parentId = state.folderId,
                        pendingUpload = true
                    )
                } else {
                    uploadStore.clear()
                    showHome(email)
                }
            }
            UploadStatus.CANCELED -> {
                uploadStore.clear()
                showHome(email)
            }
            UploadStatus.IDLE -> showHome(email)
        }
    }

    private fun showLogin() {
        screen = Screen.LOGIN
        setLogoutVisible(false)
        toolbar.title = getString(R.string.app_name)
        val root = inflate(R.layout.screen_login)
        val signIn: MaterialButton = root.findViewById(R.id.signInButton)
        val progress: ProgressBar = root.findViewById(R.id.loginProgress)
        signIn.setOnClickListener {
            signIn.isEnabled = false
            progress.visibility = View.VISIBLE
            auth.beginInteractive(
                onSuccess = { result ->
                    if (result.hasResolution()) {
                        val request = IntentSenderRequest.Builder(result.pendingIntent!!.intentSender).build()
                        authResolutionLauncher.launch(request)
                    } else {
                        finishInteractiveLogin(result)
                    }
                },
                onError = { showLoginError() }
            )
        }
    }

    private fun finishInteractiveLogin(result: AuthorizationResult) {
        val token = result.accessToken
        if (token.isNullOrBlank()) {
            showLoginError()
            return
        }
        lifecycleScope.launch {
            val outcome = runCatching {
                withContext(Dispatchers.IO) { drive.getAccountAndQuota(token) }
            }
            outcome.onSuccess { quota ->
                if (quota.email.isBlank()) {
                    showLoginError()
                } else {
                    session.accountEmail = quota.email
                    uploadStore.clear()
                    showHome(quota.email)
                }
            }.onFailure { showLoginError() }
        }
    }

    private fun showLoginError() {
        runOnUiThread {
            MaterialAlertDialogBuilder(this)
                .setMessage(R.string.auth_error)
                .setPositiveButton(android.R.string.ok, null)
                .show()
            showLogin()
        }
    }

    private fun showHome(email: String) {
        screen = Screen.HOME
        setLogoutVisible(true)
        toolbar.title = getString(R.string.app_name)
        val root = inflate(R.layout.screen_home)
        root.findViewById<TextView>(R.id.accountEmail).text = email
        val quotaText: TextView = root.findViewById(R.id.quotaText)
        val historyProgress: ProgressBar = root.findViewById(R.id.historyProgress)
        val emptyHistory: TextView = root.findViewById(R.id.emptyHistory)
        val list: RecyclerView = root.findViewById(R.id.historyList)
        val adapter = HistoryAdapter(
            onOpenLocation = { openDriveLocation(it.parentId, it.webViewLink, email) },
            onChangeSharing = {
                showShareScreen(
                    fileId = it.id,
                    fileName = it.name,
                    accountEmail = email,
                    webLink = it.webViewLink.orEmpty(),
                    parentId = it.parentId.orEmpty(),
                    pendingUpload = false
                )
            }
        )
        list.layoutManager = LinearLayoutManager(this)
        list.adapter = adapter

        root.findViewById<MaterialButton>(R.id.chooseFileButton).setOnClickListener {
            filePicker.launch(arrayOf("*/*"))
        }
        root.findViewById<MaterialButton>(R.id.signOutButton).setOnClickListener {
            requestLogout()
        }

        lifecycleScope.launch {
            val outcome = runCatching {
                withContext(Dispatchers.IO) {
                    val token = auth.tokenForAccount(email)
                    Pair(drive.getAccountAndQuota(token), drive.listAppFiles(token))
                }
            }
            if (screen != Screen.HOME) return@launch
            outcome.onSuccess { (quota, files) ->
                quotaText.text = if (quota.limitBytes != null) {
                    "Còn ${Utils.formatBytes(quota.availableBytes ?: 0L)} / ${Utils.formatBytes(quota.limitBytes)}"
                } else {
                    getString(R.string.quota_unlimited)
                }
                historyProgress.visibility = View.GONE
                adapter.submit(files)
                emptyHistory.visibility = if (files.isEmpty()) View.VISIBLE else View.GONE
            }.onFailure { error ->
                historyProgress.visibility = View.GONE
                if (error is UserAuthorizationRequiredException) {
                    session.accountEmail = null
                    showLogin()
                } else {
                    quotaText.text = getString(R.string.network_error)
                    emptyHistory.text = getString(R.string.history_error)
                    emptyHistory.visibility = View.VISIBLE
                }
            }
        }
    }

    private fun handlePickedFile(uri: Uri) {
        runCatching {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val email = session.accountEmail ?: return
        val checking = MaterialAlertDialogBuilder(this)
            .setMessage(R.string.checking_file_and_space)
            .setCancelable(false)
            .create()
        checking.show()

        lifecycleScope.launch {
            val outcome = runCatching {
                withContext(Dispatchers.IO) {
                    val meta = Utils.localFileMeta(contentResolver, uri)
                        ?: throw IllegalStateException("unknown-file-size")
                    val token = auth.tokenForAccount(email)
                    Pair(meta, drive.getAccountAndQuota(token))
                }
            }
            checking.dismiss()
            outcome.onSuccess { (meta, quota) ->
                val accountMax = quota.maxUploadBytes ?: Utils.maxDriveFileBytes()
                val available = quota.availableBytes
                when {
                    meta.sizeBytes > accountMax -> {
                        MaterialAlertDialogBuilder(this@MainActivity)
                            .setTitle(R.string.file_too_large_title)
                            .setMessage(getString(R.string.file_too_large_message, Utils.formatBytes(accountMax)))
                            .setPositiveButton(android.R.string.ok, null)
                            .show()
                    }
                    available != null && meta.sizeBytes > available -> {
                        MaterialAlertDialogBuilder(this@MainActivity)
                            .setTitle(R.string.not_enough_space_title)
                            .setMessage(
                                getString(
                                    R.string.not_enough_space_message,
                                    Utils.formatBytes(meta.sizeBytes),
                                    Utils.formatBytes(available)
                                )
                            )
                            .setPositiveButton(android.R.string.ok, null)
                            .show()
                    }
                    else -> beginUpload(meta, email)
                }
            }.onFailure { error ->
                when (error) {
                    is UserAuthorizationRequiredException -> {
                        session.accountEmail = null
                        showLogin()
                    }
                    is IllegalStateException -> {
                        MaterialAlertDialogBuilder(this@MainActivity)
                            .setTitle(R.string.unknown_size_title)
                            .setMessage(R.string.unknown_size_message)
                            .setPositiveButton(android.R.string.ok, null)
                            .show()
                    }
                    else -> {
                        MaterialAlertDialogBuilder(this@MainActivity)
                            .setMessage(R.string.network_error)
                            .setPositiveButton(android.R.string.ok, null)
                            .show()
                    }
                }
            }
        }
    }

    private fun beginUpload(meta: LocalFileMeta, email: String) {
        uploadStore.save(
            UploadSnapshot(
                status = UploadStatus.RUNNING,
                sourceUri = meta.uri,
                fileName = meta.name,
                mimeType = meta.mimeType,
                totalBytes = meta.sizeBytes,
                uploadedBytes = 0L,
                accountEmail = email
            )
        )
        requestNotificationPermissionIfNeeded()
        showUpload(uploadStore.load())
        startUploadTransfer()
    }

    private fun showUpload(snapshot: UploadSnapshot) {
        screen = Screen.UPLOAD
        setLogoutVisible(true)
        toolbar.title = getString(R.string.uploading)
        val root = inflate(R.layout.screen_upload)
        updateUploadViews(root, snapshot)
        root.findViewById<MaterialButton>(R.id.retryButton).setOnClickListener {
            val retry = uploadStore.load().copy(status = UploadStatus.RUNNING, errorMessage = "")
            uploadStore.save(retry)
            updateUploadViews(root, retry)
            startUploadTransfer()
        }
        root.findViewById<MaterialButton>(R.id.cancelUploadButton).setOnClickListener {
            val current = uploadStore.load()
            if (current.status == UploadStatus.ERROR) {
                uploadStore.clear()
                showHome(session.accountEmail ?: return@setOnClickListener)
            } else {
                it.isEnabled = false
                cancelUploadTransfer()
            }
        }
    }

    private fun updateUploadViews(root: View, snapshot: UploadSnapshot) {
        root.findViewById<TextView>(R.id.uploadFileName).text = snapshot.fileName
        root.findViewById<TextView>(R.id.uploadFileSize).text = Utils.formatBytes(snapshot.totalBytes)
        val percent = if (snapshot.totalBytes > 0L) {
            ((snapshot.uploadedBytes * 100L) / snapshot.totalBytes).toInt().coerceIn(0, 100)
        } else 0
        root.findViewById<LinearProgressIndicator>(R.id.uploadProgress).setProgressCompat(percent, true)
        root.findViewById<TextView>(R.id.uploadPercent).text = "$percent%"
        root.findViewById<TextView>(R.id.uploadBytes).text =
            "${Utils.formatBytes(snapshot.uploadedBytes)} / ${Utils.formatBytes(snapshot.totalBytes)}"
        val errorText: TextView = root.findViewById(R.id.uploadError)
        val retry: MaterialButton = root.findViewById(R.id.retryButton)
        if (snapshot.status == UploadStatus.ERROR) {
            errorText.text = snapshot.errorMessage
            errorText.visibility = View.VISIBLE
            retry.visibility = View.VISIBLE
        } else {
            errorText.visibility = View.GONE
            retry.visibility = View.GONE
        }
    }

    private fun handleUploadStateChanged() {
        val state = uploadStore.load()
        when (state.status) {
            UploadStatus.RUNNING, UploadStatus.ERROR -> {
                if (screen == Screen.UPLOAD) {
                    val root = content.getChildAt(0)
                    if (root != null) updateUploadViews(root, state) else showUpload(state)
                } else {
                    showUpload(state)
                }
            }
            UploadStatus.COMPLETED -> {
                showShareScreen(
                    fileId = state.fileId,
                    fileName = state.fileName,
                    accountEmail = state.accountEmail,
                    webLink = state.webViewLink,
                    parentId = state.folderId,
                    pendingUpload = true
                )
            }
            UploadStatus.CANCELED -> {
                uploadStore.clear()
                session.accountEmail?.let(::showHome) ?: showLogin()
            }
            UploadStatus.IDLE -> Unit
        }
    }

    private fun showShareScreen(
        fileId: String,
        fileName: String,
        accountEmail: String,
        webLink: String,
        parentId: String,
        pendingUpload: Boolean
    ) {
        screen = Screen.SHARE
        setLogoutVisible(true)
        toolbar.title = getString(R.string.share_mode)
        val root = inflate(R.layout.screen_share)
        root.findViewById<TextView>(R.id.shareFileName).text = fileName
        root.findViewById<TextView>(R.id.shareAccountEmail).text = accountEmail
        val group: RadioGroup = root.findViewById(R.id.shareModeGroup)
        val privateRadio: RadioButton = root.findViewById(R.id.privateRadio)
        val anyoneRadio: RadioButton = root.findViewById(R.id.anyoneRadio)
        val specificRadio: RadioButton = root.findViewById(R.id.specificRadio)
        val emailLayout: TextInputLayout = root.findViewById(R.id.emailInputLayout)
        val emailInput: TextInputEditText = root.findViewById(R.id.emailInput)
        val shareProgress: ProgressBar = root.findViewById(R.id.shareProgress)
        val saveButton: MaterialButton = root.findViewById(R.id.saveShareButton)
        val linkSection: View = root.findViewById(R.id.linkSection)
        val linkText: TextView = root.findViewById(R.id.shareLinkText)
        val doneButton: MaterialButton = root.findViewById(R.id.shareDoneButton)
        doneButton.isEnabled = !pendingUpload
        var currentParentId = parentId
        var currentWebLink = webLink

        fun syncEmailVisibility() {
            emailLayout.visibility = if (specificRadio.isChecked) View.VISIBLE else View.GONE
        }
        group.setOnCheckedChangeListener { _, _ -> syncEmailVisibility() }
        syncEmailVisibility()

        lifecycleScope.launch {
            shareProgress.visibility = View.VISIBLE
            saveButton.isEnabled = false
            val outcome = runCatching {
                withContext(Dispatchers.IO) {
                    val token = auth.tokenForAccount(accountEmail)
                    Pair(drive.getFile(token, fileId), drive.getSharingInfo(token, fileId))
                }
            }
            shareProgress.visibility = View.GONE
            saveButton.isEnabled = true
            if (screen != Screen.SHARE) return@launch
            outcome.onSuccess { (file, info) ->
                currentParentId = file.parentId.orEmpty()
                currentWebLink = file.webViewLink.orEmpty()
                if (pendingUpload) {
                    // New uploads start private on Drive. The requested app default is "anyone
                    // with the link", but we do not apply it until the user confirms this screen.
                    anyoneRadio.isChecked = true
                    emailInput.setText("")
                } else {
                    when (info.mode) {
                        ShareMode.PRIVATE -> privateRadio.isChecked = true
                        ShareMode.ANYONE_WITH_LINK -> anyoneRadio.isChecked = true
                        ShareMode.SPECIFIC_PEOPLE -> specificRadio.isChecked = true
                    }
                    emailInput.setText(info.emails.joinToString(", "))
                }
                syncEmailVisibility()
            }.onFailure {
                if (it is UserAuthorizationRequiredException) {
                    session.accountEmail = null
                    showLogin()
                }
            }
        }

        saveButton.setOnClickListener {
            val mode = when (group.checkedRadioButtonId) {
                R.id.privateRadio -> ShareMode.PRIVATE
                R.id.specificRadio -> ShareMode.SPECIFIC_PEOPLE
                else -> ShareMode.ANYONE_WITH_LINK
            }
            val rawEmails = emailInput.text?.toString().orEmpty()
            val emails = if (mode == ShareMode.SPECIFIC_PEOPLE) Utils.parseEmails(rawEmails) else emptyList()
            val invalidEmails = if (mode == ShareMode.SPECIFIC_PEOPLE) Utils.invalidEmails(rawEmails) else emptyList()
            if (mode == ShareMode.SPECIFIC_PEOPLE && invalidEmails.isNotEmpty()) {
                emailLayout.error = getString(R.string.invalid_email_list, invalidEmails.joinToString(", "))
                return@setOnClickListener
            }
            if (mode == ShareMode.SPECIFIC_PEOPLE && emails.isEmpty()) {
                emailLayout.error = getString(R.string.select_at_least_one_email)
                return@setOnClickListener
            }
            emailLayout.error = null
            saveButton.isEnabled = false
            shareProgress.visibility = View.VISIBLE
            lifecycleScope.launch {
                val outcome = runCatching {
                    withContext(Dispatchers.IO) {
                        var token = auth.tokenForAccount(accountEmail)
                        try {
                            drive.applySharing(token, fileId, mode, emails)
                        } catch (_: DriveAuthExpiredException) {
                            token = auth.tokenForAccount(accountEmail)
                            drive.applySharing(token, fileId, mode, emails)
                        }
                    }
                }
                shareProgress.visibility = View.GONE
                saveButton.isEnabled = true
                outcome.onSuccess { link ->
                    currentWebLink = link
                    linkText.text = link
                    linkSection.visibility = View.VISIBLE
                    doneButton.isEnabled = true
                    if (pendingUpload) {
                        val s = uploadStore.load()
                        uploadStore.save(s.copy(webViewLink = link))
                    }
                    Toast.makeText(this@MainActivity, R.string.share_saved, Toast.LENGTH_SHORT).show()
                }.onFailure {
                    MaterialAlertDialogBuilder(this@MainActivity)
                        .setMessage(R.string.sharing_error)
                        .setPositiveButton(android.R.string.ok, null)
                        .show()
                }
            }
        }

        root.findViewById<ImageButton>(R.id.copyLinkButton).setOnClickListener {
            val link = linkText.text?.toString().orEmpty().ifBlank { currentWebLink }
            if (link.isNotBlank()) {
                val clipboard = getSystemService(ClipboardManager::class.java)
                clipboard.setPrimaryClip(ClipData.newPlainText(getString(R.string.share_link), link))
                Toast.makeText(this, R.string.copied, Toast.LENGTH_SHORT).show()
            }
        }

        root.findViewById<MaterialButton>(R.id.openLocationButton).setOnClickListener {
            openDriveLocation(currentParentId.ifBlank { null }, currentWebLink.ifBlank { null }, accountEmail)
        }

        doneButton.setOnClickListener {
            if (pendingUpload) uploadStore.clear()
            showHome(accountEmail)
        }
    }

    private fun openDriveLocation(parentId: String?, webViewLink: String?, accountEmail: String) {
        val url = if (!parentId.isNullOrBlank()) {
            "https://drive.google.com/drive/folders/$parentId"
        } else {
            webViewLink ?: "https://drive.google.com/drive/my-drive"
        }
        // Google web URLs understand authuser; the Drive app may also use the account context
        // when resolving the URL. If the Drive app is unavailable, the browser fallback still
        // has an explicit hint for the account selected in this app.
        val uri = Uri.parse(url).buildUpon()
            .appendQueryParameter("authuser", accountEmail)
            .build()
        val driveIntent = Intent(Intent.ACTION_VIEW, uri).setPackage("com.google.android.apps.docs")
        if (driveIntent.resolveActivity(packageManager) != null) {
            startActivity(driveIntent)
        } else {
            startActivity(Intent(Intent.ACTION_VIEW, uri))
        }
    }

    private fun startUploadTransfer() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val scheduler = getSystemService(JobScheduler::class.java)
            if (scheduler.getPendingJob(UploadJobService.JOB_ID) != null) return
            val state = uploadStore.load()
            val network = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()
            val job = JobInfo.Builder(
                UploadJobService.JOB_ID,
                ComponentName(this, UploadJobService::class.java)
            )
                .setUserInitiated(true)
                .setRequiredNetwork(network)
                .setEstimatedNetworkBytes(0L, state.totalBytes.coerceAtLeast(0L))
                .build()
            if (scheduler.schedule(job) == JobScheduler.RESULT_SUCCESS) return

            // Do not fall back to a dataSync FGS on Android 14+: Android 15 limits that type
            // to six background hours. Keeping the state resumable is safer for truly large files.
            val failed = state.copy(
                status = UploadStatus.ERROR,
                errorMessage = getString(R.string.uidt_schedule_error)
            )
            uploadStore.save(failed)
            sendBroadcast(Intent(UploadStateStore.ACTION_UPLOAD_STATE_CHANGED).setPackage(packageName))
            handleUploadStateChanged()
            return
        }

        // Android 13 and lower: foreground data-sync service fallback.
        val intent = Intent(this, UploadService::class.java).setAction(UploadService.ACTION_START)
        ContextCompat.startForegroundService(this, intent)
    }

    private fun cancelUploadTransfer() {
        val canceled = uploadStore.load().copy(status = UploadStatus.CANCELED, errorMessage = "")
        uploadStore.save(canceled)
        sendBroadcast(Intent(UploadStateStore.ACTION_UPLOAD_STATE_CHANGED).setPackage(packageName))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            getSystemService(JobScheduler::class.java).cancel(UploadJobService.JOB_ID)
        } else {
            startService(Intent(this, UploadService::class.java).setAction(UploadService.ACTION_CANCEL))
        }
    }

    private fun requestLogout() {
        val state = uploadStore.load()
        if (state.status == UploadStatus.RUNNING) {
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.logout_upload_title)
                .setMessage(R.string.logout_upload_message)
                .setNegativeButton(R.string.stay_signed_in, null)
                .setPositiveButton(R.string.yes_logout) { _, _ ->
                    cancelUploadTransfer()
                    logoutNow()
                }
                .show()
        } else {
            logoutNow()
        }
    }

    private fun logoutNow() {
        val email = session.accountEmail
        lifecycleScope.launch {
            if (!email.isNullOrBlank()) runCatching { auth.revoke(email) }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                getSystemService(JobScheduler::class.java).cancel(UploadJobService.JOB_ID)
            }
            session.accountEmail = null
            uploadStore.clear()
            Toast.makeText(this@MainActivity, R.string.signed_out, Toast.LENGTH_SHORT).show()
            showLogin()
        }
    }

    private fun setLogoutVisible(visible: Boolean) {
        toolbar.menu.findItem(R.id.action_logout)?.isVisible = visible
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun inflate(layout: Int): View {
        content.removeAllViews()
        val view = LayoutInflater.from(this).inflate(layout, content, false)
        content.addView(view)
        return view
    }

    private enum class Screen { NONE, LOGIN, HOME, UPLOAD, SHARE }
}
