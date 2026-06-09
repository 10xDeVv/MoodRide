param(
    [string]$BaseUrl = "https://app.moodrides.com",
    [string]$ScenarioFile = "scripts/monitoring/route-quality-scenarios.json",
    [string]$OutputDir = "artifacts/route-quality-eval",
    [string]$RouteMode = "drive",
    [int]$PollIntervalSeconds = 4,
    [int]$JobTimeoutSeconds = 360,
    [int]$MaxScenarios = 0,
    [string[]]$ScenarioIds = @(),
    [switch]$DryRun
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

function Resolve-RepoPath {
    param([Parameter(Mandatory = $true)][string]$Path)

    if ([System.IO.Path]::IsPathRooted($Path)) {
        return $Path
    }
    return Join-Path -Path (Resolve-Path ".").Path -ChildPath $Path
}

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
        TimeoutSec = 45
    }

    if ($null -ne $Body) {
        $args.ContentType = "application/json"
        $args.Body = ($Body | ConvertTo-Json -Depth 16 -Compress)
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
        $reader = $null
        try {
            $reader = New-Object System.IO.StreamReader($webResponse.GetResponseStream())
            $content = $reader.ReadToEnd()
        } finally {
            if ($reader) {
                $reader.Dispose()
            }
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

function Get-Array {
    param([Parameter()][object]$Value)

    if ($null -eq $Value) {
        return @()
    }
    if ($Value -is [array]) {
        return @($Value)
    }
    return @($Value)
}

function Get-PropertyValue {
    param(
        [Parameter()][object]$Object,
        [Parameter(Mandatory = $true)][string]$PropertyName
    )

    if ($null -eq $Object) {
        return $null
    }
    $property = $Object.PSObject.Properties[$PropertyName]
    if ($null -eq $property) {
        return $null
    }
    return $property.Value
}

function Get-Number {
    param([Parameter()][object]$Value)

    if ($null -eq $Value) {
        return $null
    }
    try {
        $number = [double]$Value
        if ([double]::IsNaN($number) -or [double]::IsInfinity($number)) {
            return $null
        }
        return $number
    } catch {
        return $null
    }
}

function Get-MapNumber {
    param(
        [Parameter()][object]$Map,
        [Parameter(Mandatory = $true)][string]$Key
    )

    if ($null -eq $Map) {
        return $null
    }
    $property = $Map.PSObject.Properties[$Key]
    if ($null -eq $property) {
        return $null
    }
    return Get-Number -Value $property.Value
}

function Join-Url {
    param(
        [Parameter(Mandatory = $true)][string]$Root,
        [Parameter(Mandatory = $true)][string]$Path
    )

    return ($Root.TrimEnd("/") + "/" + $Path.TrimStart("/"))
}

function Get-PrimaryRouteId {
    param([Parameter(Mandatory = $true)][object]$JobStatus)

    $routeId = Get-PropertyValue -Object $JobStatus -PropertyName "routeId"
    if ($routeId) {
        return [string]$routeId
    }

    $routeOptions = Get-Array (Get-PropertyValue -Object $JobStatus -PropertyName "routeOptions")
    $mostScenic = $routeOptions | Where-Object { $_.profile -eq "most_scenic" } | Select-Object -First 1
    if ($mostScenic) {
        return [string]$mostScenic.routeId
    }

    $first = $routeOptions | Select-Object -First 1
    if ($first) {
        return [string]$first.routeId
    }
    return $null
}

function Get-HaversineKm {
    param(
        [Parameter(Mandatory = $true)][double]$Lat1,
        [Parameter(Mandatory = $true)][double]$Lng1,
        [Parameter(Mandatory = $true)][double]$Lat2,
        [Parameter(Mandatory = $true)][double]$Lng2
    )

    $earthRadiusKm = 6371.0088
    $latDelta = ($Lat2 - $Lat1) * [Math]::PI / 180.0
    $lngDelta = ($Lng2 - $Lng1) * [Math]::PI / 180.0
    $lat1Rad = $Lat1 * [Math]::PI / 180.0
    $lat2Rad = $Lat2 * [Math]::PI / 180.0
    $a = [Math]::Pow([Math]::Sin($latDelta / 2.0), 2.0) +
        ([Math]::Cos($lat1Rad) * [Math]::Cos($lat2Rad) * [Math]::Pow([Math]::Sin($lngDelta / 2.0), 2.0))
    $c = 2.0 * [Math]::Atan2([Math]::Sqrt($a), [Math]::Sqrt(1.0 - $a))
    return $earthRadiusKm * $c
}

function Get-RouteCoordinates {
    param([Parameter()][object]$RouteDetail)

    if ($null -eq $RouteDetail -or $null -eq $RouteDetail.geometry) {
        return @()
    }
    $geometry = Get-PropertyValue -Object $RouteDetail.geometry -PropertyName "geometry"
    if ($null -eq $geometry) {
        return @()
    }
    $coordinates = Get-Array (Get-PropertyValue -Object $geometry -PropertyName "coordinates")
    $points = @()
    foreach ($coordinate in $coordinates) {
        $pair = Get-Array $coordinate
        if ($pair.Count -lt 2) {
            continue
        }
        $lng = Get-Number -Value $pair[0]
        $lat = Get-Number -Value $pair[1]
        if ($null -ne $lat -and $null -ne $lng) {
            $points += [pscustomobject]@{ lat = $lat; lng = $lng }
        }
    }
    return $points
}

function Select-SampledPoints {
    param(
        [Parameter()][object[]]$Points,
        [int]$MaxPoints = 80
    )

    $source = @($Points)
    if ($source.Count -le $MaxPoints) {
        return $source
    }

    $sampled = @()
    $lastIndex = $source.Count - 1
    for ($i = 0; $i -lt $MaxPoints; $i++) {
        $index = [int][Math]::Round(($i * $lastIndex) / [Math]::Max(1, ($MaxPoints - 1)))
        $sampled += $source[$index]
    }
    return $sampled
}

function Get-AverageNearestDistanceKm {
    param(
        [Parameter()][object[]]$FromPoints,
        [Parameter()][object[]]$ToPoints
    )

    $from = Select-SampledPoints -Points $FromPoints
    $to = Select-SampledPoints -Points $ToPoints
    if ($from.Count -eq 0 -or $to.Count -eq 0) {
        return $null
    }

    $sum = 0.0
    foreach ($point in $from) {
        $minDistance = [double]::PositiveInfinity
        foreach ($candidate in $to) {
            $distance = Get-HaversineKm -Lat1 $point.lat -Lng1 $point.lng -Lat2 $candidate.lat -Lng2 $candidate.lng
            if ($distance -lt $minDistance) {
                $minDistance = $distance
            }
        }
        if (-not [double]::IsInfinity($minDistance)) {
            $sum += $minDistance
        }
    }
    return $sum / [Math]::Max(1, $from.Count)
}

function Get-PairwiseGeometryStats {
    param([Parameter()][object[]]$OptionDetails)

    $items = @($OptionDetails)
    $withGeometry = @($items | Where-Object { $_.coordinates -and @($_.coordinates).Count -gt 1 })
    if ($withGeometry.Count -lt 2) {
        return [ordered]@{
            pairCount = 0
            avgSeparationKm = $null
            minSeparationKm = $null
            maxSeparationKm = $null
        }
    }

    $pairDistances = @()
    for ($i = 0; $i -lt $withGeometry.Count; $i++) {
        for ($j = $i + 1; $j -lt $withGeometry.Count; $j++) {
            $forward = Get-AverageNearestDistanceKm -FromPoints $withGeometry[$i].coordinates -ToPoints $withGeometry[$j].coordinates
            $reverse = Get-AverageNearestDistanceKm -FromPoints $withGeometry[$j].coordinates -ToPoints $withGeometry[$i].coordinates
            if ($null -ne $forward -and $null -ne $reverse) {
                $pairDistances += (($forward + $reverse) / 2.0)
            }
        }
    }

    if ($pairDistances.Count -eq 0) {
        return [ordered]@{
            pairCount = 0
            avgSeparationKm = $null
            minSeparationKm = $null
            maxSeparationKm = $null
        }
    }

    return [ordered]@{
        pairCount = $pairDistances.Count
        avgSeparationKm = ($pairDistances | Measure-Object -Average).Average
        minSeparationKm = ($pairDistances | Measure-Object -Minimum).Minimum
        maxSeparationKm = ($pairDistances | Measure-Object -Maximum).Maximum
    }
}

function Get-TargetComponentsForVibes {
    param([Parameter()][string[]]$Vibes)

    $targets = New-Object System.Collections.Generic.List[string]
    foreach ($vibe in $Vibes) {
        switch ($vibe) {
            "coastal" {
                $targets.Add("water")
                break
            }
            "mountain" {
                $targets.Add("elevation")
                $targets.Add("curves")
                break
            }
            "riverside" {
                $targets.Add("water")
                $targets.Add("greenery")
                break
            }
            { $_ -in @("forest", "nature_escape", "nature") } {
                $targets.Add("greenery")
                $targets.Add("solitude")
                break
            }
            "open_roads" {
                $targets.Add("open_space")
                $targets.Add("solitude")
                break
            }
            { $_ -in @("countryside", "country", "quiet", "relaxing", "sunday_cruise", "sunday", "smooth_cruise", "cruise", "minimal_traffic", "low_traffic", "clear_my_head") } {
                $targets.Add("solitude")
                $targets.Add("greenery")
                break
            }
            { $_ -in @("winding_roads", "winding", "adventure") } {
                $targets.Add("curves")
                $targets.Add("elevation")
                break
            }
            { $_ -in @("sunset", "sunrise", "golden_hour") } {
                $targets.Add("water")
                $targets.Add("elevation")
                break
            }
            "date_night" {
                $targets.Add("water")
                $targets.Add("elevation")
                $targets.Add("poi")
                break
            }
            { $_ -in @("photo_worthy", "photo_run", "photo") } {
                $targets.Add("water")
                $targets.Add("elevation")
                $targets.Add("poi")
                break
            }
            "hidden_gems" {
                $targets.Add("solitude")
                $targets.Add("curves")
                $targets.Add("poi")
                break
            }
            { $_ -in @("loop_variety", "scenic", "scenic_reset") } {
                $targets.Add("water")
                $targets.Add("greenery")
                $targets.Add("elevation")
                $targets.Add("solitude")
                $targets.Add("curves")
                break
            }
            default {
                $targets.Add("greenery")
                $targets.Add("solitude")
                break
            }
        }
    }

    return @($targets | Select-Object -Unique)
}

function Get-TopMapEntry {
    param([Parameter()][object]$Map)

    if ($null -eq $Map) {
        return [ordered]@{ key = $null; value = $null }
    }

    $entries = @()
    foreach ($property in $Map.PSObject.Properties) {
        $number = Get-Number -Value $property.Value
        if ($null -ne $number) {
            $entries += [pscustomobject]@{ key = $property.Name; value = $number }
        }
    }

    $top = $entries | Sort-Object -Property value -Descending | Select-Object -First 1
    if ($null -eq $top) {
        return [ordered]@{ key = $null; value = $null }
    }
    return [ordered]@{ key = $top.key; value = $top.value }
}

function Get-TargetSignal {
    param(
        [Parameter()][object]$Explanation,
        [Parameter()][string[]]$TargetComponents
    )

    $componentAverages = Get-PropertyValue -Object $Explanation -PropertyName "componentAverages"
    $componentLifts = Get-PropertyValue -Object $Explanation -PropertyName "componentLifts"
    $weightedContributions = Get-PropertyValue -Object $Explanation -PropertyName "weightedContributions"

    $averages = @()
    $lifts = @()
    $contributions = @()
    foreach ($target in $TargetComponents) {
        $average = Get-MapNumber -Map $componentAverages -Key $target
        $lift = Get-MapNumber -Map $componentLifts -Key $target
        $contribution = Get-MapNumber -Map $weightedContributions -Key $target
        if ($null -ne $average) { $averages += $average }
        if ($null -ne $lift) { $lifts += $lift }
        if ($null -ne $contribution) { $contributions += $contribution }
    }

    return [ordered]@{
        targetAverage = if ($averages.Count -gt 0) { ($averages | Measure-Object -Average).Average } else { $null }
        targetLift = if ($lifts.Count -gt 0) { ($lifts | Measure-Object -Average).Average } else { $null }
        targetContribution = if ($contributions.Count -gt 0) { ($contributions | Measure-Object -Average).Average } else { $null }
    }
}

function Get-BudgetFlags {
    param(
        [int]$BudgetMinutes,
        [Parameter()][object[]]$OptionDetails
    )

    $overBudget = @()
    $underBudget = @()
    $maxAllowed = $BudgetMinutes + [Math]::Max(5.0, $BudgetMinutes * 0.15)
    $minExpected = [Math]::Max(10.0, $BudgetMinutes * 0.55)

    foreach ($option in @($OptionDetails)) {
        if ($null -eq $option.durationMinutes) {
            continue
        }
        if ($option.durationMinutes -gt $maxAllowed) {
            $overBudget += $option.profile
        }
        if ($option.durationMinutes -lt $minExpected) {
            $underBudget += $option.profile
        }
    }

    return [ordered]@{
        overBudgetProfiles = $overBudget
        underBudgetProfiles = $underBudget
        maxAllowedMinutes = $maxAllowed
        minExpectedMinutes = $minExpected
    }
}

function Get-ScenarioFlags {
    param(
        [string]$Status,
        [Parameter()][string]$FailureReason,
        [int]$BudgetMinutes,
        [Parameter()][object[]]$OptionDetails,
        [Parameter()][object]$DiversityStats,
        [Parameter()][object]$BudgetStats,
        [Parameter()][string[]]$TargetComponents
    )

    $flags = New-Object System.Collections.Generic.List[string]

    if ($Status -ne "COMPLETED") {
        if ($FailureReason -and $FailureReason.StartsWith("No strong ")) {
            $flags.Add("vibe_unavailable")
            return @($flags)
        }
        if ($FailureReason -and $FailureReason.StartsWith("No scenic data found")) {
            $flags.Add("scenic_data_unavailable")
            return @($flags)
        }
        $flags.Add("job_not_completed")
        return @($flags)
    }
    $items = @($OptionDetails)
    if ($items.Count -eq 0) {
        $flags.Add("no_route_options")
        return @($flags)
    }
    if ($items.Count -lt 3) {
        $flags.Add("fewer_than_three_options")
    }
    if (@($BudgetStats.overBudgetProfiles).Count -gt 0) {
        $flags.Add("over_budget:" + (@($BudgetStats.overBudgetProfiles) -join "+"))
    }
    if (@($BudgetStats.underBudgetProfiles).Count -gt 0) {
        $flags.Add("under_budget:" + (@($BudgetStats.underBudgetProfiles) -join "+"))
    }

    $scores = @($items | ForEach-Object { $_.scenicScore } | Where-Object { $null -ne $_ })
    $durations = @($items | ForEach-Object { $_.durationMinutes } | Where-Object { $null -ne $_ })
    if ($scores.Count -ge 2) {
        $scoreSpread = (($scores | Measure-Object -Maximum).Maximum - ($scores | Measure-Object -Minimum).Minimum)
        $maxScore = ($scores | Measure-Object -Maximum).Maximum
        $lowScoreSpreadThreshold = if ($maxScore -gt 1.5) { 2.5 } else { 0.025 }
        if ($scoreSpread -lt $lowScoreSpreadThreshold) {
            $flags.Add("low_scenic_spread")
        }
    }
    if ($durations.Count -ge 2) {
        $durationSpread = (($durations | Measure-Object -Maximum).Maximum - ($durations | Measure-Object -Minimum).Minimum)
        if ($durationSpread -lt [Math]::Max(4.0, $BudgetMinutes * 0.08)) {
            $flags.Add("low_duration_spread")
        }
    }
    if ($null -ne $DiversityStats.minSeparationKm -and $DiversityStats.minSeparationKm -lt 0.35) {
        $flags.Add("low_geometry_separation")
    }

    $missingExplanations = @($items | Where-Object { -not $_.hasExplanation }).Count
    if ($missingExplanations -gt 0) {
        $flags.Add("missing_explanations")
    }
    $missingV2Breakdowns = @($items | Where-Object { $null -eq $_.v2FinalScore }).Count
    if ($missingV2Breakdowns -gt 0) {
        $flags.Add("missing_v2_breakdown")
    }
    $unexpectedAlgorithms = @($items | Where-Object { $_.algorithmVersion -and $_.algorithmVersion -ne "hybrid_osrm_v2" })
    if ($unexpectedAlgorithms.Count -gt 0) {
        $flags.Add("unexpected_algorithm:" + (@($unexpectedAlgorithms | ForEach-Object { $_.algorithmVersion } | Select-Object -Unique) -join "+"))
    }
    $weakStrategyFits = @($items | Where-Object { $null -ne $_.v2StrategyFitScore -and $_.v2StrategyFitScore -lt 0.30 })
    if ($weakStrategyFits.Count -gt 0) {
        $flags.Add("weak_strategy_fit:" + (@($weakStrategyFits | ForEach-Object { $_.profile } | Select-Object -Unique) -join "+"))
    }
    $highStrategyPenalties = @($items | Where-Object { $null -ne $_.v2StrategyMismatchPenalty -and $_.v2StrategyMismatchPenalty -gt 0.50 })
    if ($highStrategyPenalties.Count -gt 0) {
        $flags.Add("strategy_mismatch:" + (@($highStrategyPenalties | ForEach-Object { $_.profile } | Select-Object -Unique) -join "+"))
    }

    $leadingKeys = @($items | ForEach-Object { $_.leadingComponentKey } | Where-Object { $_ } | Select-Object -Unique)
    if ($leadingKeys.Count -eq 1 -and $items.Count -gt 1) {
        $flags.Add("same_leading_component:" + $leadingKeys[0])
    }

    $primary = $items | Where-Object { $_.profile -eq "most_scenic" } | Select-Object -First 1
    if ($null -eq $primary) {
        $primary = $items | Select-Object -First 1
    }
    if ($primary) {
        $weakAverage = ($null -ne $primary.targetAverage -and $primary.targetAverage -lt 0.22)
        $weakContribution = ($null -ne $primary.targetContribution -and $primary.targetContribution -lt 0.08)
        $flatLift = ($null -ne $primary.targetLift -and [Math]::Abs($primary.targetLift) -lt 0.03)
        if ($weakAverage -or ($weakContribution -and $flatLift)) {
            $flags.Add("weak_vibe_signal:" + ($TargetComponents -join "+"))
        }
    }

    return @($flags)
}

$scenarioPath = Resolve-RepoPath -Path $ScenarioFile
if (-not (Test-Path -LiteralPath $scenarioPath)) {
    throw "Scenario file does not exist: $scenarioPath"
}

$scenarios = Get-Content -LiteralPath $scenarioPath -Raw | ConvertFrom-Json
$selectedScenarios = @($scenarios)
if ($ScenarioIds.Count -gt 0) {
    $wanted = @{}
    foreach ($id in $ScenarioIds) {
        $wanted[$id] = $true
    }
    $selectedScenarios = @($selectedScenarios | Where-Object { $wanted.ContainsKey([string]$_.id) })
}
if ($MaxScenarios -gt 0) {
    $selectedScenarios = @($selectedScenarios | Select-Object -First $MaxScenarios)
}

if ($selectedScenarios.Count -eq 0) {
    throw "No scenarios selected."
}

if ($DryRun) {
    Write-Host "Route quality eval dry run"
    Write-Host "BaseUrl: $BaseUrl"
    Write-Host "Scenario file: $scenarioPath"
    Write-Host "Selected scenarios: $($selectedScenarios.Count)"
    foreach ($scenario in $selectedScenarios) {
        Write-Host ("- {0}: {1}, {2} budget={3} vibes={4}" -f $scenario.id, $scenario.city, $scenario.region, $scenario.timeBudgetMinutes, ((Get-Array $scenario.vibes) -join ","))
    }
    exit 0
}

$runStartedAt = Get-Date
$runId = $runStartedAt.ToString("yyyyMMdd-HHmmss")
$resolvedOutputDir = Resolve-RepoPath -Path $OutputDir
New-Item -ItemType Directory -Force -Path $resolvedOutputDir | Out-Null

$scenarioResults = @()
$csvRows = @()

Write-Host "Running route quality eval"
Write-Host "BaseUrl: $BaseUrl"
Write-Host "Scenarios: $($selectedScenarios.Count)"
Write-Host "OutputDir: $resolvedOutputDir"

foreach ($scenario in $selectedScenarios) {
    $scenarioId = [string]$scenario.id
    $city = [string]$scenario.city
    $region = [string]$scenario.region
    $lat = [double]$scenario.lat
    $lng = [double]$scenario.lng
    $timeBudgetMinutes = [int]$scenario.timeBudgetMinutes
    $vibes = @(Get-Array $scenario.vibes | ForEach-Object { [string]$_ })
    $targetComponents = Get-TargetComponentsForVibes -Vibes $vibes

    Write-Host ""
    Write-Host ("==> {0} [{1}] budget={2} vibes={3}" -f $scenarioId, $city, $timeBudgetMinutes, ($vibes -join ","))

    $jobId = $null
    $jobStatus = $null
    $routeOptions = @()
    $optionDetails = @()
    $finalStatus = "UNKNOWN"
    $failureReason = $null
    $startedSubmitAt = Get-Date

    try {
        $submitBody = [ordered]@{
            userId = [guid]::NewGuid()
            lat = $lat
            lng = $lng
            timeBudgetMinutes = $timeBudgetMinutes
            routeMode = $RouteMode
            vibes = $vibes
        }

        $submission = Invoke-ApiJson -Method "POST" -Uri (Join-Url -Root $BaseUrl -Path "/api/routes") -Body $submitBody
        $jobId = [string]$submission.jobId
        $deadline = (Get-Date).AddSeconds($JobTimeoutSeconds)

        do {
            Start-Sleep -Seconds $PollIntervalSeconds
            $jobStatus = Invoke-ApiJson -Method "GET" -Uri (Join-Url -Root $BaseUrl -Path "/api/routes/$jobId")
            $status = [string]$jobStatus.status
            Write-Host ("    status={0}" -f $status)
            if ($status -in @("COMPLETED", "FAILED", "TIMEOUT")) {
                break
            }
        } while ((Get-Date) -lt $deadline)

        $finalStatus = if ($jobStatus) { [string]$jobStatus.status } else { "UNKNOWN" }
        if ((Get-Date) -ge $deadline -and $finalStatus -notin @("COMPLETED", "FAILED", "TIMEOUT")) {
            $finalStatus = "TIMEOUT"
        }
        if ($jobStatus -and $jobStatus.reason) {
            $failureReason = [string]$jobStatus.reason
        }

        if ($finalStatus -eq "COMPLETED") {
            $primaryRouteId = Get-PrimaryRouteId -JobStatus $jobStatus
            if ($primaryRouteId) {
                $primaryRoute = Invoke-ApiJson -Method "GET" -Uri (Join-Url -Root $BaseUrl -Path "/api/routes/route/$primaryRouteId")
                $routeOptions = @(Get-Array (Get-PropertyValue -Object $primaryRoute -PropertyName "routeOptions"))
                if ($routeOptions.Count -eq 0) {
                    $routeOptions = @(Get-Array (Get-PropertyValue -Object $jobStatus -PropertyName "routeOptions"))
                }

                foreach ($option in $routeOptions) {
                    $optionRouteId = [string]$option.routeId
                    $detail = $null
                    if ($optionRouteId) {
                        $detail = Invoke-ApiJson -Method "GET" -Uri (Join-Url -Root $BaseUrl -Path "/api/routes/route/$optionRouteId")
                    }
                    $explanation = Get-PropertyValue -Object $option -PropertyName "explanation"
                    if ($null -eq $explanation -and $detail -and $detail.routeOptions) {
                        $detailOption = @(Get-Array $detail.routeOptions | Where-Object { [string]$_.routeId -eq $optionRouteId } | Select-Object -First 1)
                        if ($detailOption.Count -gt 0) {
                            $explanation = $detailOption[0].explanation
                        }
                    }
                    $leadingComponents = @(Get-Array (Get-PropertyValue -Object $explanation -PropertyName "leadingComponents") | ForEach-Object { [string]$_ })
                    $topContribution = Get-TopMapEntry -Map (Get-PropertyValue -Object $explanation -PropertyName "weightedContributions")
                    $targetSignal = Get-TargetSignal -Explanation $explanation -TargetComponents $targetComponents
                    $coordinates = Get-RouteCoordinates -RouteDetail $detail
                    $duration = Get-Number -Value (Get-PropertyValue -Object $option -PropertyName "estimatedDurationMinutes")
                    $distance = Get-Number -Value (Get-PropertyValue -Object $option -PropertyName "totalDistanceKm")
                    $score = Get-Number -Value (Get-PropertyValue -Object $option -PropertyName "scenicScore")
                    $scoreBreakdown = Get-PropertyValue -Object $option -PropertyName "scoreBreakdown"
                    if ($null -eq $scoreBreakdown -and $detail) {
                        $scoreBreakdown = Get-PropertyValue -Object $detail -PropertyName "scoreBreakdown"
                    }

                    $optionDetails += [pscustomobject]@{
                        profile = [string]$option.profile
                        routeId = $optionRouteId
                        scenicScore = $score
                        distanceKm = $distance
                        durationMinutes = $duration
                        budgetDeltaMinutes = if ($null -ne $duration) { $duration - $timeBudgetMinutes } else { $null }
                        budgetDeltaPct = if ($null -ne $duration -and $timeBudgetMinutes -gt 0) { ($duration - $timeBudgetMinutes) / $timeBudgetMinutes } else { $null }
                        qualityTier = if ($detail) { Get-PropertyValue -Object $detail -PropertyName "qualityTier" } else { $null }
                        algorithmVersion = if ($detail) { Get-PropertyValue -Object $detail -PropertyName "algorithmVersion" } else { $null }
                        computationTimeMs = if ($detail) { Get-Number -Value (Get-PropertyValue -Object $detail -PropertyName "computationTimeMs") } else { $null }
                        scoreBreakdown = $scoreBreakdown
                        v2FinalScore = Get-MapNumber -Map $scoreBreakdown -Key "final_score"
                        v2LandscapeScore = Get-MapNumber -Map $scoreBreakdown -Key "landscape_score"
                        v2VibeFitScore = Get-MapNumber -Map $scoreBreakdown -Key "vibe_fit_score"
                        v2DriveQualityScore = Get-MapNumber -Map $scoreBreakdown -Key "drive_quality_score"
                        v2RouteShapeScore = Get-MapNumber -Map $scoreBreakdown -Key "route_shape_score"
                        v2ScenicMomentsScore = Get-MapNumber -Map $scoreBreakdown -Key "scenic_moments_score"
                        v2UrbanPenalty = Get-MapNumber -Map $scoreBreakdown -Key "urban_penalty"
                        v2StartEndPenalty = Get-MapNumber -Map $scoreBreakdown -Key "start_end_penalty"
                        v2CorridorTileSamples = Get-MapNumber -Map $scoreBreakdown -Key "corridor_tile_samples"
                        v2GeometryStrategyCode = Get-MapNumber -Map $scoreBreakdown -Key "geometry_strategy_code"
                        v2StrategyFitScore = Get-MapNumber -Map $scoreBreakdown -Key "strategy_fit_score"
                        v2StrategyMismatchPenalty = Get-MapNumber -Map $scoreBreakdown -Key "strategy_mismatch_penalty"
                        v2WaterCorridorShare = Get-MapNumber -Map $scoreBreakdown -Key "water_corridor_share"
                        v2OpenSpaceCorridorShare = Get-MapNumber -Map $scoreBreakdown -Key "open_space_corridor_share"
                        v2QuietCorridorShare = Get-MapNumber -Map $scoreBreakdown -Key "quiet_corridor_share"
                        v2PhotoPeakScore = Get-MapNumber -Map $scoreBreakdown -Key "photo_peak_score"
                        v2CurveElevationCorridorShare = Get-MapNumber -Map $scoreBreakdown -Key "curve_elevation_corridor_share"
                        v2RequestedAvgRadiusKm = Get-MapNumber -Map $scoreBreakdown -Key "requested_avg_radius_km"
                        v2RequestedWaypointCount = Get-MapNumber -Map $scoreBreakdown -Key "requested_waypoint_count"
                        v2DurationFitRatio = Get-MapNumber -Map $scoreBreakdown -Key "duration_fit_ratio"
                        v2DurationCalibrationBucketMinutes = Get-MapNumber -Map $scoreBreakdown -Key "duration_calibration_bucket_minutes"
                        coordinates = $coordinates
                        coordinateCount = @($coordinates).Count
                        hasExplanation = ($null -ne $explanation)
                        explanationSummary = if ($explanation) { Get-PropertyValue -Object $explanation -PropertyName "summary" } else { $null }
                        leadingComponents = $leadingComponents
                        leadingComponentKey = if ($leadingComponents.Count -gt 0) { $leadingComponents[0] } else { $null }
                        topContributionComponent = $topContribution.key
                        topContribution = $topContribution.value
                        targetAverage = $targetSignal.targetAverage
                        targetLift = $targetSignal.targetLift
                        targetContribution = $targetSignal.targetContribution
                        sampleTileCount = if ($explanation) { Get-Number -Value (Get-PropertyValue -Object $explanation -PropertyName "sampleTileCount") } else { $null }
                        baselineTileCount = if ($explanation) { Get-Number -Value (Get-PropertyValue -Object $explanation -PropertyName "baselineTileCount") } else { $null }
                    }
                }
            }
        }
    } catch {
        $finalStatus = "ERROR"
        $failureReason = $_.Exception.Message
        Write-Warning ("Scenario {0} failed: {1}" -f $scenarioId, $failureReason)
    }

    $durations = @($optionDetails | ForEach-Object { $_.durationMinutes } | Where-Object { $null -ne $_ })
    $distances = @($optionDetails | ForEach-Object { $_.distanceKm } | Where-Object { $null -ne $_ })
    $scores = @($optionDetails | ForEach-Object { $_.scenicScore } | Where-Object { $null -ne $_ })
    $diversityStats = Get-PairwiseGeometryStats -OptionDetails $optionDetails
    $budgetStats = Get-BudgetFlags -BudgetMinutes $timeBudgetMinutes -OptionDetails $optionDetails
    $flags = Get-ScenarioFlags -Status $finalStatus -FailureReason $failureReason -BudgetMinutes $timeBudgetMinutes -OptionDetails $optionDetails -DiversityStats $diversityStats -BudgetStats $budgetStats -TargetComponents $targetComponents

    $scoreSpread = if ($scores.Count -ge 2) { (($scores | Measure-Object -Maximum).Maximum - ($scores | Measure-Object -Minimum).Minimum) } else { $null }
    $durationSpread = if ($durations.Count -ge 2) { (($durations | Measure-Object -Maximum).Maximum - ($durations | Measure-Object -Minimum).Minimum) } else { $null }
    $distanceSpread = if ($distances.Count -ge 2) { (($distances | Measure-Object -Maximum).Maximum - ($distances | Measure-Object -Minimum).Minimum) } else { $null }
    $routeCount = $optionDetails.Count
    $elapsedSeconds = [Math]::Round(((Get-Date) - $startedSubmitAt).TotalSeconds, 2)

    $scenarioResult = [ordered]@{
        runId = $runId
        scenarioId = $scenarioId
        city = $city
        region = $region
        lat = $lat
        lng = $lng
        routeMode = $RouteMode
        timeBudgetMinutes = $timeBudgetMinutes
        vibes = $vibes
        targetComponents = $targetComponents
        status = $finalStatus
        jobId = $jobId
        routeCount = $routeCount
        scoreSpread = $scoreSpread
        durationSpreadMinutes = $durationSpread
        distanceSpreadKm = $distanceSpread
        geometryDiversity = $diversityStats
        budgetStats = $budgetStats
        flags = $flags
        failureReason = $failureReason
        elapsedSeconds = $elapsedSeconds
        options = $optionDetails
    }
    $scenarioResults += $scenarioResult

    Write-Host ("    completed status={0} routes={1} flags={2}" -f $finalStatus, $routeCount, (($flags -join ",") -replace "^$", "none"))

    if ($optionDetails.Count -eq 0) {
        $csvRows += [pscustomobject]@{
            runId = $runId
            scenarioId = $scenarioId
            city = $city
            region = $region
            lat = $lat
            lng = $lng
            routeMode = $RouteMode
            timeBudgetMinutes = $timeBudgetMinutes
            vibes = ($vibes -join "+")
            status = $finalStatus
            jobId = $jobId
            routeCount = 0
            profile = $null
            routeId = $null
            durationMinutes = $null
            distanceKm = $null
            scenicScore = $null
            budgetDeltaMinutes = $null
            budgetDeltaPct = $null
            qualityTier = $null
            algorithmVersion = $null
            computationTimeMs = $null
            v2FinalScore = $null
            v2LandscapeScore = $null
            v2VibeFitScore = $null
            v2DriveQualityScore = $null
            v2RouteShapeScore = $null
            v2ScenicMomentsScore = $null
            v2UrbanPenalty = $null
            v2StartEndPenalty = $null
            v2CorridorTileSamples = $null
            v2GeometryStrategyCode = $null
            v2StrategyFitScore = $null
            v2StrategyMismatchPenalty = $null
            v2WaterCorridorShare = $null
            v2OpenSpaceCorridorShare = $null
            v2QuietCorridorShare = $null
            v2PhotoPeakScore = $null
            v2CurveElevationCorridorShare = $null
            v2RequestedAvgRadiusKm = $null
            v2RequestedWaypointCount = $null
            v2DurationFitRatio = $null
            v2DurationCalibrationBucketMinutes = $null
            targetComponents = ($targetComponents -join "+")
            targetAverage = $null
            targetLift = $null
            targetContribution = $null
            leadingComponents = $null
            topContributionComponent = $null
            topContribution = $null
            sampleTileCount = $null
            baselineTileCount = $null
            coordinateCount = $null
            scoreSpread = $scoreSpread
            durationSpreadMinutes = $durationSpread
            distanceSpreadKm = $distanceSpread
            avgGeometrySeparationKm = $diversityStats.avgSeparationKm
            minGeometrySeparationKm = $diversityStats.minSeparationKm
            flags = ($flags -join ";")
            failureReason = $failureReason
        }
    } else {
        foreach ($option in $optionDetails) {
            $csvRows += [pscustomobject]@{
                runId = $runId
                scenarioId = $scenarioId
                city = $city
                region = $region
                lat = $lat
                lng = $lng
                routeMode = $RouteMode
                timeBudgetMinutes = $timeBudgetMinutes
                vibes = ($vibes -join "+")
                status = $finalStatus
                jobId = $jobId
                routeCount = $routeCount
                profile = $option.profile
                routeId = $option.routeId
                durationMinutes = $option.durationMinutes
                distanceKm = $option.distanceKm
                scenicScore = $option.scenicScore
                budgetDeltaMinutes = $option.budgetDeltaMinutes
                budgetDeltaPct = $option.budgetDeltaPct
                qualityTier = $option.qualityTier
                algorithmVersion = $option.algorithmVersion
                computationTimeMs = $option.computationTimeMs
                v2FinalScore = $option.v2FinalScore
                v2LandscapeScore = $option.v2LandscapeScore
                v2VibeFitScore = $option.v2VibeFitScore
                v2DriveQualityScore = $option.v2DriveQualityScore
                v2RouteShapeScore = $option.v2RouteShapeScore
                v2ScenicMomentsScore = $option.v2ScenicMomentsScore
                v2UrbanPenalty = $option.v2UrbanPenalty
                v2StartEndPenalty = $option.v2StartEndPenalty
                v2CorridorTileSamples = $option.v2CorridorTileSamples
                v2GeometryStrategyCode = $option.v2GeometryStrategyCode
                v2StrategyFitScore = $option.v2StrategyFitScore
                v2StrategyMismatchPenalty = $option.v2StrategyMismatchPenalty
                v2WaterCorridorShare = $option.v2WaterCorridorShare
                v2OpenSpaceCorridorShare = $option.v2OpenSpaceCorridorShare
                v2QuietCorridorShare = $option.v2QuietCorridorShare
                v2PhotoPeakScore = $option.v2PhotoPeakScore
                v2CurveElevationCorridorShare = $option.v2CurveElevationCorridorShare
                v2RequestedAvgRadiusKm = $option.v2RequestedAvgRadiusKm
                v2RequestedWaypointCount = $option.v2RequestedWaypointCount
                v2DurationFitRatio = $option.v2DurationFitRatio
                v2DurationCalibrationBucketMinutes = $option.v2DurationCalibrationBucketMinutes
                targetComponents = ($targetComponents -join "+")
                targetAverage = $option.targetAverage
                targetLift = $option.targetLift
                targetContribution = $option.targetContribution
                leadingComponents = ($option.leadingComponents -join "+")
                topContributionComponent = $option.topContributionComponent
                topContribution = $option.topContribution
                sampleTileCount = $option.sampleTileCount
                baselineTileCount = $option.baselineTileCount
                coordinateCount = $option.coordinateCount
                scoreSpread = $scoreSpread
                durationSpreadMinutes = $durationSpread
                distanceSpreadKm = $distanceSpread
                avgGeometrySeparationKm = $diversityStats.avgSeparationKm
                minGeometrySeparationKm = $diversityStats.minSeparationKm
                flags = ($flags -join ";")
                failureReason = $failureReason
            }
        }
    }
}

$completedCount = @($scenarioResults | Where-Object { $_.status -eq "COMPLETED" }).Count
$flagCounts = @{}
foreach ($result in $scenarioResults) {
    foreach ($flag in @($result.flags)) {
        if (-not $flagCounts.ContainsKey($flag)) {
            $flagCounts[$flag] = 0
        }
        $flagCounts[$flag] += 1
    }
}

$summary = [ordered]@{
    runId = $runId
    baseUrl = $BaseUrl
    routeMode = $RouteMode
    scenarioFile = $scenarioPath
    startedAtUtc = $runStartedAt.ToUniversalTime().ToString("o")
    finishedAtUtc = (Get-Date).ToUniversalTime().ToString("o")
    scenarioCount = $scenarioResults.Count
    completedCount = $completedCount
    nonCompletedCount = $scenarioResults.Count - $completedCount
    flagCounts = $flagCounts
    scenarios = $scenarioResults
}

$jsonPath = Join-Path -Path $resolvedOutputDir -ChildPath "route-quality-eval-$runId.json"
$csvPath = Join-Path -Path $resolvedOutputDir -ChildPath "route-quality-eval-$runId.csv"
$summaryPath = Join-Path -Path $resolvedOutputDir -ChildPath "route-quality-eval-$runId.md"

$summary | ConvertTo-Json -Depth 18 | Set-Content -LiteralPath $jsonPath -Encoding UTF8
$csvRows | Export-Csv -LiteralPath $csvPath -NoTypeInformation -Encoding UTF8

$lines = @()
$lines += "# Route Quality Eval - $runId"
$lines += ""
$lines += "- Base URL: $BaseUrl"
$lines += "- Route mode: $RouteMode"
$lines += "- Scenarios: $($scenarioResults.Count)"
$lines += "- Completed: $completedCount"
$lines += "- Non-completed: $($scenarioResults.Count - $completedCount)"
$lines += ""
$lines += "## Flags"
if ($flagCounts.Count -eq 0) {
    $lines += "- none"
} else {
    foreach ($key in ($flagCounts.Keys | Sort-Object)) {
        $lines += "- ${key}: $($flagCounts[$key])"
    }
}
$lines += ""
$lines += "## Scenario Summary"
$lines += ""
$lines += "| Scenario | City | Budget | Vibes | Status | Routes | Algorithm | Avg Strategy | Avg Strategy Fit | Avg Strategy Penalty | Avg Req Radius | Avg Req Wpts | Score Spread | Duration Spread | Min Geometry Sep | Avg V2 Final | Avg Urban Penalty | Flags |"
$lines += "|---|---|---:|---|---|---:|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---|"
foreach ($result in $scenarioResults) {
    $spread = if ($null -ne $result.scoreSpread) { "{0:N4}" -f [double]$result.scoreSpread } else { "n/a" }
    $durationSpreadLabel = if ($null -ne $result.durationSpreadMinutes) { "{0:N1}" -f [double]$result.durationSpreadMinutes } else { "n/a" }
    $minSep = if ($null -ne $result.geometryDiversity.minSeparationKm) { "{0:N2}" -f [double]$result.geometryDiversity.minSeparationKm } else { "n/a" }
    $flagLabel = if (@($result.flags).Count -gt 0) { @($result.flags) -join ", " } else { "none" }
    $algorithms = @($result.options | ForEach-Object { $_.algorithmVersion } | Where-Object { $_ } | Select-Object -Unique)
    $algorithmLabel = if ($algorithms.Count -gt 0) { $algorithms -join "+" } else { "n/a" }
    $v2FinalScores = @($result.options | ForEach-Object { $_.v2FinalScore } | Where-Object { $null -ne $_ })
    $v2UrbanPenalties = @($result.options | ForEach-Object { $_.v2UrbanPenalty } | Where-Object { $null -ne $_ })
    $v2StrategyCodes = @($result.options | ForEach-Object { $_.v2GeometryStrategyCode } | Where-Object { $null -ne $_ })
    $v2StrategyFits = @($result.options | ForEach-Object { $_.v2StrategyFitScore } | Where-Object { $null -ne $_ })
    $v2StrategyPenalties = @($result.options | ForEach-Object { $_.v2StrategyMismatchPenalty } | Where-Object { $null -ne $_ })
    $v2RequestedRadii = @($result.options | ForEach-Object { $_.v2RequestedAvgRadiusKm } | Where-Object { $null -ne $_ })
    $v2RequestedWaypointCounts = @($result.options | ForEach-Object { $_.v2RequestedWaypointCount } | Where-Object { $null -ne $_ })
    $avgStrategyCode = if ($v2StrategyCodes.Count -gt 0) { "{0:N1}" -f [double](($v2StrategyCodes | Measure-Object -Average).Average) } else { "n/a" }
    $avgStrategyFit = if ($v2StrategyFits.Count -gt 0) { "{0:N4}" -f [double](($v2StrategyFits | Measure-Object -Average).Average) } else { "n/a" }
    $avgStrategyPenalty = if ($v2StrategyPenalties.Count -gt 0) { "{0:N4}" -f [double](($v2StrategyPenalties | Measure-Object -Average).Average) } else { "n/a" }
    $avgRequestedRadius = if ($v2RequestedRadii.Count -gt 0) { "{0:N2}" -f [double](($v2RequestedRadii | Measure-Object -Average).Average) } else { "n/a" }
    $avgRequestedWaypointCount = if ($v2RequestedWaypointCounts.Count -gt 0) { "{0:N1}" -f [double](($v2RequestedWaypointCounts | Measure-Object -Average).Average) } else { "n/a" }
    $avgV2Final = if ($v2FinalScores.Count -gt 0) { "{0:N4}" -f [double](($v2FinalScores | Measure-Object -Average).Average) } else { "n/a" }
    $avgUrbanPenalty = if ($v2UrbanPenalties.Count -gt 0) { "{0:N4}" -f [double](($v2UrbanPenalties | Measure-Object -Average).Average) } else { "n/a" }
    $lines += "| $($result.scenarioId) | $($result.city) | $($result.timeBudgetMinutes) | $(@($result.vibes) -join '+') | $($result.status) | $($result.routeCount) | $algorithmLabel | $avgStrategyCode | $avgStrategyFit | $avgStrategyPenalty | $avgRequestedRadius | $avgRequestedWaypointCount | $spread | $durationSpreadLabel | $minSep | $avgV2Final | $avgUrbanPenalty | $flagLabel |"
}
$lines += ""
$lines += "## Output Files"
$lines += ""
$lines += "- JSON: $jsonPath"
$lines += "- CSV: $csvPath"

$lines | Set-Content -LiteralPath $summaryPath -Encoding UTF8

Write-Host ""
Write-Host "Route quality eval complete."
Write-Host "JSON: $jsonPath"
Write-Host "CSV: $csvPath"
Write-Host "Markdown: $summaryPath"
