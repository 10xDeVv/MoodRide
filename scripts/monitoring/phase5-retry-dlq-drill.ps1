$ErrorActionPreference = 'Stop'

$log = 'docs/verification/logs/phase5-retry-dlq-drill-2026-04-06.log'
$json = 'docs/verification/logs/phase5-retry-dlq-drill-2026-04-06.json'
$startUtc = (Get-Date).ToUniversalTime().ToString('o')
$jobRetry = [guid]::NewGuid().ToString()
$jobDlq = [guid]::NewGuid().ToString()
$user1 = [guid]::NewGuid().ToString()
$user2 = [guid]::NewGuid().ToString()

"Phase 5 Retry/DLQ Runtime Drill (2026-04-06)" | Set-Content $log
"startUtc=$startUtc" | Add-Content $log
"jobRetry=$jobRetry" | Add-Content $log
"jobDlq=$jobDlq" | Add-Content $log

$beforeOffsetsRaw = docker exec moodride-kafka bash -lc "kafka-run-class kafka.tools.GetOffsetShell --broker-list localhost:9092 --topic route.jobs.dlq --time -1"
$beforeOffset = ($beforeOffsetsRaw | ForEach-Object {
    $parts = $_.Split(':')
    if ($parts.Count -ge 3) { [int64]$parts[2] } else { 0 }
} | Measure-Object -Sum).Sum
"dlqOffsetsBefore=$beforeOffset" | Add-Content $log

$seedSql = @"
INSERT INTO route_jobs (id, user_id, start_latitude, start_longitude, time_budget_minutes, vibe, status, submitted_at, started_at, retry_count, max_retries)
VALUES
  ('$jobRetry'::uuid, '$user1'::uuid, 45.5152, -122.6784, 60, 'coastal', 'PROCESSING', NOW() - INTERVAL '10 minutes', NOW() - INTERVAL '5 minutes', 0, 2),
  ('$jobDlq'::uuid, '$user2'::uuid, 45.5152, -122.6784, 60, 'coastal', 'PROCESSING', NOW() - INTERVAL '10 minutes', NOW() - INTERVAL '5 minutes', 2, 2)
ON CONFLICT (id) DO NOTHING;
"@

docker run --rm -e PGPASSWORD=postgres postgres:15 psql -h host.docker.internal -U postgres -d moodride -c $seedSql *>> $log

"waitingForWatchdog=15s" | Add-Content $log
Start-Sleep -Seconds 15

$querySql = @"
SELECT id, status, retry_count, max_retries, COALESCE(failure_reason,'') AS failure_reason
FROM route_jobs
WHERE id IN ('$jobRetry'::uuid, '$jobDlq'::uuid)
ORDER BY id;
"@

$rows = docker run --rm -e PGPASSWORD=postgres postgres:15 psql -h host.docker.internal -U postgres -d moodride -t -A -F '|' -c $querySql
$rows | Add-Content $log

$retryRow = $rows | Where-Object { $_ -like "$jobRetry*" } | Select-Object -First 1
$dlqRow = $rows | Where-Object { $_ -like "$jobDlq*" } | Select-Object -First 1

$retryParts = if ($retryRow) { $retryRow.Split('|') } else { @() }
$dlqParts = if ($dlqRow) { $dlqRow.Split('|') } else { @() }

$retryStatus = if ($retryParts.Count -ge 2) { $retryParts[1] } else { '' }
$retryCount = if ($retryParts.Count -ge 3) { [int]$retryParts[2] } else { -1 }
$dlqStatus = if ($dlqParts.Count -ge 2) { $dlqParts[1] } else { '' }
$dlqFailure = if ($dlqParts.Count -ge 5) { $dlqParts[4] } else { '' }

# Allow async producer send to reach Kafka before reading offsets again.
Start-Sleep -Seconds 3

$afterOffsetsRaw = docker exec moodride-kafka bash -lc "kafka-run-class kafka.tools.GetOffsetShell --broker-list localhost:9092 --topic route.jobs.dlq --time -1"
$afterOffset = ($afterOffsetsRaw | ForEach-Object {
    $parts = $_.Split(':')
    if ($parts.Count -ge 3) { [int64]$parts[2] } else { 0 }
} | Measure-Object -Sum).Sum
"dlqOffsetsAfter=$afterOffset" | Add-Content $log
$dlqOffsetDelta = $afterOffset - $beforeOffset
"dlqOffsetDelta=$dlqOffsetDelta" | Add-Content $log

$passRetry = ($retryStatus -eq 'QUEUED' -and $retryCount -eq 1)
$passDlqState = ($dlqStatus -eq 'FAILED' -and $dlqFailure -match 'timed out after retries')
$passDlqTopic = ($dlqOffsetDelta -ge 1)
$passAll = ($passRetry -and $passDlqState -and $passDlqTopic)

"passRetryBranch=$passRetry" | Add-Content $log
"passDlqStateBranch=$passDlqState" | Add-Content $log
"passDlqTopicPublishByOffsetDelta=$passDlqTopic" | Add-Content $log
if ($passAll) {
    'result=PASS' | Add-Content $log
} else {
    'result=FAIL' | Add-Content $log
}

$result = [ordered]@{
    timestampUtc = (Get-Date).ToUniversalTime().ToString('o')
    jobRetry = $jobRetry
    jobDlq = $jobDlq
    retryBranch = [ordered]@{
        expectedStatus = 'QUEUED'
        expectedRetryCount = 1
        actualStatus = $retryStatus
        actualRetryCount = $retryCount
        passed = $passRetry
    }
    dlqBranch = [ordered]@{
        expectedStatus = 'FAILED'
        expectedFailureContains = 'timed out after retries'
        actualStatus = $dlqStatus
        actualFailureReason = $dlqFailure
        dlqOffsetDelta = $dlqOffsetDelta
        dlqTopicPublishObserved = $passDlqTopic
        passedState = $passDlqState
    }
    passed = $passAll
    evidenceLog = $log
}

$result | ConvertTo-Json -Depth 6 | Set-Content $json

Get-Content $log
Get-Content $json
