# e2e-verify.ps1 - end-to-end verification harness for fsrs-server (port 8080).
#
# This is a verification script, not an application script: it is kept next to the server
# module so it survives `mvn clean`. Run it with:
#   powershell -ExecutionPolicy Bypass -File D:\Github\fsrs-engine\tools\e2e-verify.ps1
#
# It must run inside ONE shell session: a java process started with Start-Process from a
# short-lived PowerShell invocation is reclaimed when that invocation ends, so the server
# is started and exercised by this single script.
#
# ASCII only on purpose (a BOM-less .ps1 is decoded as the system ANSI code page on a
# Chinese Windows install, which turns non-ASCII text into parse errors).

[CmdletBinding()]
param(
    # Empties the srs:* keyspace first, so a run starts from a clean quota and served set.
    # Use it when re-running the verification; leave it off to observe the state a previous
    # run left behind.
    [switch]$Reset
)

$ErrorActionPreference = 'Stop'
$base = 'http://127.0.0.1:8080'
$logDir = 'D:\Github\fsrs-engine\fsrs-server\target'
$mysql = 'D:\MySQL\MySQL Server 8.0\bin\mysql.exe'

function Show([string]$title, $value) {
    Write-Host ''
    Write-Host "=== $title ==="
    if ($value -is [string]) { Write-Host $value } else { $value | ConvertTo-Json -Depth 8 -Compress }
}

function Test-Port([int]$Port) {
    try {
        $c = New-Object System.Net.Sockets.TcpClient
        $c.Connect('127.0.0.1', $Port)
        $ok = $c.Connected
        $c.Close()
        return $ok
    } catch { return $false }
}

# Reads a complete Redis reply by draining the socket until it stays quiet for a moment.
#
# A single Read() is not enough: KEYS can return more bytes than one socket read, and a
# truncated reply would make correct Redis output look wrong.
function Read-AllBytes($stream) {
    $all = New-Object System.Collections.Generic.List[byte]
    $chunk = New-Object byte[] 4096
    $stream.ReadTimeout = 1500
    while ($true) {
        try {
            $read = $stream.Read($chunk, 0, $chunk.Length)
        } catch {
            break
        }
        if ($read -le 0) { break }
        for ($i = 0; $i -lt $read; $i++) { $all.Add($chunk[$i]) }
        if ($stream.DataAvailable -eq $false) { break }
    }
    return $all.ToArray()
}

# Sends one RESP command to Redis over raw TCP; no redis-cli build exists on this machine.
function Invoke-Redis([string[]]$CommandArgs) {
    $client = New-Object System.Net.Sockets.TcpClient
    $client.Connect('127.0.0.1', 6379)
    $stream = $client.GetStream()
    try {
        $sb = New-Object System.Text.StringBuilder
        [void]$sb.Append("*$($CommandArgs.Count)`r`n")
        foreach ($a in $CommandArgs) {
            [void]$sb.Append("`$$($a.Length)`r`n$a`r`n")
        }
        $bytes = [System.Text.Encoding]::ASCII.GetBytes($sb.ToString())
        $stream.Write($bytes, 0, $bytes.Length)
        $stream.Flush()
        $reply = Read-AllBytes $stream
        return ([System.Text.Encoding]::ASCII.GetString($reply)).Trim()
    } finally {
        $client.Close()
    }
}

function Query([string]$sql) {
    # mysql prints a "password on the command line" warning to stderr; keep it from being
    # turned into a terminating error by the caller's $ErrorActionPreference.
    $previous = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        return (& $mysql -h 127.0.0.1 -P 3306 -u root -p123456 -B -e $sql 2>$null)
    } finally {
        $ErrorActionPreference = $previous
    }
}

