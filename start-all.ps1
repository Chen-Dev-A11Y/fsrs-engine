<#
.SYNOPSIS
    一键启动 fsrs-engine 本地开发环境（Redis + 后端 + 前端）。

.DESCRIPTION
    依次确认并拉起三个组件，每个组件在独立窗口里运行，这样日志可见、进程也不会随本脚本退出而被回收：

      1. Redis      127.0.0.1:6379   （复用 tools/redis-server.exe，已监听则跳过）
      2. 后端        http://localhost:8080
      3. 前端        http://localhost:5173

    启动完成后脚本会等端口真正就绪再报成功，不会出现"脚本说好了但服务还没起来"的情况。

.PARAMETER MavenSettings
    传给 mvn 的 -s 参数。当默认 Maven 本地仓库不可写时（例如受限沙箱环境）需要指定，
    例如 -MavenSettings .mvn\settings.xml。

.PARAMETER SkipRedis
    跳过 Redis 启动。适用于你已经有自己的 Redis 在 6379 上跑着的情况。

.PARAMETER SkipFrontend
    只启动后端，不启动前端。

.EXAMPLE
    .\start-all.ps1

.EXAMPLE
    .\start-all.ps1 -MavenSettings .mvn\settings.xml -SkipRedis
#>
[CmdletBinding()]
param(
    [string]$MavenSettings = '',
    [switch]$SkipRedis,
    [switch]$SkipFrontend
)

$ErrorActionPreference = 'Stop'
$root = $PSScriptRoot

