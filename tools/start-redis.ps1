# start-redis.ps1 - start the local Redis used by fsrs-server.
#
# ASCII only on purpose: a .ps1 file without a UTF-8 BOM is decoded as the system ANSI
# code page on a Chinese Windows install, which turns non-ASCII comments into
# unterminated strings. Keeping this file ASCII makes it impossible to hit that.

[CmdletBinding()]
param(
    [int]$Port = 6379,
    [string]$BindAddress = '127.0.0.1',
    [switch]$Force
)

$ErrorActionPreference = 'Stop'

$toolsDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$exe = Join-Path $toolsDir 'redis-server.exe'
$dataDir = Join-Path $toolsDir 'redis-data'
$logFile = Join-Path $dataDir 'redis.log'
$pidFile = Join-Path $toolsDir 'redis.pid'

function Test-RedisPort {
    param([int]$Port)
    try {
        $client = New-Object System.Net.Sockets.TcpClient
        $client.Connect('127.0.0.1', $Port)
        $client.Close()
        return $true
    } catch {
        return $false
    }
}

if (-not (Test-Path $exe)) {
    throw "redis-server.exe not found at $exe"
}

if (Test-RedisPort -Port $Port) {
    if (-not $Force) {
        Write-Host "Redis is already listening on 127.0.0.1:$Port - nothing to do."
        Write-Host "Use -Force only after stopping it with stop-redis.ps1."
        return
    }
    throw "Port $Port is already in use. Run stop-redis.ps1 first."
}

if (-not (Test-Path $dataDir)) {
    New-Item -ItemType Directory -Path $dataDir | Out-Null
}

# WARNING: a process started with Start-Process from a short-lived PowerShell session can be
# reclaimed when that session ends. When started this way from an interactive shell the
# process tree normally survives, but for a reliable background Redis use one of:
#   * a dedicated terminal that stays open, or
#   * a managed background job (Start-Job / the harness background runner), or
#   * a Windows service wrapper.
# The script verifies the port is actually listening before it reports success, so a
# swallowed process is reported as a failure instead of a silent one.
$arguments = @(
    "--port", $Port,
    "--bind", $BindAddress,
    "--dir", $dataDir,
    "--dbfilename", "dump.rdb",
    "--appendonly", "no",
    "--save", "900 1",
    "--logfile", $logFile
)

Write-Host "Starting Redis: $exe $($arguments -join ' ')"
$process = Start-Process -FilePath $exe -ArgumentList $arguments -WorkingDirectory $dataDir -PassThru -WindowStyle Hidden
$process.Id | Set-Content -Path $pidFile -Encoding ASCII

$deadline = (Get-Date).AddSeconds(15)
while ((Get-Date) -lt $deadline) {
    if (Test-RedisPort -Port $Port) {
        Write-Host "Redis is listening on 127.0.0.1:$Port (PID $($process.Id), data dir $dataDir)."
        return
    }
    if ($process.HasExited) {
        throw "redis-server exited with code $($process.ExitCode). See $logFile"
    }
    Start-Sleep -Milliseconds 250
}

throw "Redis did not start listening on port $Port within 15 seconds. See $logFile"