# Reduces a raw RESP reply to something readable, so the transcript shows values and not
# protocol noise. Handles the four shapes Redis actually returns here: +status, :integer,
# $bulk and *array.
#
# Lines are split on CR only, not CRLF. The OS text layer normalises CRLF to LF on the way
# out of the socket, so splitting on CRLF leaves an empty element between every pair of lines
# and the bulk payload ends up indexed one line too far.
function Simplify-Resp([string]$raw) {
    if ([string]::IsNullOrEmpty($raw)) { return '(empty)' }
    $cr = [string]([char]13)
    if ($raw.StartsWith('+')) { return $raw.Substring(1).Split($cr)[0] }
    if ($raw.StartsWith('-')) { return "ERROR $($raw.Substring(1).Split($cr)[0])" }
    if ($raw.StartsWith(':')) { return $raw.Substring(1).Split($cr)[0] }
    if ($raw.StartsWith('$')) {
        if ($raw.Split($cr)[0] -eq '$-1') { return '(nil)' }
        return $raw.Substring($raw.IndexOf("`n") + 1).Split($cr)[0]
    }
    if ($raw.StartsWith('*')) {
        $items = @()
        $rest = $raw.Substring($raw.IndexOf("`n") + 1)
        while ($rest.Length -gt 0) {
            $nl = $rest.IndexOf("`n")
            if ($nl -lt 0) { break }
            $header = $rest.Substring(0, $nl).TrimEnd([char]13)
            $rest = $rest.Substring($nl + 1)
            if (-not $header.StartsWith('$')) { continue }
            if ($header -eq '$-1') { continue }
            $len = [int]$header.Substring(1)
            $items += $rest.Substring(0, [Math]::Min($len, $rest.Length)).TrimEnd([char]13)
            $rest = if ($rest.Length -gt $len) { $rest.Substring($len) } else { '' }
        }
        return "[$($items -join ', ')]"
    }
    return $raw
}

# Empties only database 0, the database this service uses.
#
# SELECT and FLUSHDB must share one connection: every Invoke-Redis call opens its own socket,
# so issuing them separately would run FLUSHDB against whatever database the new connection
# defaults to. The SELECT reply is checked, so a misconfigured run cannot wipe a neighbour's
# keyspace (another verification instance in this workspace uses Redis database 3).
function Reset-RedisDb0 {
    $client = New-Object System.Net.Sockets.TcpClient
    $client.Connect('127.0.0.1', 6379)
    $stream = $client.GetStream()
    try {
        foreach ($command in @(@('SELECT', '0'), @('FLUSHDB'))) {
            $sb = New-Object System.Text.StringBuilder
            [void]$sb.Append("*$($command.Count)`r`n")
            foreach ($arg in $command) {
                [void]$sb.Append("`$$($arg.Length)`r`n$arg`r`n")
            }
            $bytes = [System.Text.Encoding]::ASCII.GetBytes($sb.ToString())
            $stream.Write($bytes, 0, $bytes.Length)
            $stream.Flush()
            $reply = [System.Text.Encoding]::ASCII.GetString((Read-AllBytes $stream)).Trim()
            if ($reply.StartsWith('-')) { throw "Redis rejected '$($command -join ' ')': $reply" }
            if ($command[0] -eq 'SELECT' -and -not $reply.StartsWith('+OK')) {
                throw "could not select Redis database 0: $reply"
            }
            if ($command[0] -eq 'FLUSHDB') { return (Simplify-Resp $reply) }
        }
    } finally {
        $client.Close()
    }
}

Write-Host '########## starting fsrs-server ##########'
if ($Reset) {
    Write-Host "FLUSHDB on database 0 -> $(Reset-RedisDb0)"
}
if (Test-Port 8080) { throw 'port 8080 is already taken; stop the other instance first' }

$proc = Start-Process -FilePath "$env:JAVA_HOME\bin\java.exe" `
    -ArgumentList '-Duser.timezone=UTC', '-Dfile.encoding=UTF-8', '-jar', "$logDir\fsrs-server-1.0.0.jar" `
    -WorkingDirectory $logDir -RedirectStandardOutput "$logDir\server-out.log" `
    -RedirectStandardError "$logDir\server-err.log" -PassThru -WindowStyle Hidden
$proc.Id | Set-Content "$logDir\server.pid"
Write-Host "server PID=$($proc.Id)"

$deadline = (Get-Date).AddSeconds(90)
while ((Get-Date) -lt $deadline -and -not (Test-Port 8080)) {
    if ($proc.HasExited) { throw "server exited with code $($proc.ExitCode)" }
    Start-Sleep -Milliseconds 500
}
if (-not (Test-Port 8080)) { throw 'server did not start listening on 8080' }
Write-Host 'server is listening on 127.0.0.1:8080'

# ---------------------------------------------------------------- 1. login
$login = Invoke-RestMethod -Method Post -Uri "$base/api/auth/login" -ContentType 'application/json' `
    -Body (@{ username = 'demo'; password = 'demo1234' } | ConvertTo-Json)
