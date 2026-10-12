param([switch]$AcceptAndroidLicense)
$ErrorActionPreference = 'Stop'
$taskRoot = $PSScriptRoot
$taskTools = Join-Path $taskRoot '.tools'
New-Item -ItemType Directory -Path $taskTools -Force | Out-Null

function Get-Archive($Uri, $ArchivePath, $Destination) {
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $validArchive = $false
    try {
        $archive = [IO.Compression.ZipFile]::OpenRead($ArchivePath)
        $validArchive = $archive.Entries.Count -gt 0
        $archive.Dispose()
    } catch { $validArchive = $false }
    if (!$validArchive) {
        $downloadPath = $ArchivePath + '.download'
        & curl.exe --fail --location --connect-timeout 20 --max-time 240 --retry 2 --output $downloadPath $Uri
        if ($LASTEXITCODE -ne 0) { throw "No se pudo descargar $Uri" }
        Move-Item -LiteralPath $downloadPath -Destination $ArchivePath -Force
    }
    $readyMarker = Join-Path $Destination '.extracted'
    if (!(Test-Path -LiteralPath $readyMarker)) {
        New-Item -ItemType Directory -Path $Destination -Force | Out-Null
        Expand-Archive -LiteralPath $ArchivePath -DestinationPath $Destination -Force
        Set-Content -LiteralPath $readyMarker -Value 'ready'
    }
}

Get-Archive 'https://aka.ms/download-jdk/microsoft-jdk-17-windows-x64.zip' (Join-Path $taskTools 'jdk17.zip') (Join-Path $taskTools 'jdk17')
Get-Archive 'https://services.gradle.org/distributions/gradle-8.2.1-bin.zip' (Join-Path $taskTools 'gradle.zip') (Join-Path $taskTools 'gradle')
Get-Archive 'https://dl.google.com/android/repository/commandlinetools-win-15859902_latest.zip' (Join-Path $taskTools 'android-tools.zip') (Join-Path $taskTools 'android-tools')

$env:JAVA_HOME = (Get-ChildItem -LiteralPath (Join-Path $taskTools 'jdk17') -Directory | Select-Object -First 1).FullName
$env:PATH = (Join-Path $env:JAVA_HOME 'bin') + ';' + $env:PATH
$env:GRADLE_USER_HOME = Join-Path $taskTools 'gradle-cache'
$env:ANDROID_USER_HOME = Join-Path $taskTools 'android-user'
$env:ANDROID_HOME = Join-Path $taskTools 'android-sdk'
$taskDebugKey = Join-Path $taskTools 'debug.keystore'
if (!(Test-Path -LiteralPath $taskDebugKey)) {
    & (Join-Path $env:JAVA_HOME 'bin\keytool.exe') -genkeypair -keystore $taskDebugKey -storepass android -keypass android -alias androiddebugkey -dname 'CN=Android Debug,O=Android,C=US' -keyalg RSA -keysize 2048 -validity 10000 -storetype JKS
    if ($LASTEXITCODE -ne 0) { throw 'No se pudo generar la firma de desarrollo.' }
}
$sdkManager = Join-Path $taskTools 'android-tools\cmdline-tools\bin\sdkmanager.bat'
# Only review licenses for the packages needed by this app.
if ($AcceptAndroidLicense) {
    1..10 | ForEach-Object { 'y' } | & $sdkManager "--sdk_root=$env:ANDROID_HOME" 'platforms;android-34' 'build-tools;34.0.0' 'platform-tools'
} else {
    & $sdkManager "--sdk_root=$env:ANDROID_HOME" 'platforms;android-34' 'build-tools;34.0.0' 'platform-tools'
}
if ($LASTEXITCODE -ne 0) { throw 'No se pudo instalar el SDK.' }
$gradle = Join-Path $taskTools 'gradle\gradle-8.2.1\bin\gradle.bat'
& $gradle -p (Join-Path $taskRoot 'android') wrapper '--gradle-version' '8.2.1'
if ($LASTEXITCODE -ne 0) { throw 'No se pudo configurar Gradle.' }
& $gradle -p (Join-Path $taskRoot 'android') ':app:assembleDebug' ':app:lintDebug' '--no-daemon'
if ($LASTEXITCODE -ne 0) { throw 'Falló la compilación o lint.' }