function Test-Port {
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

function Wait-Port {
    param([int]$Port, [string]$Name, [int]$TimeoutSeconds = 90)
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    while ((Get-Date) -lt $deadline) {
        if (Test-Port -Port $Port) {
            Write-Host "  [OK] $Name 已就绪 (port $Port)" -ForegroundColor Green
            return $true
        }
        Start-Sleep -Milliseconds 750
    }
    Write-Host "  [!!] $Name 在 $TimeoutSeconds 秒内没有监听 $Port" -ForegroundColor Yellow
    return $false
}

function Get-ShellExe {
    $pwsh = Get-Command pwsh -ErrorAction SilentlyContinue
    if ($pwsh) { return $pwsh.Source }
    return (Get-Command powershell).Source
}

Write-Host ''
Write-Host '========================================' -ForegroundColor Cyan
Write-Host '  fsrs-engine  本地开发环境启动' -ForegroundColor Cyan
Write-Host '========================================' -ForegroundColor Cyan
Write-Host ''

# ---------------------------------------------------------------- 前置检查
# Prefer an explicit JDK. On some Windows setups the `java` on PATH is an Oracle
# javapath shim that can fail before printing anything at all.
$javaExe = if ($env:JAVA_HOME -and (Test-Path (Join-Path $env:JAVA_HOME 'bin\java.exe'))) {
    Join-Path $env:JAVA_HOME 'bin\java.exe'
} else {
    $found = Get-Command java -ErrorAction SilentlyContinue
    if ($found) { $found.Source } else { $null }
}
if (-not $javaExe) { throw '找不到 java，请安装 JDK 21（或设置 JAVA_HOME）后重试。' }
Write-Host "  java : $javaExe" -ForegroundColor DarkGray

$mvn = Get-Command mvn -ErrorAction SilentlyContinue
if (-not $mvn) { throw '找不到 mvn，请安装 Maven 并加入 PATH。' }

if (-not $SkipFrontend) {
    if (-not (Get-Command node -ErrorAction SilentlyContinue)) {
        throw '找不到 node，请安装 Node.js 18+ 并加入 PATH。'
    }
}

# ---------------------------------------------------------------- 1. Redis
Write-Host '[1/3] Redis' -ForegroundColor White

if (Test-Port -Port 6379) {
    Write-Host '  [OK] 6379 已在监听，复用现有实例' -ForegroundColor Green
} elseif ($SkipRedis) {
    Write-Host '  [--] 已指定 -SkipRedis，跳过' -ForegroundColor DarkGray
} else {
    $redisExe = Join-Path $root 'tools\redis-server.exe'
    if (-not (Test-Path $redisExe)) {
        Write-Host "  [!!] 找不到 $redisExe" -ForegroundColor Yellow
        Write-Host '       请自行启动 Redis 到 127.0.0.1:6379，或加 -SkipRedis 并另起一个实例。' -ForegroundColor Yellow
    } else {
        $redisData = Join-Path $root 'tools\redis-data'
        New-Item -ItemType Directory -Force -Path $redisData | Out-Null

        # 用独立窗口启动：Start-Process 的子进程会随父进程退出被回收，
        # 独立窗口（-NoExit）则能一直存活。
        $shell = Get-ShellExe
        Start-Process -FilePath $shell -ArgumentList @(
            '-NoExit', '-Command',
            "& '$redisExe' --port 6379 --dir '$redisData'"
        ) | Out-Null

        if (-not (Wait-Port -Port 6379 -Name 'Redis' -TimeoutSeconds 30)) {
            Write-Host '       Redis 未能启动，后端会连不上缓存。' -ForegroundColor Yellow
        }
    }
}

# ---------------------------------------------------------------- 2. 后端
Write-Host ''
Write-Host '[2/3] 后端 (Spring Boot)' -ForegroundColor White

if (Test-Port -Port 8080) {
    Write-Host '  [OK] 8080 已在监听，跳过启动' -ForegroundColor Green
} else {
    $serverPom = Join-Path $root 'fsrs-server\pom.xml'
    if (-not (Test-Path $serverPom)) {
        Write-Host '  [!!] 找不到 fsrs-server 模块，无法启动后端' -ForegroundColor Yellow
    } else {
        # Package first, then run the jar.
        #
        # `spring-boot:run` is the obvious choice for a dev script, but it forks a child
        # JVM and talks to it over pipes, which locked-down environments refuse (EPERM).
        # Running the packaged jar is a direct process, starts in a few seconds, and is
        # the exact path this project is documented and verified with.
        #
        # Build from the AGGREGATOR with -pl/-am, never from fsrs-server/pom.xml on its own.
        # A standalone single-module build has to resolve com.fsrs:fsrs-core from a
        # repository, where it does not exist - fsrs-core is a sibling module, not a
        # published artifact. Without -am that fails with "The POM for com.fsrs:fsrs-core
        # is missing" unless someone happened to `mvn install` it first. -am pulls the
        # needed modules into the same reactor, so nothing has to be pre-installed.
        $reactorPom = Join-Path $root 'pom.xml'
        $mvnArgs = @()
        if ($MavenSettings) { $mvnArgs += @('-s', $MavenSettings) }
        $mvnArgs += @('-f', $reactorPom, '-pl', 'fsrs-server', '-am', 'package', '-DskipTests')

        Write-Host '  [..] 正在打包后端 (mvn package -DskipTests)' -ForegroundColor DarkGray
        & mvn @mvnArgs
        if ($LASTEXITCODE -ne 0) { throw '后端打包失败，请检查上面的 Maven 输出。' }

        $jar = Get-ChildItem (Join-Path $root 'fsrs-server\target') -Filter 'fsrs-server-*.jar' `
            -ErrorAction SilentlyContinue |
            Where-Object { $_.Name -notlike '*.original' } | Select-Object -First 1
        if (-not $jar) { throw '打包完成但找不到可执行 jar。' }

        $shell = Get-ShellExe
        Start-Process -FilePath $shell -ArgumentList @(
            '-NoExit', '-Command', "& '$javaExe' -jar '$($jar.FullName)'"
        ) | Out-Null

        Write-Host '  [..] 首次启动会自动建库建表并写入演示数据，需要一点时间' -ForegroundColor DarkGray
        Wait-Port -Port 8080 -Name '后端' -TimeoutSeconds 180 | Out-Null
    }
}

# ---------------------------------------------------------------- 3. 前端
Write-Host ''
Write-Host '[3/3] 前端 (Vite)' -ForegroundColor White

if ($SkipFrontend) {
    Write-Host '  [--] 已指定 -SkipFrontend，跳过' -ForegroundColor DarkGray
} elseif (Test-Port -Port 5173) {
    Write-Host '  [OK] 5173 已在监听，跳过启动' -ForegroundColor Green
} else {
    $webDir = Join-Path $root 'fsrs-web'
    if (-not (Test-Path $webDir)) {
        Write-Host '  [!!] 找不到 fsrs-web 目录，无法启动前端' -ForegroundColor Yellow
    } else {
        if (-not (Test-Path (Join-Path $webDir 'node_modules'))) {
            Write-Host '  [..] 首次运行，先安装依赖 (npm install)' -ForegroundColor DarkGray
            Push-Location $webDir
            try { npm install } finally { Pop-Location }
        }

        $shell = Get-ShellExe
        Start-Process -FilePath $shell -ArgumentList @(
            '-NoExit', '-Command', "Set-Location '$webDir'; npm run dev"
        ) | Out-Null

        Wait-Port -Port 5173 -Name '前端' -TimeoutSeconds 90 | Out-Null
    }
}

# ---------------------------------------------------------------- 收尾
Write-Host ''
Write-Host '========================================' -ForegroundColor Cyan
Write-Host '  启动完成' -ForegroundColor Cyan
Write-Host '========================================' -ForegroundColor Cyan
Write-Host ''
Write-Host '  前端     http://localhost:5173' -ForegroundColor White
Write-Host '  后端     http://localhost:8080' -ForegroundColor White
Write-Host '  演示账号 demo / demo1234' -ForegroundColor White
Write-Host ''
Write-Host '  停止服务：关闭对应的窗口即可；Redis 可用 tools\stop-redis.ps1。' -ForegroundColor DarkGray
Write-Host ''
