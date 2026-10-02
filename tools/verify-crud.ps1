# Final end-to-end verification: CRUD / soft delete / archive / recycle bin.
#
# Requires a UTF-8 BOM on disk (this file contains Chinese literals); without it a
# Chinese Windows install decodes it as ANSI and reports bogus syntax errors.
#
# Two traps this script works around, both client-side rather than server bugs:
#   1. Windows PowerShell 5.1 encodes a *string* body as ASCII unless the content type
#      carries a charset, silently turning Chinese into "?". Writes send UTF-8 bytes.
#   2. The study queue deliberately re-issues nothing that this session already served
#      (acquire_cards.lua guards with SISMEMBER), so a fresh card must be removed from
#      the session set before the queue will hand it over.

$ErrorActionPreference = 'Stop'
$base = 'http://localhost:8080'

function U([int[]]$codes) { -join ($codes | ForEach-Object { [char]$_ }) }
$CN_DECK  = U @(0x7F16,0x7801,0x6D4B,0x8BD5,0x5361,0x7EC4)
$CN_FRONT = U @(0x4E2D,0x6587,0x6B63,0x9762,0xFF1A,0x7A33,0x5B9A,0x6027)
$CN_BACK  = U @(0x8FD8,0x6709,0x20,0x39,0x30,0x25,0x20,0x628A,0x63E1,0x20,0x2014,0x2014,0x20,0x53,0x3D,0x34,0x36)
$CN_EDIT  = U @(0x4FEE,0x6539,0x540E,0x7684,0x80CC,0x9762)

function Redis([string[]]$cmds) {
  $c = New-Object System.Net.Sockets.TcpClient('127.0.0.1', 6379); $s = $c.GetStream()
  $out = @()
  foreach ($cmd in $cmds) {
    $b = [Text.Encoding]::ASCII.GetBytes("$cmd`r`n"); $s.Write($b, 0, $b.Length); $s.Flush(); Start-Sleep -Milliseconds 250
    $buf = New-Object byte[] 4096; $n = $s.Read($buf, 0, 4096)
    $out += ([Text.Encoding]::ASCII.GetString($buf, 0, $n).Trim())
  }
  $c.Close(); return $out
}

$pass = 0; $fail = 0
function Check($name, $cond, $detail) {
  if ($cond) { $script:pass++; Write-Host ("  [PASS] " + $name) }
  else { $script:fail++; Write-Host ("  [FAIL] " + $name + "  -> " + $detail) }
}

$login = Invoke-RestMethod -Method Post -Uri "$base/api/auth/login" -ContentType 'application/json' -Body '{"username":"demo","password":"demo1234"}' -TimeoutSec 25
$tok = $login.token
function Api($method, $path, $obj) {
  $h = @{ Authorization = "Bearer $tok" }
  if ($null -eq $obj) { return Invoke-RestMethod -Method $method -Uri "$base$path" -Headers $h -TimeoutSec 25 }
  $bytes = [System.Text.Encoding]::UTF8.GetBytes(($obj | ConvertTo-Json -Compress))
  return Invoke-RestMethod -Method $method -Uri "$base$path" -Headers $h -ContentType 'application/json; charset=utf-8' -Body $bytes -TimeoutSec 25
}
function Find($list, $id) { foreach ($x in @($list)) { if ($x.id -eq $id) { return $x } } return $null }
function SqlCount($q) { $v = & mysql -uroot -p123456 -N -e $q 2>&1 | Out-Null; return [int]($v | Select-Object -First 1) }

Write-Host "`n=== 0) 清理上次中断留下的测试卡组 ==="
& mysql -uroot -p123456 -e "DELETE rl FROM fsrs.review_log rl JOIN fsrs.card c ON c.id=rl.card_id JOIN fsrs.deck d ON d.id=c.deck_id WHERE d.id IN (12,13);" 2>&1 | Out-Null
& mysql -uroot -p123456 -e "DELETE FROM fsrs.card WHERE deck_id IN (12,13); DELETE FROM fsrs.deck WHERE id IN (12,13);" 2>&1 | Out-Null
Check "遗留测试卡组已清除" ((SqlCount "SELECT COUNT(*) FROM fsrs.deck WHERE id IN (12,13);") -eq 0) ""

