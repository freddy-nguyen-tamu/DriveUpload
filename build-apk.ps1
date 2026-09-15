param(
    [Parameter(Mandatory = $true)]
    [string]$KeystorePath,

    [string]$KeyAlias = "driveupload",

    [string]$ExpectedSha1 = "",

    [switch]$Install
)

$ErrorActionPreference = "Stop"
$ProgressPreference = "SilentlyContinue"
# PowerShell 5.1 can otherwise negotiate older TLS defaults on some Windows installs.
[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
$ProjectRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
Set-Location $ProjectRoot
$ToolsRoot = Join-Path $ProjectRoot ".build-tools"
New-Item -ItemType Directory -Force -Path $ToolsRoot | Out-Null

function Write-Step([string]$Message) {
    Write-Host "`n== $Message ==" -ForegroundColor Cyan
}

function Get-JavaMajorVersion([string]$JavaExe = "java") {
    # `java -version` writes its version banner to STDERR. On Windows PowerShell
    # 5.1, redirecting that STDERR while $ErrorActionPreference is Stop can turn
    # the perfectly successful version check into a terminating NativeCommandError.
    # Java 9+ provides `--version`, which writes normally and is safe here.
    $previousErrorActionPreference = $ErrorActionPreference
    try {
        $ErrorActionPreference = "Continue"
        $output = @(& $JavaExe --version 2>&1)
        $exitCode = $LASTEXITCODE
        if ($exitCode -eq 0) {
            $text = ($output | ForEach-Object { $_.ToString() }) -join "`n"
            if ($text -match '(?m)^(?:openjdk|java)\s+(?<major>\d+)(?:\.|\s)') {
                return [int]$Matches.major
            }
            if ($text -match '(?m)version\s+"(?<major>\d+)') {
                return [int]$Matches.major
            }
        }
    } catch {
        # Return 0 below; callers can try another installed JDK or install the
        # portable JDK. Do not let a version probe terminate the build script.
    } finally {
        $ErrorActionPreference = $previousErrorActionPreference
    }
    return 0
}

function Use-JdkHome([string]$JdkHomePath) {
    if ([string]::IsNullOrWhiteSpace($JdkHomePath)) { return $false }
    $javaExe = Join-Path $JdkHomePath "bin\java.exe"
    $keytoolExe = Join-Path $JdkHomePath "bin\keytool.exe"
    if (-not (Test-Path $javaExe) -or -not (Test-Path $keytoolExe)) { return $false }
    if ((Get-JavaMajorVersion $javaExe) -lt 17) { return $false }

    $env:JAVA_HOME = (Resolve-Path $JdkHomePath).Path
    $env:PATH = "$(Join-Path $env:JAVA_HOME 'bin');$env:PATH"
    return $true
}

function Find-JdkHomes {
    $homes = New-Object System.Collections.Generic.List[string]

    if ($env:JAVA_HOME) { $homes.Add($env:JAVA_HOME) }

    try {
        $javaCmd = Get-Command java -ErrorAction Stop
        if ($javaCmd.Source) {
            $candidate = Split-Path -Parent (Split-Path -Parent $javaCmd.Source)
            $homes.Add($candidate)
        }
    } catch {}

    foreach ($pattern in @(
        (Join-Path $ToolsRoot "jdk17*"),
        "C:\Program Files\Microsoft\jdk-*",
        "C:\Program Files\Eclipse Adoptium\jdk-*",
        "C:\Program Files\Java\jdk-*"
    )) {
        foreach ($item in (Get-Item $pattern -ErrorAction SilentlyContinue)) {
            if ($item -and $item.PSIsContainer) { $homes.Add($item.FullName) }
        }
    }

    return $homes | Select-Object -Unique
}

function Install-PortableJdk17 {
    Write-Step "Downloading a portable JDK 17 (no admin rights and no Android Studio required)"

    # Query Adoptium for the current Windows x64 Temurin 17 JDK.  We use the
    # checksum returned by the same API, avoiding winget's occasionally stale
    # installer hash metadata.
    $apiUrl = "https://api.adoptium.net/v3/assets/latest/17/hotspot?architecture=x64&image_type=jdk&os=windows&vendor=eclipse"
    $downloadUrl = $null
    $expectedHash = $null

    try {
        $assets = Invoke-RestMethod -Uri $apiUrl -Headers @{ "User-Agent" = "DriveUploadBuild/1.1" }
        $asset = @($assets) | Where-Object { $_.binary.package.link } | Select-Object -First 1
        if ($asset) {
            $downloadUrl = [string]$asset.binary.package.link
            $expectedHash = ([string]$asset.binary.package.checksum).ToUpperInvariant()
        }
    } catch {
        Write-Warning "Could not query Adoptium metadata; using its stable latest-JDK endpoint instead."
    }

    if ([string]::IsNullOrWhiteSpace($downloadUrl)) {
        $downloadUrl = "https://api.adoptium.net/v3/binary/latest/17/ga/windows/x64/jdk/hotspot/normal/eclipse?project=jdk"
    }

    $jdkZip = Join-Path $ToolsRoot "temurin-jdk17-windows-x64.zip"
    if (Test-Path $jdkZip) {
        if ($expectedHash) {
            $cachedHash = (Get-FileHash $jdkZip -Algorithm SHA256).Hash.ToUpperInvariant()
            if ($cachedHash -ne $expectedHash) {
                Write-Host "Cached JDK archive does not match the current checksum; downloading it again."
                Remove-Item $jdkZip -Force
            }
        } elseif ((Get-Item $jdkZip).Length -lt 50000000) {
            # A JDK archive should be much larger than this.  This also catches
            # saved HTML/error responses from a failed download.
            Remove-Item $jdkZip -Force
        }
    }

    if (-not (Test-Path $jdkZip)) {
        Write-Host "Downloading Temurin JDK 17..."
        try {
            Invoke-WebRequest -Uri $downloadUrl -OutFile $jdkZip -UseBasicParsing -MaximumRedirection 10
        } catch {
            Remove-Item $jdkZip -Force -ErrorAction SilentlyContinue
            throw "Could not download portable JDK 17.`n$($_.Exception.Message)"
        }
    }

    if ($expectedHash) {
        $actualHash = (Get-FileHash $jdkZip -Algorithm SHA256).Hash.ToUpperInvariant()
        if ($actualHash -ne $expectedHash) {
            Remove-Item $jdkZip -Force -ErrorAction SilentlyContinue
            throw "Portable JDK SHA-256 verification failed. Rerun the script to download a fresh copy."
        }
        Write-Host "Portable JDK SHA-256: verified" -ForegroundColor Green
    }

    $temp = Join-Path $ToolsRoot "jdk17-extract"
    $portableHome = Join-Path $ToolsRoot "jdk17"
    Remove-Item -Recurse -Force $temp -ErrorAction SilentlyContinue
    Remove-Item -Recurse -Force $portableHome -ErrorAction SilentlyContinue
    New-Item -ItemType Directory -Force -Path $temp | Out-Null
    Expand-Archive -Path $jdkZip -DestinationPath $temp -Force

    $rootCandidate = $null
    if (Test-Path (Join-Path $temp "bin\java.exe")) {
        $rootCandidate = Get-Item $temp
    } else {
        $rootCandidate = Get-ChildItem $temp -Directory | Where-Object {
            Test-Path (Join-Path $_.FullName "bin\java.exe")
        } | Select-Object -First 1
    }

    if (-not $rootCandidate) {
        throw "The JDK archive was downloaded, but bin\java.exe was not found after extraction."
    }

    if ($rootCandidate.FullName -eq $temp) {
        New-Item -ItemType Directory -Force -Path $portableHome | Out-Null
        Get-ChildItem $temp -Force | Move-Item -Destination $portableHome
    } else {
        Move-Item $rootCandidate.FullName $portableHome
    }
    Remove-Item -Recurse -Force $temp -ErrorAction SilentlyContinue

    if (-not (Use-JdkHome $portableHome)) {
        $javaExe = Join-Path $portableHome "bin\java.exe"
        $details = ""
        if (Test-Path $javaExe) {
            $previousErrorActionPreference = $ErrorActionPreference
            try {
                $ErrorActionPreference = "Continue"
                $probe = @(& $javaExe --version 2>&1)
                $probeExit = $LASTEXITCODE
                $details = "`njava.exe exit code: $probeExit`n" + (($probe | ForEach-Object { $_.ToString() }) -join "`n")
            } catch {
                $details = "`njava.exe error: $($_.Exception.Message)"
            } finally {
                $ErrorActionPreference = $previousErrorActionPreference
            }
        } else {
            $details = "`nExpected java.exe was not found at: $javaExe"
        }
        throw "Portable JDK 17 was extracted but could not be started.$details"
    }
}

function Ensure-Java17 {
    foreach ($candidateJdkHome in (Find-JdkHomes)) {
        if (Use-JdkHome $candidateJdkHome) {
            Write-Host "Using JDK: $env:JAVA_HOME"
            return
        }
    }

    # If java is on PATH but its home could not be inferred, still accept it
    # when it is new enough and keytool is also available.
    if ((Get-JavaMajorVersion) -ge 17 -and (Get-Command keytool -ErrorAction SilentlyContinue)) {
        Write-Host "Using Java 17+ already available on PATH."
        return
    }

    Install-PortableJdk17
}

function Download-File([string]$Url, [string]$Destination) {
    if (Test-Path $Destination) { return }
    Write-Host "Downloading $Url"
    try {
        Invoke-WebRequest -Uri $Url -OutFile $Destination -UseBasicParsing
    } catch {
        Remove-Item $Destination -Force -ErrorAction SilentlyContinue
        throw "Download failed: $Url`n$($_.Exception.Message)"
    }
}

function Normalize-Sha1([string]$Value) {
    return ($Value -replace '[^0-9A-Fa-f]', '').ToUpperInvariant()
}

Ensure-Java17
Write-Step "Java"
java -version

$resolvedKeystore = (Resolve-Path $KeystorePath).Path
$storeSecure = Read-Host "Keystore password" -AsSecureString
$keySecure = Read-Host "Key password for '$KeyAlias' (often the same password)" -AsSecureString
$storePassword = [System.Net.NetworkCredential]::new("", $storeSecure).Password
$keyPassword = [System.Net.NetworkCredential]::new("", $keySecure).Password
if ([string]::IsNullOrEmpty($keyPassword)) {
    $keyPassword = $storePassword
    Write-Host "Empty key password: using the keystore password for '$KeyAlias'."
}

$keytool = Get-Command keytool -ErrorAction SilentlyContinue
if (-not $keytool) {
    $keytoolPath = Join-Path $env:JAVA_HOME "bin\keytool.exe"
    if (-not (Test-Path $keytoolPath)) { throw "keytool.exe was not found." }
    $keytool = $keytoolPath
} else {
    $keytool = $keytool.Source
}

Write-Step "Release certificate SHA-1"
$keyInfo = & $keytool -list -v -keystore $resolvedKeystore -alias $KeyAlias -storepass $storePassword 2>&1
if ($LASTEXITCODE -ne 0) { throw "Could not read the keystore or alias '$KeyAlias'." }
$shaLine = $keyInfo | Select-String -Pattern 'SHA1:' | Select-Object -First 1
if (-not $shaLine) { throw "Could not find SHA1 in keytool output." }
$actualSha1 = (($shaLine.ToString() -replace '^.*SHA1:\s*', '')).Trim()
Write-Host "Package: com.xong.driveupload"
Write-Host "SHA-1:   $actualSha1" -ForegroundColor Yellow
if ($ExpectedSha1) {
    if ((Normalize-Sha1 $ExpectedSha1) -ne (Normalize-Sha1 $actualSha1)) {
        throw "The keystore SHA-1 does not match -ExpectedSha1. Use the same certificate that you registered in the Google OAuth Android client."
    }
    Write-Host "OAuth SHA-1 check: MATCH" -ForegroundColor Green
}

$SdkRoot = Join-Path $ToolsRoot "android-sdk"
$GradleVersion = "8.11.1"
$GradleRoot = Join-Path $ToolsRoot "gradle-$GradleVersion"

$SdkManager = Join-Path $SdkRoot "cmdline-tools\latest\bin\sdkmanager.bat"
if (-not (Test-Path $SdkManager)) {
    Write-Step "Downloading official Android command-line tools"
    $toolsZip = Join-Path $ToolsRoot "commandlinetools-win-15859902_latest.zip"
    Download-File "https://dl.google.com/android/repository/commandlinetools-win-15859902_latest.zip" $toolsZip

    $expectedToolsHash = "90AE805D20434428BFFCB699C290860F19BB5F66A67E6B330067E3DE801FB04A"
    $actualToolsHash = (Get-FileHash $toolsZip -Algorithm SHA256).Hash.ToUpperInvariant()
    if ($actualToolsHash -ne $expectedToolsHash) {
        throw "Android command-line tools SHA-256 mismatch. Delete $toolsZip and retry."
    }

    $tmp = Join-Path $ToolsRoot "cmdline-temp"
    Remove-Item -Recurse -Force $tmp -ErrorAction SilentlyContinue
    Expand-Archive -Path $toolsZip -DestinationPath $tmp -Force
    New-Item -ItemType Directory -Force -Path (Join-Path $SdkRoot "cmdline-tools") | Out-Null
    Remove-Item -Recurse -Force (Join-Path $SdkRoot "cmdline-tools\latest") -ErrorAction SilentlyContinue
    Move-Item (Join-Path $tmp "cmdline-tools") (Join-Path $SdkRoot "cmdline-tools\latest")
    Remove-Item -Recurse -Force $tmp
}

$env:ANDROID_HOME = $SdkRoot
$env:ANDROID_SDK_ROOT = $SdkRoot

Write-Step "Accepting Android SDK licenses"
$yes = ((1..80 | ForEach-Object { "y" }) -join "`n")
$yes | & $SdkManager "--sdk_root=$SdkRoot" --licenses | Out-Host

Write-Step "Installing Android SDK 35, Build Tools 35.0.0, and adb"
& $SdkManager "--sdk_root=$SdkRoot" "platform-tools" "platforms;android-35" "build-tools;35.0.0"
if ($LASTEXITCODE -ne 0) { throw "sdkmanager failed." }

$GradleBat = Join-Path $GradleRoot "bin\gradle.bat"
if (-not (Test-Path $GradleBat)) {
    Write-Step "Downloading Gradle $GradleVersion"
    $gradleZip = Join-Path $ToolsRoot "gradle-$GradleVersion-bin.zip"
    Download-File "https://services.gradle.org/distributions/gradle-$GradleVersion-bin.zip" $gradleZip
    $expectedGradleHash = "F397B287023ACDBA1E9F6FC5EA72D22DD63669D59ED4A289A29B1A76EEE151C6"
    $actualGradleHash = (Get-FileHash $gradleZip -Algorithm SHA256).Hash.ToUpperInvariant()
    if ($actualGradleHash -ne $expectedGradleHash) {
        throw "Gradle $GradleVersion SHA-256 mismatch. Delete $gradleZip and retry."
    }
    Expand-Archive -Path $gradleZip -DestinationPath $ToolsRoot -Force
}

$env:XONG_KEYSTORE = $resolvedKeystore
$env:XONG_STORE_PASSWORD = $storePassword
$env:XONG_KEY_ALIAS = $KeyAlias
$env:XONG_KEY_PASSWORD = $keyPassword

Write-Step "Building signed release APK"
& $GradleBat --no-daemon clean :app:assembleRelease
if ($LASTEXITCODE -ne 0) { throw "Gradle build failed." }

$apk = Join-Path $ProjectRoot "app\build\outputs\apk\release\app-release.apk"
if (-not (Test-Path $apk)) { throw "Build finished but APK was not found at $apk" }
$easyApk = Join-Path $ProjectRoot "DriveUpload-release.apk"
Copy-Item $apk $easyApk -Force

$apksigner = Join-Path $SdkRoot "build-tools\35.0.0\apksigner.bat"
Write-Step "Verifying APK signature"
& $apksigner verify --verbose --print-certs $apk | Out-Host
if ($LASTEXITCODE -ne 0) { throw "APK signature verification failed." }

Write-Host "`nDONE" -ForegroundColor Green
Write-Host "APK: $easyApk" -ForegroundColor Green
Write-Host "Original Gradle output: $apk"
Write-Host "OAuth package name: com.xong.driveupload"
Write-Host "OAuth certificate SHA-1: $actualSha1"

if ($Install) {
    Write-Step "Installing APK with adb"
    $adb = Join-Path $SdkRoot "platform-tools\adb.exe"
    & $adb install -r $easyApk
    if ($LASTEXITCODE -ne 0) { throw "adb install failed. Enable USB debugging and authorize this PC on the phone." }
}

# Reduce the lifetime of plaintext password variables in this PowerShell process.
$storePassword = $null
$keyPassword = $null
Remove-Item Env:XONG_STORE_PASSWORD -ErrorAction SilentlyContinue
Remove-Item Env:XONG_KEY_PASSWORD -ErrorAction SilentlyContinue
