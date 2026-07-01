param(
    [string]$BaseUrl = "https://usewayward.app",
    [int]$TimeBudgetMinutes = 90,
    [int]$PollIntervalSeconds = 4,
    [int]$JobTimeoutSeconds = 300,
    [string]$OutputDir = "artifacts/release-qa"
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

function Invoke-ApiJson {
    param(
        [Parameter(Mandatory = $true)][string]$Method,
        [Parameter(Mandatory = $true)][string]$Uri,
        [Parameter()][object]$Body
    )

    $args = @{
        Method = $Method
        Uri = $Uri
        UseBasicParsing = $true
        Headers = @{ "Accept" = "application/json" }
    }

    if ($null -ne $Body) {
        $args.ContentType = "application/json"
        $args.Body = ($Body | ConvertTo-Json -Depth 10 -Compress)
    }

    $statusCode = 0
    $content = $null
    try {
        $response = Invoke-WebRequest @args
        $statusCode = [int]$response.StatusCode
        $content = [string]$response.Content
    } catch {
        $webResponse = $_.Exception.Response
        if ($null -eq $webResponse) {
            throw
        }
        $statusCode = [int]$webResponse.StatusCode
        try {
            $reader = New-Object System.IO.StreamReader($webResponse.GetResponseStream())
            $content = $reader.ReadToEnd()
        } finally {
            if ($reader) { $reader.Dispose() }
        }
    }

    if ($statusCode -lt 200 -or $statusCode -ge 300) {
        $payload = if ($content) { $content } else { "<empty response>" }
        throw "HTTP $statusCode for $Method $Uri :: $payload"
    }
    if (-not $content) {
        return $null
    }
    return $content | ConvertFrom-Json
}

function Get-PrimaryRouteId {
    param([Parameter(Mandatory = $true)][object]$JobStatus)

    if ($JobStatus.routeId) {
        return [string]$JobStatus.routeId
    }
    if (-not $JobStatus.routeOptions) {
        return $null
    }

    $mostScenic = $JobStatus.routeOptions | Where-Object { $_.profile -eq "most_scenic" } | Select-Object -First 1
    if ($mostScenic) {
        return [string]$mostScenic.routeId
    }
    $first = $JobStatus.routeOptions | Select-Object -First 1
    if ($first) {
        return [string]$first.routeId
    }
    return $null
}

function Try-GetNumericProperty {
    param(
        [Parameter(Mandatory = $true)][object]$Object,
        [Parameter(Mandatory = $true)][string]$PropertyName
    )
    if ($null -eq $Object) {
        return $null
    }
    $prop = $Object.PSObject.Properties[$PropertyName]
    if ($null -eq $prop -or $null -eq $prop.Value) {
        return $null
    }
    try {
        return [double]$prop.Value
    } catch {
        return $null
    }
}

$regions = @(
    @{
        id = "ontario-toronto"
        label = "Ontario (Toronto)"
        lat = 43.6532
        lng = -79.3832
    },
    @{
        id = "ontario-ottawa-gatineau"
        label = "Ontario/Quebec (Ottawa-Gatineau)"
        lat = 45.4215
        lng = -75.6972
    },
    @{
        id = "bc-vancouver"
        label = "British Columbia (Vancouver)"
        lat = 49.2827
        lng = -123.1207
    },
    @{
        id = "alberta-banff"
        label = "Alberta (Banff/Rockies)"
        lat = 51.1784
        lng = -115.5708
    },
    @{
        id = "saskatchewan-regina"
        label = "Saskatchewan (Regina/Prairie)"
        lat = 50.4452
        lng = -104.6189
    },
    @{
        id = "maritimes-fredericton"
        label = "Maritimes (Fredericton)"
        lat = 45.9636
        lng = -66.6431
    }
)

$vibeProfiles = @(
    @{
        id = "countryside"
        vibes = @("countryside")
        preferenceVector = @{ water = 0.4; greenery = 0.7; elevation = 0.45; solitude = 0.7; curves = 0.6; poi = 0.3 }
    },
    @{
        id = "coastal"
        vibes = @("coastal")
        preferenceVector = @{ water = 0.9; greenery = 0.7; elevation = 0.3; solitude = 0.6; curves = 0.45; poi = 0.2 }
    },
    @{
        id = "mountain"
        vibes = @("mountain")
        preferenceVector = @{ water = 0.2; greenery = 0.55; elevation = 0.9; solitude = 0.7; curves = 0.8; poi = 0.2 }
    },
    @{
        id = "forest"
        vibes = @("forest")
        preferenceVector = @{ water = 0.3; greenery = 0.9; elevation = 0.45; solitude = 0.8; curves = 0.45; poi = 0.2 }
    }
)

$startedAt = Get-Date
$runId = $startedAt.ToString("yyyyMMdd-HHmmss")
$resolvedOutputDir = (Join-Path (Resolve-Path ".").Path $OutputDir)
New-Item -ItemType Directory -Force -Path $resolvedOutputDir | Out-Null

$results = @()

foreach ($region in $regions) {
    Write-Host ""
    Write-Host "==> Scenic regions check: $($region.label)"

    $scenicUri = "$BaseUrl/api/scenic-regions?lat=$($region.lat)&lng=$($region.lng)&radiusKm=50&limit=10"
    $scenic = Invoke-ApiJson -Method "GET" -Uri $scenicUri

    $regionScores = @($scenic.regions | ForEach-Object {
        $scenicScore = Try-GetNumericProperty -Object $_ -PropertyName "scenicScore"
        if ($null -ne $scenicScore) {
            $scenicScore
        } else {
            Try-GetNumericProperty -Object $_ -PropertyName "compositeScore"
        }
    } | Where-Object { $null -ne $_ })
    $scenicSummary = @{
        count = $regionScores.Count
        min = if ($regionScores.Count -gt 0) { ($regionScores | Measure-Object -Minimum).Minimum } else { $null }
        max = if ($regionScores.Count -gt 0) { ($regionScores | Measure-Object -Maximum).Maximum } else { $null }
        avg = if ($regionScores.Count -gt 0) { ($regionScores | Measure-Object -Average).Average } else { $null }
    }

    foreach ($profile in $vibeProfiles) {
        $profileId = [string]$profile.id
        Write-Host "==> Route job: $($region.id) [$profileId]"

        $submitBody = @{
            userId = [guid]::NewGuid()
            lat = $region.lat
            lng = $region.lng
            timeBudgetMinutes = $TimeBudgetMinutes
            vibes = $profile.vibes
            preferenceVector = $profile.preferenceVector
        }
        $submission = Invoke-ApiJson -Method "POST" -Uri "$BaseUrl/api/routes" -Body $submitBody

        $jobId = [string]$submission.jobId
        $deadline = (Get-Date).AddSeconds($JobTimeoutSeconds)
        $jobStatus = $null
        do {
            Start-Sleep -Seconds $PollIntervalSeconds
            $jobStatus = Invoke-ApiJson -Method "GET" -Uri "$BaseUrl/api/routes/$jobId"
            $status = [string]$jobStatus.status
            if ($status -in @("COMPLETED", "FAILED", "TIMEOUT")) {
                break
            }
        } while ((Get-Date) -lt $deadline)

        $finalStatus = if ($jobStatus) { [string]$jobStatus.status } else { "UNKNOWN" }
        if ((Get-Date) -ge $deadline -and $finalStatus -notin @("COMPLETED", "FAILED", "TIMEOUT")) {
            $finalStatus = "TIMEOUT"
        }

        $routeId = $null
        $routeDetail = $null
        $routeOptions = @()
        if ($finalStatus -eq "COMPLETED") {
            $routeId = Get-PrimaryRouteId -JobStatus $jobStatus
            if ($routeId) {
                $routeDetail = Invoke-ApiJson -Method "GET" -Uri "$BaseUrl/api/routes/route/$routeId"
                if ($routeDetail.routeOptions) {
                    $routeOptions = @($routeDetail.routeOptions)
                } elseif ($jobStatus.routeOptions) {
                    $routeOptions = @($jobStatus.routeOptions)
                }
            }
        }

        $optionScores = @($routeOptions | ForEach-Object { [double]$_.scenicScore })
        $scoreSpread = if ($optionScores.Count -ge 2) {
            ($optionScores | Measure-Object -Maximum).Maximum - ($optionScores | Measure-Object -Minimum).Minimum
        } else {
            $null
        }
        $optionExplanationCount = @($routeOptions | Where-Object {
            $_.explanation -and $_.explanation.componentAverages -and $_.explanation.leadingComponents
        }).Count
        $leadingComponents = @($routeOptions | ForEach-Object {
            if ($_.explanation -and $_.explanation.leadingComponents) {
                ($_.explanation.leadingComponents -join "+")
            }
        } | Where-Object { $_ })

        $results += [ordered]@{
            regionId = $region.id
            regionLabel = $region.label
            lat = $region.lat
            lng = $region.lng
            vibes = $profile.vibes
            preferenceVector = $profile.preferenceVector
            scenicTop10 = $scenicSummary
            jobId = $jobId
            status = $finalStatus
            routeId = $routeId
            routeOptions = $routeOptions
            scoreSpread = $scoreSpread
            optionExplanationCount = $optionExplanationCount
            leadingComponents = $leadingComponents
            failureReason = if ($jobStatus -and $jobStatus.reason) { [string]$jobStatus.reason } else { $null }
            checkedAtUtc = (Get-Date).ToUniversalTime().ToString("o")
        }
    }
}

$completed = @($results | Where-Object { $_.status -eq "COMPLETED" })
$failed = @($results | Where-Object { $_.status -ne "COMPLETED" })

$summary = [ordered]@{
    runId = $runId
    startedAtUtc = $startedAt.ToUniversalTime().ToString("o")
    finishedAtUtc = (Get-Date).ToUniversalTime().ToString("o")
    baseUrl = $BaseUrl
    scenarioCount = $results.Count
    completedCount = $completed.Count
    nonCompletedCount = $failed.Count
    results = $results
}

$jsonPath = Join-Path $resolvedOutputDir "release-qa-$runId.json"
$mdPath = Join-Path $resolvedOutputDir "release-qa-$runId.md"

$summary | ConvertTo-Json -Depth 12 | Set-Content -LiteralPath $jsonPath -Encoding UTF8

$lines = @()
$lines += "# Release QA Baseline - $runId"
$lines += ""
$lines += "- Base URL: $BaseUrl"
$lines += "- Scenarios: $($results.Count)"
$lines += "- Completed: $($completed.Count)"
$lines += "- Non-completed: $($failed.Count)"
$lines += ""
$lines += "| Region | Vibes | Status | Job ID | Route Options | Explanations | Score Spread |"
$lines += "|---|---|---|---|---:|---:|---:|"
foreach ($item in $results) {
    $vibesLabel = ($item.vibes -join ",")
    $optionCount = if ($item.routeOptions) { @($item.routeOptions).Count } else { 0 }
    $explanationCount = if ($null -ne $item.optionExplanationCount) { [int]$item.optionExplanationCount } else { 0 }
    $spreadLabel = if ($null -ne $item.scoreSpread) { "{0:N4}" -f [double]$item.scoreSpread } else { "n/a" }
    $lines += "| $($item.regionLabel) | $vibesLabel | $($item.status) | $($item.jobId) | $optionCount | $explanationCount | $spreadLabel |"
}
$lines += ""

if ($failed.Count -gt 0) {
    $lines += "## Failures"
    foreach ($item in $failed) {
        $lines += "- $($item.regionLabel) [$($item.vibes -join ',')]: status=$($item.status) reason=$($item.failureReason)"
    }
}

$lines | Set-Content -LiteralPath $mdPath -Encoding UTF8

Write-Host ""
Write-Host "QA baseline complete."
Write-Host "JSON: $jsonPath"
Write-Host "Markdown: $mdPath"