Write-Host "`n=== 1) UTF-8 往返 ==="
$deck = Api 'Post' '/api/decks' @{ name = $CN_DECK }
Check "卡组名中文无损" ($deck.name -eq $CN_DECK) "got=$($deck.name)"
$card = Api 'Post' '/api/cards' @{ deckId = $deck.id; front = $CN_FRONT; back = $CN_BACK }
Check "卡片正/背面中文无损" (($card.front -eq $CN_FRONT) -and ($card.back -eq $CN_BACK)) "front=$($card.front)"

Write-Host "`n=== 2) 队列发卡 + 改文字不重置记忆状态 ==="
$null = Redis @('SELECT 0', "SREM srs:session:1 $($card.id)")
$q = Api 'Get' "/api/study/queue?deckId=$($deck.id)&limit=5" $null
Check "队列能把新卡发给未见过它的会话" (@($q.cards).Count -ge 1) "returned=$(@($q.cards).Count)"
$c0 = @($q.cards)[0]
$rev = Api 'Post' '/api/study/review' @{ cardId = $c0.cardId; rating = 3; durationMs = 900; clientReviewId = [guid]::NewGuid().ToString() }
Check "复习成功且 S 非空" ($rev.stability -gt 0) "S=$($rev.stability)"
$before = Find (Api 'Get' "/api/cards?deckId=$($deck.id)" $null) $c0.cardId
$after = Api 'Put' "/api/cards/$($c0.cardId)" @{ back = $CN_EDIT }
Check "改文字后中文仍无损" ($after.back -eq $CN_EDIT) "back=$($after.back)"
$same = $true
foreach ($f in 'state','due','stability','difficulty','reps','lapses') { if ($before.$f -ne $after.$f) { $same = $false; Write-Host "      DIFF $f : $($before.$f) -> $($after.$f)" } }
Check "记忆状态逐位一致" $same ""
Check "编辑不会把卡片变回新卡" ($after.state -eq 2) "state=$($after.state)"

Write-Host "`n=== 3) 暂停 / 恢复 ==="
Check "PUT suspended=true"  ((Api 'Put' "/api/cards/$($c0.cardId)" @{ suspended = $true }).suspended -eq $true) ""
Check "PUT suspended=false" ((Api 'Put' "/api/cards/$($c0.cardId)" @{ suspended = $false }).suspended -eq $false) ""

Write-Host "`n=== 4) 卡片软删除 / 回收站 / 恢复 ==="
$logsBefore = SqlCount "SELECT COUNT(*) FROM fsrs.review_log WHERE card_id=$($c0.cardId);"
$del = Api 'Delete' "/api/cards/$($c0.cardId)" $null
Check "删除返回 reviewsPreserved = 日志条数" ($del.reviewsPreserved -eq $logsBefore) "preserved=$($del.reviewsPreserved) logs=$logsBefore"
Check "删除后不在列表" ((Find (Api 'Get' "/api/cards?deckId=$($deck.id)" $null) $c0.cardId) -eq $null) ""
Check "review_log 一行未减" ((SqlCount "SELECT COUNT(*) FROM fsrs.review_log WHERE card_id=$($c0.cardId);") -eq $logsBefore) "before=$logsBefore"
Check "出现在回收站" ((Find (Api 'Get' '/api/cards/deleted' $null) $c0.cardId) -ne $null) ""
Check "重复删除幂等 alreadyGone=true" ((Api 'Delete' "/api/cards/$($c0.cardId)" $null).alreadyGone -eq $true) ""
$putDeleted = & { try { Api 'Put' "/api/cards/$($c0.cardId)" @{ front = 'x' } | Out-Null; 200 } catch { [int]$_.Exception.Response.StatusCode } }
Check "已删除卡不可编辑(404)" ($putDeleted -eq 404) "got=$putDeleted"
$res = Api 'Post' "/api/cards/$($c0.cardId)/restore" $null
Check "恢复后记忆状态逐位一致" (($res.state -eq $before.state) -and ($res.due -eq $before.due) -and ($res.stability -eq $before.stability) -and ($res.reps -eq $before.reps)) "S=$($res.stability) vs $($before.stability)"

