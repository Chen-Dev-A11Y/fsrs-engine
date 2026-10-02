# stop-redis.ps1 - stop the local Redis used by fsrs-server.
#
# ASCII only on purpose (see start-redis.ps1): non-ASCII text in a BOM-less .ps1 file is
# decoded as the system ANSI code page on a Chinese Windows install and breaks parsing.

[CmdletBinding()]
param(
    [int]$Port = 6379,
    [switch]$KeepData
)

$ErrorActionPreference = 'Stop'

$toolsDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$pidFile = Join-Path $toolsDir 'redis.pid'
$exe = Join-Path $toolsDir 'redis-server.exe'

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

# Sends one RESP command over a raw TCP socket, so no redis-cli build is required.
function Invoke-RedisCommand {
    param(
        [string]$Command,
        [int]$Port,
        [switch]$KeepOpen
    )
    $client = New-Object System.Net.Sockets.TcpClient
    try {
        $client.Connect('127.0.0.1', $Port)
        $stream = $client.GetStream()
        $payload = "*1`r`n`$$($Command.Length)`r`n$Command`r`n"
        $bytes = [System.Text.Encoding]::ASCII.GetBytes($payload)
        $stream.Write($bytes, 0, $bytes.Length)
        $stream.Flush()
        if ($KeepOpen) {
            return
        }
        $stream.ReadTimeout = 3000
        $buffer = New-Object byte[] 512
        $read = $stream.Read($buffer, 0, $buffer.Length)
        return [System.Text.Encoding]::ASCII.GetString($buffer, 0, $read).Trim()
    } finally {
        $client.Close()
    }
}

if (-not (Test-RedisPort -Port $Port)) {
    Write-Host "Nothing is listening on 127.0.0.1:$Port."
    if (Test-Path $pidFile) { Remove-Item $pidFile -Force }
    return
}

$shutdownCommand = if ($KeepData) { 'SHUTDOWN NOSAVE' } else { 'SHUTDOWN SAVE' }
try {
    Invoke-RedisCommand -Command $shutdownCommand -Port $Port -KeepOpen | Out-Null
    Write-Host "Sent '$shutdownCommand' to 127.0.0.1:$Port."
} catch {
    # Redis closes the connection without replying to SHUTDOWN, so a read error here is
    # expected and is not a failure.
    Write-Host "Sent '$shutdownCommand' to 127.0.0.1:$Port (connection closed by server, as expected)."
}

$deadline = (Get-Date).AddSeconds(10)
while ((Get-Date) -lt $deadline) {
    if (-not (Test-RedisPort -Port $Port)) { break }
    Start-Sleep -Milliseconds 250
}

if (Test-RedisPort -Port $Port) {
    # SHUTDOWN was refused or ignored: fall back to terminating the recorded process.
    if (Test-Path $pidFile) {
        $recordedPid = (Get-Content $pidFile -Raw).Trim()
        if ($recordedPid) {
            Write-Host "Port still open; stopping recorded PID $recordedPid."
            Stop-Process -Id ([int]$recordedPid) -Force -ErrorAction SilentlyContinue
        }
    }
    $owners = Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue |
        Select-Object -ExpandProperty OwningProcess -Unique
    foreach ($owner in $owners) {
        Write-Host "Stopping process $owner which still owns port $Port."
        Stop-Process -Id $owner -Force -ErrorAction SilentlyContinue
    }
}

if (Test-Path $pidFile) { Remove-Item $pidFile -Force }

if (Test-RedisPort -Port $Port) {
    Write-Host "WARNING: something is still listening on port $Port."
    exit 1
}
Write-Host "Redis on port $Port has stopped."
