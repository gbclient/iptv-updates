# IPTV Player - Build APK senza Android Studio
# Questo script scarica JDK 17, Android SDK e Gradle, poi compila l'APK.

param(
    [switch]$SkipDownload,
    [switch]$Clean
)

$ErrorActionPreference = "Stop"
$ProjectRoot = $PSScriptRoot
$BuildDir = Join-Path $ProjectRoot "build_tools"
New-Item -ItemType Directory -Path $BuildDir -Force | Out-Null

$JavaUrl = "https://corretto.aws/downloads/latest/amazon-corretto-17-x64-windows-jdk.zip"
$SdkUrl = "https://dl.google.com/android/repository/commandlinetools-win-11076708_latest.zip"
$GradleUrl = "https://services.gradle.org/distributions/gradle-8.7-bin.zip"

$JavaDir = Join-Path $BuildDir "jdk17"
$SdkDir = Join-Path $BuildDir "android_sdk"
$GradleDir = Join-Path $BuildDir "gradle"

# ── Colori per output ──
function Write-Step { Write-Host "`n>>> $args" -ForegroundColor Cyan }
function Write-OK { Write-Host "    OK: $args" -ForegroundColor Green }
function Write-Warn { Write-Host "    WARN: $args" -ForegroundColor Yellow }
function Write-Err { Write-Host "    ERR: $args" -ForegroundColor Red }

# ── Download file ──
function Download-File {
    param($Url, $OutFile, $Description)
    if (Test-Path $OutFile) {
        Write-OK "$Description gia' presente, skip"
        return
    }
    Write-Step "Scaricando $Description..."
    Write-Host "    URL: $Url"
    Write-Host "    Dest: $OutFile"
    try {
        $ProgressPreference = 'SilentlyContinue'
        Invoke-WebRequest -Uri $Url -OutFile $OutFile -UseBasicParsing
        Write-OK "Download completato: $Description"
    } catch {
        Write-Err "Download fallito: $_"
        Write-Host "    Prova con curl..."
        & curl.exe -L -o $OutFile $Url
        if ($LASTEXITCODE -ne 0) {
            throw "Impossibile scaricare $Description"
        }
        Write-OK "Download completato (curl): $Description"
    } finally {
        $ProgressPreference = 'Continue'
    }
}

# ── Estrai zip con progresso ──
function Expand-Zip {
    param($ZipFile, $DestDir, $Description)
    if (Test-Path $DestDir) {
        $existing = Get-ChildItem $DestDir -ErrorAction SilentlyContinue
        if ($existing.Count -gt 0) {
            Write-OK "$Description gia' estratto, skip"
            return
        }
    }
    New-Item -ItemType Directory -Path $DestDir -Force | Out-Null
    Write-Step "Estraendo $Description..."
    Expand-Archive -Path $ZipFile -DestinationPath $DestDir -Force
    Write-OK "Estratto: $Description"
}

# ═══════════════════════════════════════════════
# 1. SETUP JDK 17
# ═══════════════════════════════════════════════
Write-Step "═════ STEP 1: JDK 17 ═════"

$javaZip = Join-Path $BuildDir "jdk17.zip"
Download-File -Url $JavaUrl -OutFile $javaZip -Description "Amazon Corretto JDK 17"

$javaExtractDir = Join-Path $BuildDir "jdk17_extracted"
Expand-Zip -ZipFile $javaZip -DestDir $javaExtractDir -Description "JDK 17"

# Trova la cartella effettiva (es. jdk17.0.12_7)
$jdkHome = Get-ChildItem $javaExtractDir -Directory | Select-Object -First 1
if (-not $jdkHome) {
    $jdkHome = $javaExtractDir
} else {
    $jdkHome = $jdkHome.FullName
}
Write-OK "JAVA_HOME = $jdkHome"

# ═══════════════════════════════════════════════
# 2. SETUP Android SDK
# ═══════════════════════════════════════════════
Write-Step "═════ STEP 2: Android SDK ═════"

$sdkZip = Join-Path $BuildDir "cmdline-tools.zip"
Download-File -Url $SdkUrl -OutFile $sdkZip -Description "Android Command-line Tools"

$cmdlineDir = Join-Path $SdkDir "cmdline-tools\latest"
if (-not (Test-Path (Join-Path $cmdlineDir "bin\sdkmanager.bat"))) {
    $tempSdk = Join-Path $BuildDir "sdk_temp"
    Expand-Zip -ZipFile $sdkZip -DestDir $tempSdk -Description "Android SDK cmdline-tools"
    New-Item -ItemType Directory -Path $cmdlineDir -Force | Out-Null
    
    # Lo zip contiene una cartella "cmdline-tools" - copia il suo contenuto
    $innerDir = Join-Path $tempSdk "cmdline-tools"
    if (Test-Path $innerDir) {
        Copy-Item -Path "$innerDir\*" -Destination $cmdlineDir -Recurse -Force
    } else {
        Copy-Item -Path "$tempSdk\*" -Destination $cmdlineDir -Recurse -Force
    }
    Remove-Item $tempSdk -Recurse -Force -ErrorAction SilentlyContinue
}

$env:ANDROID_SDK_ROOT = $SdkDir
$sdkManager = Join-Path $cmdlineDir "bin\sdkmanager.bat"

