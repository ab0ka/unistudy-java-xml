$ErrorActionPreference = 'Stop'
Set-Location -LiteralPath (Join-Path $PSScriptRoot 'server')
& .\gradlew.bat test bootJar --no-daemon --console=plain
if ($LASTEXITCODE -ne 0) { throw 'Java server build failed.' }
$target = Join-Path $PSScriptRoot 'output'
New-Item -ItemType Directory -Force -Path $target | Out-Null
Copy-Item -LiteralPath 'build\libs\unistudy-server.jar' -Destination (Join-Path $target 'UniStudy-server.jar') -Force
Write-Host 'Server ready. Start run-server.cmd in the project root.'
