<#
.SYNOPSIS
    Concurrency and idempotency probe for fsrs-server.

.DESCRIPTION
    Produces the evidence behind the resume claim
    "no double counting under concurrent replay of the same clientReviewId".

    Three experiments, each measured rather than asserted:

      A. Idempotency under replay
         Fire N concurrent reviews for the SAME card using the SAME clientReviewId.
         Expected: exactly one review recorded, exactly one response reports
         duplicate=false, and every response agrees on the resulting due date.

      B. No lost updates on one card
         Fire N concurrent reviews for the SAME card using DIFFERENT clientReviewIds.
         Expected: every successful request is recorded exactly once.

      C. Throughput
         Fire N concurrent reviews across N freshly created cards, and report
         wall-clock throughput along with latency percentiles.

    Load is generated with System.Net.Http.HttpClient and real async tasks rather
    than PowerShell runspaces, because a runspace per request would measure the
    harness instead of the server.

    Only HTTP is used - the script never opens the database - so it measures what
    a real client would experience.

.PARAMETER BaseUrl
    Base URL of the running server. Default http://localhost:8080

.PARAMETER Concurrency
    Number of concurrent requests per experiment. Default 200

.EXAMPLE
    .\benchmarks\idempotency-stress.ps1 -Concurrency 200
#>
[CmdletBinding()]
param(
    [string]$BaseUrl = 'http://localhost:8080',
    [string]$Username = 'demo',
    [string]$Password = 'demo1234',
    [int]$Concurrency = 200
)

$ErrorActionPreference = 'Stop'

# System.Net.Http is not loaded by default in every PowerShell host, and the load
# generator below needs it. Loading it here keeps the script self-contained.
Add-Type -AssemblyName System.Net.Http -ErrorAction SilentlyContinue

# ---------------------------------------------------------------- HTTP helpers

function Invoke-Login {
    $body = @{ username = $Username; password = $Password } | ConvertTo-Json -Compress
    $response = Invoke-RestMethod -Method Post -Uri "$BaseUrl/api/auth/login" `
        -ContentType 'application/json' -Body $body
    if (-not $response.token) { throw 'Login succeeded but no token was returned.' }
    return $response.token
}

function Get-AuthHeaders([string]$Token) {
    return @{ Authorization = "Bearer $Token" }
}

function Get-TotalReviews([hashtable]$Headers) {
    $summary = Invoke-RestMethod -Method Get -Uri "$BaseUrl/api/stats/summary" -Headers $Headers
    return [int]$summary.totalReviews
}

function New-Deck([hashtable]$Headers, [string]$Name) {
    $body = @{ name = $Name } | ConvertTo-Json -Compress
    return Invoke-RestMethod -Method Post -Uri "$BaseUrl/api/decks" `
        -Headers $Headers -ContentType 'application/json' -Body $body
}

