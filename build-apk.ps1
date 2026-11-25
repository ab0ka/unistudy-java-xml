$ErrorActionPreference = 'Stop'
Set-Location -LiteralPath (Join-Path $PSScriptRoot 'android')
& .\gradlew.bat :app:assembleDebug :app:testDebugUnitTest --console=plain
if ($LASTEXITCODE -ne 0) { throw 'Android build failed.' }
$output = Join-Path $PSScriptRoot 'output'
New-Item -ItemType Directory -Force $output | Out-Null
Copy-Item -LiteralPath 'app\build\outputs\apk\debug\app-debug.apk' -Destination (Join-Path $output 'UniStudy.apk') -Force
Write-Host "APK: $output\UniStudy.apk"