Show 'POST /api/auth/login {username:demo,password:demo1234}' $login
$headers = @{ Authorization = "Bearer $($login.token)" }
$userId = $login.userId

# ----------------------------------------------------- 2. auth is enforced
try {
    Invoke-RestMethod -Method Get -Uri "$base/api/decks" | Out-Null
    Show 'GET /api/decks with NO token' 'UNEXPECTED: answered 200 without a token'
} catch {
    $reader = New-Object System.IO.StreamReader($_.Exception.Response.GetResponseStream())
    Show 'GET /api/decks with NO token (expect 401 JSON)' `
        "status=$([int]$_.Exception.Response.StatusCode) body=$($reader.ReadToEnd())"
}

# ------------------------------------------------------------- 3. new deck
$deck = Invoke-RestMethod -Method Post -Uri "$base/api/decks" -Headers $headers -ContentType 'application/json' `
    -Body (@{ name = 'E2E Verification Deck' } | ConvertTo-Json)
Show 'POST /api/decks {"name":"E2E Verification Deck"}' $deck
$deckId = $deck.id

# ----------------------------------------------------------- 4. three cards
$cardIds = @()
for ($i = 1; $i -le 3; $i++) {
    $card = Invoke-RestMethod -Method Post -Uri "$base/api/cards" -Headers $headers -ContentType 'application/json' `
        -Body (@{ deckId = $deckId; front = "e2e-front-$i"; back = "e2e-back-$i" } | ConvertTo-Json)
    $cardIds += $card.id
    Show "POST /api/cards (card $i)" $card
}

Show 'GET /api/decks' (Invoke-RestMethod -Method Get -Uri "$base/api/decks" -Headers $headers)
Show "GET /api/cards?deckId=$deckId" (Invoke-RestMethod -Method Get -Uri "$base/api/cards?deckId=$deckId" -Headers $headers)

# ----------------------------------------------------------- 5. study queue
$queue = Invoke-RestMethod -Method Get -Uri "$base/api/study/queue?deckId=$deckId&limit=20" -Headers $headers
Show 'GET /api/study/queue?deckId=&limit=20' $queue
Write-Host "queue card count=$($queue.cards.Count) predictedIntervals(card1)=$($queue.cards[0].predictedIntervals | ConvertTo-Json -Compress)"

# 5b. the same call again must not hand the same card out twice.
$queue2 = Invoke-RestMethod -Method Get -Uri "$base/api/study/queue?deckId=$deckId&limit=20" -Headers $headers
Write-Host ''
Write-Host "=== GET /api/study/queue called a SECOND time (anti double-issue) ==="
Write-Host "first call cardIds   = $(($queue.cards  | ForEach-Object { $_.cardId }) -join ',')"
Write-Host "second call cardIds  = $(($queue2.cards | ForEach-Object { $_.cardId }) -join ',')"
$first = $queue.cards | ForEach-Object { $_.cardId }
$second = $queue2.cards | ForEach-Object { $_.cardId }
$overlap = @($first | Where-Object { $second -contains $_ })
Write-Host "overlap between the two calls = $($overlap.Count) (must be 0)"
Write-Host "duplicates inside the first call = $(@($first | Group-Object | Where-Object { $_.Count -gt 1 }).Count) (must be 0)"

# 5c. the daily new-card allowance is a hard cap.
$queueSmall = Invoke-RestMethod -Method Get -Uri "$base/api/study/queue?deckId=$deckId&limit=1" -Headers $headers
Write-Host "limit=1 returns $($queueSmall.cards.Count) card(s) (totalDue=$($queueSmall.totalDue) totalNew=$($queueSmall.totalNew))"

# 5d. a review card that is due must also be emitted, not only new cards.
$queueDemo = Invoke-RestMethod -Method Get -Uri "$base/api/study/queue?limit=50" -Headers $headers
Write-Host "whole-account queue: cards=$($queueDemo.cards.Count) totalDue=$($queueDemo.totalDue) totalNew=$($queueDemo.totalNew) isNewFlags=$(($queueDemo.cards | ForEach-Object { [int]$_.isNew }) -join ',')"

# -------------------------------------------------------- 6. suspend a card
Show "POST /api/cards/$($cardIds[2])/suspend" (Invoke-RestMethod -Method Post -Uri "$base/api/cards/$($cardIds[2])/suspend" -Headers $headers)
Show 'card 3 suspended flag in MySQL' (Query "select id, suspended from fsrs.card where id=$($cardIds[2]);")

# --------------------------------------------------- 7. review_log before
$before = (Query 'select count(*) from fsrs.review_log;' | Select-Object -Last 1)
Write-Host "review_log count BEFORE the new submissions = $before"

# ----------------------------------------------------------- 8. first review
$reviewCardId = $cardIds[0]
$clientReviewId = [guid]::NewGuid().ToString()
$reviewBody = @{ cardId = $reviewCardId; rating = 3; durationMs = 4321; clientReviewId = $clientReviewId } | ConvertTo-Json
$review1 = Invoke-RestMethod -Method Post -Uri "$base/api/study/review" -Headers $headers -ContentType 'application/json' -Body $reviewBody
Show "POST /api/study/review first submission (Good) clientReviewId=$clientReviewId" $review1

# ----------------------------------------------------- 9. duplicate replay
$review2 = Invoke-RestMethod -Method Post -Uri "$base/api/study/review" -Headers $headers -ContentType 'application/json' -Body $reviewBody
Show 'POST /api/study/review SAME clientReviewId (expect duplicate:true)' $review2

# ------------------------------------------- 10. a second, genuine review
$review3 = Invoke-RestMethod -Method Post -Uri "$base/api/study/review" -Headers $headers -ContentType 'application/json' `
    -Body (@{ cardId = $cardIds[1]; rating = 4; durationMs = 1500; clientReviewId = [guid]::NewGuid().ToString() } | ConvertTo-Json)
Show 'POST /api/study/review second card (Easy)' $review3

# ------------------------------- 10b. Again must not bounce back the same round
$againBody = @{ cardId = $cardIds[0]; rating = 1; durationMs = 900; clientReviewId = [guid]::NewGuid().ToString() } | ConvertTo-Json
$reviewAgain = Invoke-RestMethod -Method Post -Uri "$base/api/study/review" -Headers $headers -ContentType 'application/json' -Body $againBody
Show 'POST /api/study/review rating=1 Again on the card just reviewed (same round)' $reviewAgain
$queueAfter = Invoke-RestMethod -Method Get -Uri "$base/api/study/queue?deckId=$deckId&limit=20" -Headers $headers
$backAgain = @($queueAfter.cards | Where-Object { $_.cardId -eq $cardIds[0] })
Write-Host "card $($cardIds[0]) re-issued in the same round = $($backAgain.Count) (must be 0: dueTs is clamped to now+60s)"
Write-Host "nextDue - now = $([math]::Round(($reviewAgain.nextDue - [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds())/1000)) s"

# ----------------------------------------------- 11. review_log after + row
$after = (Query 'select count(*) from fsrs.review_log;' | Select-Object -Last 1)
Show 'review_log count AFTER' "before=$before after=$after delta=$([int]$after - [int]$before)"
Show 'review_log rows for that clientReviewId (must be exactly 1)' `
    (Query "select id, card_id, rating, client_review_id from fsrs.review_log where client_review_id='$clientReviewId';")
Show 'card row after review' `
    (Query "select id, state, reps, lapses, round(stability,4) s, round(difficulty,4) d, due_ts, last_review_ts from fsrs.card where id=$reviewCardId;")

# ------------------------------------------------------------- 12. stats
Show 'GET /api/stats/summary' (Invoke-RestMethod -Method Get -Uri "$base/api/stats/summary" -Headers $headers)
$heatmap = Invoke-RestMethod -Method Get -Uri "$base/api/stats/heatmap?days=365" -Headers $headers
Show "GET /api/stats/heatmap?days=365 (cells=$($heatmap.Count), first 3)" ($heatmap | Select-Object -First 3)

# --------------------------------------------------------- 13. scheduler
$params = Invoke-RestMethod -Method Get -Uri "$base/api/scheduler/params" -Headers $headers
Show 'GET /api/scheduler/params' $params

# Round-trip the parameter update: write the same 21 weights back, then read them again.
$sameWeights = @($params.weights)
$putBody = @{ weights = $sameWeights; desiredRetention = $params.desiredRetention } | ConvertTo-Json -Depth 4
Show 'PUT /api/scheduler/params (21 weights, retention 0.9)' (Invoke-RestMethod -Method Put -Uri "$base/api/scheduler/params" -Headers $headers -ContentType 'application/json' -Body $putBody)
Show 'GET /api/scheduler/params after the update' (Invoke-RestMethod -Method Get -Uri "$base/api/scheduler/params" -Headers $headers)

$curve = Invoke-RestMethod -Method Get -Uri "$base/api/scheduler/curve?stability=10&difficulty=5&maxDay=365" -Headers $headers
Show "GET /api/scheduler/curve?stability=10&difficulty=5&maxDay=365 (points=$($curve.points.Count), first 3)" ($curve.points | Select-Object -First 3)

# ---------------------------------------------------------- 14. timeline
Show "GET /api/cards/$reviewCardId/timeline" (Invoke-RestMethod -Method Get -Uri "$base/api/cards/$reviewCardId/timeline" -Headers $headers)

# ------------------------------------------------------- 15. Redis evidence
Write-Host ''
Write-Host '=== Redis (raw RESP over TCP) ==='
Write-Host "KEYS srs:*                            -> $(Simplify-Resp (Invoke-Redis @('KEYS', 'srs:*')))"
Write-Host "ZCARD srs:due:$userId                  -> $(Simplify-Resp (Invoke-Redis @('ZCARD', "srs:due:$userId")))"
Write-Host "ZRANGE srs:due:$userId 0 4 WITHSCORES  -> $(Simplify-Resp (Invoke-Redis @('ZRANGE', "srs:due:$userId", '0', '4', 'WITHSCORES')))"
Write-Host "GET srs:new:$userId                    -> $(Simplify-Resp (Invoke-Redis @('GET', "srs:new:$userId")))"
Write-Host "SCARD srs:session:$userId              -> $(Simplify-Resp (Invoke-Redis @('SCARD', "srs:session:$userId")))"
Write-Host "SCARD srs:newset:$userId               -> $(Simplify-Resp (Invoke-Redis @('SCARD', "srs:newset:$userId")))"
Write-Host "GET srs:idem:$clientReviewId           -> $(Simplify-Resp (Invoke-Redis @('GET', "srs:idem:$clientReviewId")))"
Write-Host "TTL srs:due:$userId                    -> $(Simplify-Resp (Invoke-Redis @('TTL', "srs:due:$userId")))"
Write-Host "HGETALL srs:state:$reviewCardId        -> $(Simplify-Resp (Invoke-Redis @('HGETALL', "srs:state:$reviewCardId")))"

Write-Host ''
Write-Host "SUMMARY userId=$userId deckId=$deckId reviewCardId=$reviewCardId clientReviewId=$clientReviewId"
Write-Host "SUMMARY duplicateOnFirst=$($review1.duplicate) duplicateOnReplay=$($review2.duplicate)"
Write-Host "SUMMARY replayIdentical=$($review2.nextDue -eq $review1.nextDue -and $review2.stability -eq $review1.stability)"
Write-Host "SUMMARY review_log before=$before after=$after"
Write-Host "SERVER PID=$($proc.Id) URL=http://127.0.0.1:8080"
