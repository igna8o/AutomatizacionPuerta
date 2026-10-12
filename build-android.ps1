$ErrorActionPreference = 'Stop'
$taskTools = Join-Path $PSScriptRoot '.tools'
$taskJdk = Get-ChildItem -LiteralPath (Join-Path $taskTools 'jdk17') -Directory | Select-Object -First 1
if (!$taskJdk) { throw 'Primero ejecutá prepare-android.ps1.' }
$env:JAVA_HOME = $taskJdk.FullName
$env:GRADLE_USER_HOME = Join-Path $taskTools 'gradle-cache'
$env:ANDROID_USER_HOME = Join-Path $taskTools 'android-user'
$env:ANDROID_HOME = Join-Path $taskTools 'android-sdk'
$taskGradle = Join-Path $taskTools 'gradle\gradle-8.2.1\bin\gradle.bat'
& $taskGradle -p (Join-Path $PSScriptRoot 'android') ':app:assembleDebug' ':app:lintDebug' '--no-daemon'
if ($LASTEXITCODE -ne 0) { throw 'Falló la compilación o lint.' }
