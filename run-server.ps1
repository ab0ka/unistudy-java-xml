param([string]$BindAddress = '127.0.0.1', [int]$Port = 8000)
$ErrorActionPreference = 'Stop'
Set-Location -LiteralPath $PSScriptRoot
$envFile = Join-Path $PSScriptRoot '.env'
if (Test-Path -LiteralPath $envFile) {
    foreach ($line in Get-Content -LiteralPath $envFile) {
        if ($line -match '^\s*(UNISTUDY_DB|UNISTUDY_STORAGE|SERVER_PORT)\s*=\s*(.*?)\s*$') {
            if (-not [Environment]::GetEnvironmentVariable($Matches[1], 'Process')) {
                [Environment]::SetEnvironmentVariable($Matches[1], $Matches[2].Trim('"').Trim("'"), 'Process')
            }
        }
    }
}
if (-not $PSBoundParameters.ContainsKey('Port') -and $env:SERVER_PORT) { $Port = [int]$env:SERVER_PORT }
if (-not $env:UNISTUDY_DB) { $env:UNISTUDY_DB = Join-Path $PSScriptRoot 'private\unistudy.db' }
if (-not $env:UNISTUDY_STORAGE) { $env:UNISTUDY_STORAGE = Join-Path $PSScriptRoot 'storage\files' }
if (-not (Test-Path -LiteralPath $env:UNISTUDY_DB)) { throw 'Database missing. Run init-demo.ps1 first (creates synthetic demo accounts only).' }
$javaExecutable = if ($env:JAVA_HOME -and (Test-Path -LiteralPath (Join-Path $env:JAVA_HOME 'bin\java.exe'))) { Join-Path $env:JAVA_HOME 'bin\java.exe' }
else { (Get-Command java -ErrorAction Stop).Source }
$serverJar = Join-Path $PSScriptRoot 'output\UniStudy-server.jar'
if (-not (Test-Path -LiteralPath $serverJar)) { $serverJar = Join-Path $PSScriptRoot 'server\build\libs\unistudy-server.jar' }
if (-not (Test-Path -LiteralPath $serverJar)) { throw 'Server JAR missing. Run build-server.ps1 first.' }
Write-Host "UniStudy Java server: http://localhost:$Port"
Write-Host 'Emulator URL: http://10.0.2.2:8000 (adjust if using another port)'
Get-NetIPAddress -AddressFamily IPv4 -ErrorAction SilentlyContinue |
    Where-Object { $_.IPAddress -notmatch '^127\.|^169\.' -and $_.InterfaceAlias -notmatch 'vEthernet|WSL' } |
    ForEach-Object { Write-Host "Phone URL: http://$($_.IPAddress):$Port" }
Write-Host 'Keep this window open. Ctrl+C stops the server.'
& $javaExecutable '-Xmx384m' '-jar' $serverJar "--server.address=$BindAddress" "--server.port=$Port"
if ($LASTEXITCODE -ne 0) { throw 'Server stopped with an error. Check the log above; port 8000 may already be in use.' }
