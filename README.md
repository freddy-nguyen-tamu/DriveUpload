# Drive Upload — Android source (Vietnamese UI)

**Package/application ID:** `com.xong.driveupload`

This project is a native Kotlin Android app that uploads local Android files directly to the Google Drive account selected by the user. It has no server and does not embed a Google client secret.

## User flow

1. Open the app and tap **Đăng nhập bằng Google**.
2. Google shows the account chooser, so the user can choose the Gmail/Google account they want.
3. The app remembers the selected account locally. Google Play services remembers the OAuth grant; the app does **not** store the Google password or persist access tokens.
4. The user can tap **Đăng xuất** at any time. Logout revokes this app's `drive.file` grant for the selected account and clears the locally remembered email.
5. Tap **Chọn tệp để tải lên** and choose any file exposed through Android's Storage Access Framework.
6. Before starting, the app determines the file size and calls Drive `about.get` to check the account's current storage quota and Drive's reported maximum upload size.
7. If there is not enough Google storage, the upload is refused before it begins and the app explains how large the file is versus how much space remains.
8. Uploading uses Google Drive's **resumable upload** protocol in constant-memory 8 MiB chunks. The app never loads the entire file into RAM.
9. Upload state, the Drive resumable session URL, and the last Drive-confirmed byte offset are persisted. If connectivity breaks, **Thử lại** resumes from the portion Drive already accepted whenever the Drive session is still valid.
10. After upload, the screen shows **Xong**, `File nằm ở [email]`, and the three sharing choices:
    - **Một mình tôi**
    - **Ai có link đều xem được** — selected by default for a new upload
    - **Chỉ người được chọn mới xem được** — enter one or more email addresses in the app
11. The new Drive file remains private until the user confirms the sharing screen. After **Lưu chế độ chia sẻ** succeeds, the app renders `Link chia sẻ: [link]` plus the copy button. This avoids claiming a permission was applied before Drive confirms it.
12. When the app is idle, the home screen lists all non-trashed files created by this app that are still accessible in the selected account. Tapping a file opens the containing Google Drive folder; the share button lets the user modify that file's sharing settings again.

## Large-file behavior

The app is designed so Android RAM usage does not grow with file size. It uses an 8 MiB upload buffer and Drive resumable sessions.

There are still external limits that no Android app can bypass:

- the selected Google account must have enough available Google storage;
- Drive's current `maxUploadSize` for the account/service must allow the file;
- the Android document provider must continue giving read access to the selected file;
- Google can enforce service-side upload quotas/rate limits.

If a document provider does not report file size, this app performs one streaming pass to count the bytes before upload. That keeps memory use constant but can take time for a very large file.

## Background transfer strategy

- **Android 14+ (API 34+)**: user-initiated data-transfer `JobScheduler` job with a required network and system notification.
- **Android 13 and lower**: foreground `dataSync` service.

The Android 14+ path intentionally does not fall back to a long `dataSync` foreground service, because Android 15+ imposes a six-hour background budget on that foreground-service type. The resumable Drive state is kept so the user can retry safely.

## Google Cloud setup

Use the Google Cloud project where you already created the Android OAuth client.

1. Enable **Google Drive API**.
2. Configure Google Auth Platform / consent screen and audience.
3. During testing, add the Google accounts you want to use as test users when required by the console.
4. Add/request this OAuth scope:

   ```text
   https://www.googleapis.com/auth/drive.file
   ```

5. OAuth client type must be **Android**.
6. Package name must be exactly:

   ```text
   com.xong.driveupload
   ```

7. The OAuth Android client's SHA-1 must come from the **same release keystore** used by `build-apk.ps1`.

There is no Web OAuth client ID and no OAuth client secret in this project.

## Build a signed APK on Windows — no Android Studio

The included `build-apk.ps1` is the supported Windows build path. It:

- checks for JDK 17+ and can install Microsoft OpenJDK 17 with `winget` when Java is missing;
- prints the keystore SHA-1 used by the APK;
- optionally checks that SHA-1 against the value you registered in Google Cloud;
- downloads Google's official Android command-line tools;
- verifies the command-line-tools ZIP with Google's published SHA-256;
- installs SDK Platform 35, Build Tools 35.0.0 and `adb`;
- downloads Gradle 8.11.1 and verifies its official SHA-256;
- builds a release APK signed by your keystore;
- verifies the final APK signature with `apksigner`;
- creates an easy-to-find copy at `DriveUpload-release.apk`.

Open PowerShell in this extracted project folder and run. If your key password is the same as the keystore password, press Enter at the key-password prompt:

```powershell
powershell -ExecutionPolicy Bypass -File .\build-apk.ps1 `
  -KeystorePath "C:\path\to\drive-upload-release.jks" `
  -KeyAlias "driveupload"
```

Optional SHA-1 guard:

```powershell
powershell -ExecutionPolicy Bypass -File .\build-apk.ps1 `
  -KeystorePath "C:\path\to\drive-upload-release.jks" `
  -KeyAlias "driveupload" `
  -ExpectedSha1 "AA:BB:CC:DD:EE:FF:..."
```

Optional direct USB install after build:

```powershell
powershell -ExecutionPolicy Bypass -File .\build-apk.ps1 `
  -KeystorePath "C:\path\to\drive-upload-release.jks" `
  -KeyAlias "driveupload" `
  -Install
```

Output:

```text
DriveUpload-release.apk
```

See `BUILD-WINDOWS.txt` for the short version.

## Custom launcher icon

The source includes a custom minimalist Drive-inspired upload icon. It is embedded as:

- legacy launcher PNGs for mdpi / hdpi / xhdpi / xxhdpi / xxxhdpi;
- round launcher PNGs;
- Android adaptive-icon foreground/background resources;
- Android 13+ monochrome/themed icon resource;
- a dedicated upload notification icon.

`AndroidManifest.xml` explicitly points to `@mipmap/ic_launcher` and `@mipmap/ic_launcher_round`, so the app does not fall back to the generic Android launcher icon.

The high-resolution icon master is included at:

```text
design/launcher-icon-master.png
```

## Main source files

```text
app/src/main/java/com/xong/driveupload/
  AuthManager.kt          Google Identity authorization, silent token refresh, logout/revoke
  DriveApi.kt             Drive REST API, quota, history, permissions, resumable upload calls
  HistoryAdapter.kt       Idle/history list
  MainActivity.kt         Vietnamese screens and user flow
  Models.kt               Data models
  SessionStore.kt         Remembers selected account email only
  UploadEngine.kt         Constant-memory resumable upload engine
  UploadJobService.kt     Android 14+ user-initiated data-transfer job
  UploadService.kt        Android 13-and-lower foreground-service fallback
  UploadStateStore.kt     Persistent resumable upload state
  Utils.kt                File metadata/size, formatting, email parsing
```

## Dependencies / build versions

- Android Gradle Plugin `8.9.2`
- Gradle `8.11.1`
- compile/target SDK `35`
- minimum SDK `26`
- Java/Kotlin bytecode target `17`
- Google Play services Auth `21.6.0`
- OkHttp `4.12.0`