Write-Host "`n=== 5) 卡组归档 / 取消归档 ==="
$beforeDeck = Find (Api 'Get' '/api/decks' $null) $deck.id
$arc = Api 'Post' "/api/decks/$($deck.id)/archive" $null
Check "归档后 archived=true" ($arc.archived -eq $true) "archived=$($arc.archived)"
Check "归档后 due/new 归零、cardCount 不变" (($arc.dueCount -eq 0) -and ($arc.newCount -eq 0) -and ($arc.cardCount -eq $beforeDeck.cardCount)) "due=$($arc.dueCount) new=$($arc.newCount) cards=$($arc.cardCount)"
Check "归档卡组仍在 GET /api/decks" ((Find (Api 'Get' '/api/decks' $null) $deck.id) -ne $null) ""
Check "显式指定归档卡组也拿不到卡" (@((Api 'Get' "/api/study/queue?deckId=$($deck.id)&limit=10" $null).cards).Count -eq 0) ""
Check "summary 报 archivedDecks>=1" ((Api 'Get' '/api/stats/summary' $null).archivedDecks -ge 1) ""
Check "取消归档 archived=false" ((Api 'Post' "/api/decks/$($deck.id)/unarchive" $null).archived -eq $false) ""

Write-Host "`n=== 6) 卡组软删除 / 回收站 / 恢复 ==="
$ddel = Api 'Delete' "/api/decks/$($deck.id)" $null
Check "删卡组 cardsDeleted>=1" ($ddel.cardsDeleted -ge 1) "cardsDeleted=$($ddel.cardsDeleted)"
Check "卡组出现在回收站" ((Find (Api 'Get' '/api/decks/deleted' $null) $deck.id) -ne $null) ""
$leaked = $false
foreach ($r in @(Api 'Get' '/api/cards/deleted' $null)) { if ($r.deckId -eq $deck.id) { $leaked = $true } }
Check "已删卡组的卡片不单独列出" (-not $leaked) "leaked for deck $($deck.id)"
Check "恢复卡组" ((Api 'Post' "/api/decks/$($deck.id)/restore" $null).id -eq $deck.id) ""

Write-Host "`n=== 7) 归档 + 删除叠加：恢复后仍应处于归档态 ==="
$null = Api 'Post' "/api/decks/$($deck.id)/archive" $null
$null = Api 'Delete' "/api/decks/$($deck.id)" $null
Check "恢复后仍归档" ((Api 'Post' "/api/decks/$($deck.id)/restore" $null).archived -eq $true) ""
$null = Api 'Post' "/api/decks/$($deck.id)/unarchive" $null

Write-Host "`n=== 8) 清理测试数据（含 Redis 索引）==="
$ids = @(& mysql -uroot -p123456 -N -e "SELECT id FROM fsrs.card WHERE deck_id=$($deck.id);" 2>&1 | Out-Null | Where-Object { "$_".Trim() -ne '' })
foreach ($cid in $ids) { $null = Redis @('SELECT 0', "ZREM srs:due:1 $cid", "SREM srs:session:1 $cid", "DEL srs:state:$cid") }
& mysql -uroot -p123456 -e "DELETE rl FROM fsrs.review_log rl JOIN fsrs.card c ON c.id=rl.card_id WHERE c.deck_id=$($deck.id);" 2>&1 | Out-Null
& mysql -uroot -p123456 -e "DELETE FROM fsrs.card WHERE deck_id=$($deck.id); DELETE FROM fsrs.deck WHERE id=$($deck.id);" 2>&1 | Out-Null
Check "测试卡组已清除" ((SqlCount "SELECT COUNT(*) FROM fsrs.deck WHERE id=$($deck.id);") -eq 0) ""
Check "测试卡片已清除" ((SqlCount "SELECT COUNT(*) FROM fsrs.card WHERE deck_id=$($deck.id);") -eq 0) ""

Write-Host ("`n=== 结果: PASS=" + $pass + "  FAIL=" + $fail + " ===")
if ($fail -gt 0) { exit 1 }
