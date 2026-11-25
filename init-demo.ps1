param([string]$DatabasePath = (Join-Path $PSScriptRoot 'private/unistudy.db'))
$ErrorActionPreference = 'Stop'
$DatabasePath = [System.IO.Path]::GetFullPath($DatabasePath)
if (Test-Path -LiteralPath $DatabasePath) { throw 'Refusing to overwrite an existing database. Choose a new path.' }
Push-Location -LiteralPath (Join-Path $PSScriptRoot 'server')
try {
    & .\gradlew.bat demoInit "-PdemoDb=$DatabasePath" --no-daemon --console=plain
    if ($LASTEXITCODE -ne 0) { throw 'Demo initialization failed. An incomplete new file may remain; inspect it before removing it.' }
} finally { Pop-Location }