Write-Step "Installando pacchetti SDK Android..."
$env:JAVA_HOME = $jdkHome
$env:PATH = "$jdkHome\bin;$env:PATH"

# Accetta tutte le licenze PRIMA di installare
Write-Host "    Accettando licenze SDK..."
$licArgs = "--sdk_root=`"$SdkDir`" --licenses"
& "cmd.exe" /c "echo y | `"$sdkManager`" $licArgs" 2>&1 | Out-Null

$packages = @(
    "platforms;android-35",
    "build-tools;35.0.0",
    "platform-tools",
    "extras;android;m2repository"
)

foreach ($pkg in $packages) {
    Write-Host "    Installando: $pkg"
    $pkgArgs = "--sdk_root=`"$SdkDir`" $pkg"
    & "cmd.exe" /c "echo y | `"$sdkManager`" $pkgArgs" 2>&1 | Out-Null
    if ($LASTEXITCODE -ne 0) {
        Write-Warn "Fallito: $pkg, riprovo con --install..."
        $instArgs = "--sdk_root=`"$SdkDir`" --install $pkg"
        & "cmd.exe" /c "echo y | `"$sdkManager`" $instArgs" 2>&1 | Out-Null
    }
    Write-OK "OK: $pkg"
}

# ═══════════════════════════════════════════════
# 3. SETUP Gradle
# ═══════════════════════════════════════════════
Write-Step "═════ STEP 3: Gradle ═════"

$gradleZip = Join-Path $BuildDir "gradle-8.7-bin.zip"
Download-File -Url $GradleUrl -OutFile $gradleZip -Description "Gradle 8.7"

$gradleHome = Join-Path $GradleDir "gradle-8.7"
if (-not (Test-Path (Join-Path $gradleHome "bin\gradle.bat"))) {
    Expand-Zip -ZipFile $gradleZip -DestDir $GradleDir -Description "Gradle 8.7"
}
Write-OK "GRADLE_HOME = $gradleHome"

# ═══════════════════════════════════════════════
# 4. Genera Gradle Wrapper
# ═══════════════════════════════════════════════
Write-Step "═════ STEP 4: Generazione Gradle Wrapper ═════"

Push-Location $ProjectRoot
try {
    $env:JAVA_HOME = $jdkHome
    $env:PATH = "$jdkHome\bin;$env:PATH"
    $env:ANDROID_SDK_ROOT = $SdkDir
    $env:ANDROID_HOME = $SdkDir

    $gradleCmd = Join-Path $gradleHome "bin\gradle.bat"
    
    Write-Host "    Eseguendo: gradle wrapper --gradle-version 8.7"
    & $gradleCmd wrapper --gradle-version 8.7
    Write-OK "Gradle wrapper generato"
} finally {
    Pop-Location
}

# ═══════════════════════════════════════════════
# 5. BUILD APK
# ═══════════════════════════════════════════════
Write-Step "═════ STEP 5: Compilazione APK ═════"

Push-Location $ProjectRoot
try {
    $env:JAVA_HOME = $jdkHome
    $env:PATH = "$jdkHome\bin;$env:PATH"
    $env:ANDROID_SDK_ROOT = $SdkDir
    $env:ANDROID_HOME = $SdkDir

    $gradlew = Join-Path $ProjectRoot "gradlew.bat"

    Write-Host "    Compilando APK (assembleRelease)..."
    & $gradlew assembleRelease --no-daemon --stacktrace

    if ($LASTEXITCODE -eq 0) {
        Write-Host ""
        Write-Host "====================================" -ForegroundColor Green
        Write-Host "  APK COMPILATO CON SUCCESSO!" -ForegroundColor Green
        Write-Host "====================================" -ForegroundColor Green
        Write-Host ""
        
        $apkPath = Join-Path $ProjectRoot "app\build\outputs\apk\release\app-release-unsigned.apk"
        if (Test-Path $apkPath) {
            Write-Host "  APK: $apkPath" -ForegroundColor Yellow
        }
        
        $debugApk = Join-Path $ProjectRoot "app\build\outputs\apk\debug\app-debug.apk"
        if (Test-Path $debugApk) {
            Write-Host "  APK Debug: $debugApk" -ForegroundColor Yellow
        }
        
        Write-Host ""
        Write-Host "  INSTALLAZIONE SU FIRESTICK:" -ForegroundColor Cyan
        Write-Host "  1. Su Firestick: Impostazioni > My Fire TV > Opzioni sviluppatore"
        Write-Host "     Attiva 'Debug ADB' e 'App da origini sconosciute'"
        Write-Host "  2. Trova IP del Firestick (Impostazioni > My Fire TV > Informazioni > Rete)"
        Write-Host "  3. Esegui: adb connect IP_DEL_FIRESTICK"
        Write-Host "  4. Esegui: adb install app\build\outputs\apk\debug\app-debug.apk"
        Write-Host ""
    } else {
        Write-Err "Compilazione fallita! Controlla gli errori sopra."
        Write-Host "    Prova a rieseguire con: .\gradlew.bat assembleDebug --stacktrace"
    }
} finally {
    Pop-Location
}

Write-Step "Fatto!"