function New-Card([hashtable]$Headers, [int]$DeckId, [string]$Front) {
    $body = @{ deckId = $DeckId; front = $Front; back = "answer for $Front" } | ConvertTo-Json -Compress
    # Invoke-RestMethod occasionally drops a connection under sustained load in this
    # environment, so setup calls retry; the load itself is measured, not retried.
    for ($attempt = 1; $attempt -le 4; $attempt++) {
        try {
            return Invoke-RestMethod -Method Post -Uri "$BaseUrl/api/cards" `
                -Headers $Headers -ContentType 'application/json' -Body $body
        } catch {
            if ($attempt -eq 4) { throw }
            Start-Sleep -Milliseconds 300
        }
    }
}

<#
    Splits load results into three buckets. A 429 is the rate limiter doing its job,
    not a correctness failure, so it is counted and reported separately rather than
    failing the experiment.
#>
function Split-Results([object[]]$Records) {
    $ok = @($Records | Where-Object { $_.ok })
    $rateLimited = @($Records | Where-Object { -not $_.ok -and $_.status -eq 429 }).Count
    $failed = @($Records | Where-Object { -not $_.ok -and $_.status -ne 429 }).Count
    return [pscustomobject]@{ ok = $ok; rateLimited = $rateLimited; failed = $failed }
}

<#
    Fires every request in $Requests concurrently and waits for all of them.

    Returns the wall-clock duration plus one record per request carrying the
    status code, the per-request latency and the raw response body.
#>
function Invoke-ReviewLoad {
    param(
        [string]$BaseUrl,
        [string]$Token,
        [object[]]$Requests
    )

    $handler = [System.Net.Http.HttpClientHandler]::new()
    $handler.MaxConnectionsPerServer = 1024
    $client = [System.Net.Http.HttpClient]::new($handler)
    $client.Timeout = [System.TimeSpan]::FromSeconds(60)
    $client.DefaultRequestHeaders.Authorization =
        [System.Net.Http.Headers.AuthenticationHeaderValue]::new('Bearer', $Token)

    $uri = "$BaseUrl/api/study/review"
    # Task<HttpResponseMessage> derives from Task, so a plain Task list can hold them.
    $tasks = [System.Collections.Generic.List[System.Threading.Tasks.Task]]::new()
    $clocks = [System.Collections.Generic.List[System.Diagnostics.Stopwatch]]::new()

    foreach ($request in $Requests) {
        $json = @{
            cardId         = $request.cardId
            rating         = $request.rating
            durationMs     = $request.durationMs
            clientReviewId = $request.clientReviewId
        } | ConvertTo-Json -Compress

        $content = [System.Net.Http.StringContent]::new(
            $json, [System.Text.Encoding]::UTF8, 'application/json')

        $clock = [System.Diagnostics.Stopwatch]::StartNew()
        $tasks.Add($client.PostAsync($uri, $content))
        $clocks.Add($clock)
    }

    $wall = [System.Diagnostics.Stopwatch]::StartNew()
    # Stop each stopwatch as its OWN request completes. Waiting for the whole batch
    # first would stop every clock at the same instant and make each request look as
    # slow as the slowest one in the burst.
    $pending = [System.Collections.Generic.List[int]]::new()
    for ($i = 0; $i -lt $tasks.Count; $i++) { $pending.Add($i) }
    while ($pending.Count -gt 0) {
        for ($j = $pending.Count - 1; $j -ge 0; $j--) {
            $index = $pending[$j]
            if ($tasks[$index].IsCompleted) {
                $clocks[$index].Stop()
                $pending.RemoveAt($j)
            }
        }
        if ($pending.Count -gt 0) { Start-Sleep -Milliseconds 1 }
    }
    $wall.Stop()

    $records = @()
    for ($i = 0; $i -lt $tasks.Count; $i++) {
        # .Result only exists on Task<T>, so cast back to the closed generic type.
        $response = ([System.Threading.Tasks.Task[System.Net.Http.HttpResponseMessage]]$tasks[$i]).Result
        $text = $response.Content.ReadAsStringAsync().Result

        $duplicate = $false
        $nextDue = $null
        if ($response.IsSuccessStatusCode -and $text) {
            try {
                $parsed = $text | ConvertFrom-Json
                $duplicate = [bool]$parsed.duplicate
                $nextDue = $parsed.nextDue
            } catch { }
        }

        $records += [pscustomobject]@{
            ok        = $response.IsSuccessStatusCode
            status    = [int]$response.StatusCode
            ms        = $clocks[$i].Elapsed.TotalMilliseconds
            duplicate = $duplicate
            nextDue   = $nextDue
        }
    }

    $client.Dispose()
    return [pscustomobject]@{ records = $records; wallSeconds = $wall.Elapsed.TotalSeconds }
}

function Show-Percentiles {
    param([object[]]$Records, [string]$Label = 'latency')
    $values = @($Records | ForEach-Object { $_.ms } | Sort-Object)
    if ($values.Count -eq 0) { return }
    $at = {
        param($q)
        $index = [Math]::Min($values.Count - 1, [int][Math]::Floor($q * $values.Count))
        return $values[$index]
    }
    Write-Host ("  {0,-8}: n={1}  p50={2:N1}ms  p95={3:N1}ms  p99={4:N1}ms  max={5:N1}ms" -f `
        $Label, $values.Count, (& $at 0.50), (& $at 0.95), (& $at 0.99), $values[-1])
}

# ------------------------------------------------------------------ run
Write-Host ''
Write-Host '=== fsrs-server idempotency & concurrency probe ===' -ForegroundColor Cyan
Write-Host "target      : $BaseUrl"
Write-Host "concurrency : $Concurrency"
Write-Host ''

$token = Invoke-Login
$headers = Get-AuthHeaders $token
$deck = New-Deck $headers ("stress-" + (Get-Date -Format 'HHmmss'))
$deckId = [int]$deck.id
Write-Host "created deck id=$deckId" -ForegroundColor DarkGray

# ------------------------------------------------- A. same clientReviewId
Write-Host ''
Write-Host '[A] idempotency: same clientReviewId fired concurrently' -ForegroundColor White

$cardId = [int](New-Card $headers $deckId 'idempotency-probe').id
$before = Get-TotalReviews $headers
$sharedKey = [guid]::NewGuid().ToString()

$requests = @()
for ($i = 0; $i -lt $Concurrency; $i++) {
    $requests += @{ cardId = $cardId; rating = 3; durationMs = 900; clientReviewId = $sharedKey }
}

$loadA = Invoke-ReviewLoad -BaseUrl $BaseUrl -Token $token -Requests $requests
$after = Get-TotalReviews $headers

$recorded = $after - $before
$splitA = Split-Results $loadA.records
$okA = $splitA.ok
$firstWrites = @($okA | Where-Object { -not $_.duplicate }).Count
$duplicates = @($okA | Where-Object { $_.duplicate }).Count
$failed = $splitA.failed
$rateLimited = $splitA.rateLimited
$distinctDue = @($okA | Select-Object -ExpandProperty nextDue -Unique).Count

Write-Host "  requests            : $Concurrency"
Write-Host "  review_log delta    : $recorded   (expected 1)"
Write-Host "  duplicate=false     : $firstWrites   (expected 1)"
Write-Host "  duplicate=true      : $duplicates"
Write-Host "  distinct nextDue    : $distinctDue   (expected 1)"
Write-Host "  failed requests     : $failed"
if ($rateLimited -gt 0) { Write-Host "  rate limited (429)  : $rateLimited" -ForegroundColor DarkGray }
Write-Host "  wall clock          : $([Math]::Round($loadA.wallSeconds, 2))s"
Show-Percentiles -Records $loadA.records

$passA = ($recorded -eq 1) -and ($firstWrites -eq 1) -and ($distinctDue -eq 1) -and ($failed -eq 0)
if ($passA) { Write-Host '  RESULT: PASS' -ForegroundColor Green }
else { Write-Host '  RESULT: FAIL' -ForegroundColor Red }

# -------------------------------- B. one card, distinct clientReviewIds
Write-Host ''
Write-Host '[B] no lost updates: same card, distinct clientReviewIds' -ForegroundColor White

$cardIdB = [int](New-Card $headers $deckId 'lost-update-probe').id
$beforeB = Get-TotalReviews $headers

$requests = @()
for ($i = 0; $i -lt $Concurrency; $i++) {
    $requests += @{ cardId = $cardIdB; rating = 3; durationMs = 700; clientReviewId = [guid]::NewGuid().ToString() }
}

$loadB = Invoke-ReviewLoad -BaseUrl $BaseUrl -Token $token -Requests $requests
$afterB = Get-TotalReviews $headers

$recordedB = $afterB - $beforeB
$splitB = Split-Results $loadB.records
$successB = $splitB.ok.Count
$failedB = $splitB.failed

Write-Host "  successful requests : $successB / $Concurrency"
Write-Host "  review_log delta    : $recordedB   (every success must be recorded exactly once)"
Write-Host "  failed requests     : $failedB"
if ($splitB.rateLimited -gt 0) { Write-Host "  rate limited (429)  : $($splitB.rateLimited)" -ForegroundColor DarkGray }
Write-Host "  wall clock          : $([Math]::Round($loadB.wallSeconds, 2))s"
Show-Percentiles -Records $loadB.records

$passB = ($failedB -eq 0) -and ($recordedB -eq $successB) -and ($successB -gt 0)
if ($passB) { Write-Host '  RESULT: PASS' -ForegroundColor Green }
else { Write-Host '  RESULT: FAIL' -ForegroundColor Red }

# ------------------------------------------- C. throughput on fresh cards
Write-Host ''
Write-Host '[C] throughput: distinct cards, concurrent reviews' -ForegroundColor White

$requests = @()
for ($i = 1; $i -le $Concurrency; $i++) {
    $id = [int](New-Card $headers $deckId "load-$i").id
    $requests += @{ cardId = $id; rating = 3; durationMs = 800; clientReviewId = [guid]::NewGuid().ToString() }
}
Write-Host "  created $($requests.Count) cards" -ForegroundColor DarkGray

$loadC = Invoke-ReviewLoad -BaseUrl $BaseUrl -Token $token -Requests $requests

$splitC = Split-Results $loadC.records
$okC = $splitC.ok.Count
$qps = 0
if ($loadC.wallSeconds -gt 0) { $qps = [Math]::Round($okC / $loadC.wallSeconds, 1) }

Write-Host "  successful          : $okC / $Concurrency"
Write-Host "  wall clock          : $([Math]::Round($loadC.wallSeconds, 2))s"
Write-Host "  throughput          : $qps req/s"
if ($splitC.rateLimited -gt 0) { Write-Host "  rate limited (429)  : $($splitC.rateLimited)" -ForegroundColor DarkGray }
if ($splitC.failed -gt 0) { Write-Host "  failed (non-429)    : $($splitC.failed)" -ForegroundColor Yellow }
Show-Percentiles -Records $loadC.records

# ------------------------------------------------------------------ summary
Write-Host ''
Write-Host '=== summary ===' -ForegroundColor Cyan
Write-Host ("  idempotency (A)     : {0}" -f $(if ($passA) { 'PASS' } else { 'FAIL' }))
Write-Host ("  no lost updates (B) : {0}" -f $(if ($passB) { 'PASS' } else { 'FAIL' }))
Write-Host ("  throughput (C)      : {0} req/s over {1} concurrent requests" -f $qps, $Concurrency)
Write-Host ''
Write-Host '  Paste these numbers into README.md and docs/interview-notes.md.' -ForegroundColor DarkGray
Write-Host ''
