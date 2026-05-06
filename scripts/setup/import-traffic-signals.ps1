param(
    [Parameter(Mandatory = $true)]
    [string]$CsvPath,

    [string]$Database = "moodride",
    [string]$Username = "postgres",
    [string]$DbHost = "localhost",
    [int]$Port = 5432,
    [string]$Password,

    [string]$Provider = "external-csv",

    [string]$ScenicApiBaseUrl = "http://localhost:8085/api/internal/scenic",
    [string]$VerificationOutputPath
)

$ErrorActionPreference = "Stop"

if (-not (Test-Path -Path $CsvPath -PathType Leaf)) {
    throw "CSV file not found: $CsvPath"
}

$psql = Get-Command psql -ErrorAction SilentlyContinue
if (-not $psql) {
    throw "psql is not installed or not on PATH. Install PostgreSQL client tools and retry."
}

if ($Password) {
    $env:PGPASSWORD = $Password
}

try {
    $rows = Import-Csv -Path $CsvPath
    if (-not $rows -or $rows.Count -eq 0) {
        throw "CSV contains no rows."
    }

    Write-Host "Importing traffic signal rows into traffic_tile_signals..."

    $changedTiles = New-Object System.Collections.Generic.HashSet[string]

    foreach ($row in $rows) {
        $h3 = [string]$row.h3_index
        $scoreText = [string]$row.traffic_score

        if ([string]::IsNullOrWhiteSpace($h3)) {
            continue
        }

        [double]$score = 0.5
        if (-not [double]::TryParse($scoreText, [ref]$score)) {
            $score = 0.5
        }
        if ($score -lt 0) { $score = 0.0 }
        if ($score -gt 1) { $score = 1.0 }

        $sql = @"
INSERT INTO traffic_tile_signals (h3_index, traffic_score, provider, last_updated)
VALUES ('$h3', $score, '$Provider', CURRENT_TIMESTAMP)
ON CONFLICT (h3_index) DO UPDATE SET
    traffic_score = EXCLUDED.traffic_score,
    provider = EXCLUDED.provider,
    last_updated = EXCLUDED.last_updated;
"@

        & $psql.Source -h $DbHost -p $Port -U $Username -d $Database -v ON_ERROR_STOP=1 -c $sql | Out-Null
        if ($LASTEXITCODE -ne 0) {
            throw "Failed to upsert traffic signal for h3_index=$h3"
        }

        [void]$changedTiles.Add($h3)
    }

    & $psql.Source -h $DbHost -p $Port -U $Username -d $Database -c "SELECT provider, COUNT(*) AS rows, ROUND(AVG(traffic_score)::numeric, 4) AS avg_traffic_score FROM traffic_tile_signals GROUP BY provider ORDER BY rows DESC;"

    $providerSummary = @()
    $summarySql = "SELECT provider, COUNT(*) AS rows, ROUND(AVG(traffic_score)::numeric, 6) AS avg_score FROM traffic_tile_signals GROUP BY provider ORDER BY rows DESC;"
    & $psql.Source -h $DbHost -p $Port -U $Username -d $Database -t -A -F "|" -c $summarySql | ForEach-Object {
        $line = $_.Trim()
        if ([string]::IsNullOrWhiteSpace($line)) {
            return
        }
        $parts = $line -split "\|"
        if ($parts.Count -lt 3) {
            return
        }
        $providerSummary += [PSCustomObject]@{
            provider = $parts[0]
            rows = [int64]$parts[1]
            avgTrafficScore = [double]$parts[2]
        }
    }

    if ($changedTiles.Count -gt 0) {
        $payload = @{
            source = "traffic-csv-import"
            h3Indexes = @($changedTiles.ToArray())
        } | ConvertTo-Json -Depth 4

        $refreshUrl = "$ScenicApiBaseUrl/traffic-refresh-events"
        $refreshResponse = Invoke-RestMethod -Method Post -Uri $refreshUrl -ContentType "application/json" -Body $payload
        Write-Host "Published Kafka scenic refresh event. eventId=$($refreshResponse.eventId) tileCount=$($refreshResponse.tileCount)"
    }

    if ($VerificationOutputPath) {
        $verification = [PSCustomObject]@{
            importedProvider = $Provider
            changedTileCount = $changedTiles.Count
            providerSummary = $providerSummary
            generatedAt = (Get-Date).ToString("o")
        }

        $verificationDir = Split-Path -Path $VerificationOutputPath -Parent
        if (-not [string]::IsNullOrWhiteSpace($verificationDir)) {
            New-Item -ItemType Directory -Path $verificationDir -Force | Out-Null
        }

        $verification | ConvertTo-Json -Depth 6 | Set-Content -Path $VerificationOutputPath -Encoding UTF8
        Write-Host "Wrote verification artifact: $VerificationOutputPath"
    }

    Write-Host "Traffic signal import completed."
}
finally {
    if ($Password) {
        Remove-Item Env:PGPASSWORD -ErrorAction SilentlyContinue
    }
}

