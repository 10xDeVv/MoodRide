param(
    [Parameter(Mandatory = $true)]
    [string] $BeforeJsonPath,

    [Parameter(Mandatory = $true)]
    [string] $AfterJsonPath,

    [Parameter(Mandatory = $true)]
    [string] $OutputDirectory,

    [double] $GeometryChangedThresholdKm = 0.20
)

$ErrorActionPreference = "Stop"

function Get-NumberOrNull($Value) {
    if ($null -eq $Value) {
        return $null
    }
    return [double] $Value
}

function Get-Delta($AfterValue, $BeforeValue) {
    if ($null -eq $AfterValue -or $null -eq $BeforeValue) {
        return $null
    }
    return [double] $AfterValue - [double] $BeforeValue
}

function Get-ScenarioIndex($Eval) {
    $index = @{}
    foreach ($scenario in $Eval.scenarios) {
        $index[$scenario.scenarioId] = $scenario
    }
    return $index
}

function Get-OptionIndex($Scenario) {
    $index = @{}
    if ($null -eq $Scenario -or $null -eq $Scenario.options) {
        return $index
    }
    foreach ($option in $Scenario.options) {
        $index[$option.profile] = $option
    }
    return $index
}

function Get-DistanceKm($A, $B) {
    $r = 6371.0088
    $lat1 = [math]::PI * [double] $A.lat / 180.0
    $lat2 = [math]::PI * [double] $B.lat / 180.0
    $dLat = [math]::PI * ([double] $B.lat - [double] $A.lat) / 180.0
    $dLng = [math]::PI * ([double] $B.lng - [double] $A.lng) / 180.0
    $h = [math]::Sin($dLat / 2.0) * [math]::Sin($dLat / 2.0) +
        [math]::Cos($lat1) * [math]::Cos($lat2) *
        [math]::Sin($dLng / 2.0) * [math]::Sin($dLng / 2.0)
    return 2.0 * $r * [math]::Asin([math]::Min(1.0, [math]::Sqrt($h)))
}

function Get-SampledCoordinates($Coordinates, [int] $MaxSamples = 60) {
    $coords = @($Coordinates)
    if ($coords.Count -le $MaxSamples) {
        return $coords
    }

    $sampled = New-Object System.Collections.Generic.List[object]
    for ($i = 0; $i -lt $MaxSamples; $i++) {
        $index = [math]::Round($i * ($coords.Count - 1) / ($MaxSamples - 1))
        $sampled.Add($coords[$index])
    }
    return $sampled.ToArray()
}

function Get-AverageNearestKm($FromCoordinates, $ToCoordinates) {
    $from = @(Get-SampledCoordinates $FromCoordinates)
    $to = @(Get-SampledCoordinates $ToCoordinates)
    if ($from.Count -eq 0 -or $to.Count -eq 0) {
        return $null
    }

    $sum = 0.0
    foreach ($a in $from) {
        $minDistance = [double]::PositiveInfinity
        foreach ($b in $to) {
            $distance = Get-DistanceKm $a $b
            if ($distance -lt $minDistance) {
                $minDistance = $distance
            }
        }
        $sum += $minDistance
    }
    return $sum / $from.Count
}

function Get-SymmetricGeometryDistanceKm($BeforeOption, $AfterOption) {
    if ($null -eq $BeforeOption -or $null -eq $AfterOption) {
        return $null
    }
    if ($null -eq $BeforeOption.coordinates -or $null -eq $AfterOption.coordinates) {
        return $null
    }

    $beforeToAfter = Get-AverageNearestKm $BeforeOption.coordinates $AfterOption.coordinates
    $afterToBefore = Get-AverageNearestKm $AfterOption.coordinates $BeforeOption.coordinates
    if ($null -eq $beforeToAfter -or $null -eq $afterToBefore) {
        return $null
    }
    return ($beforeToAfter + $afterToBefore) / 2.0
}

New-Item -ItemType Directory -Path $OutputDirectory -Force | Out-Null

$before = Get-Content -Raw $BeforeJsonPath | ConvertFrom-Json
$after = Get-Content -Raw $AfterJsonPath | ConvertFrom-Json
$beforeIndex = Get-ScenarioIndex $before
$afterIndex = Get-ScenarioIndex $after
$profiles = @("most_scenic", "balanced", "shorter")

$rows = New-Object System.Collections.Generic.List[object]
foreach ($scenarioId in ($beforeIndex.Keys | Sort-Object)) {
    $beforeScenario = $beforeIndex[$scenarioId]
    $afterScenario = $afterIndex[$scenarioId]
    if ($null -eq $afterScenario) {
        continue
    }

    $beforeOptions = Get-OptionIndex $beforeScenario
    $afterOptions = Get-OptionIndex $afterScenario
    foreach ($profile in $profiles) {
        $beforeOption = $beforeOptions[$profile]
        $afterOption = $afterOptions[$profile]
        $geometryDistanceKm = Get-SymmetricGeometryDistanceKm $beforeOption $afterOption
        $rows.Add([pscustomobject] @{
            scenarioId = $scenarioId
            city = $beforeScenario.city
            vibes = (($beforeScenario.vibes | ForEach-Object { $_ }) -join "+")
            profile = $profile
            beforeStatus = $beforeScenario.status
            afterStatus = $afterScenario.status
            beforeRouteId = if ($beforeOption) { $beforeOption.routeId } else { $null }
            afterRouteId = if ($afterOption) { $afterOption.routeId } else { $null }
            beforeDistanceKm = Get-NumberOrNull $beforeOption.distanceKm
            afterDistanceKm = Get-NumberOrNull $afterOption.distanceKm
            distanceDeltaKm = Get-Delta $afterOption.distanceKm $beforeOption.distanceKm
            beforeDurationMinutes = Get-NumberOrNull $beforeOption.durationMinutes
            afterDurationMinutes = Get-NumberOrNull $afterOption.durationMinutes
            durationDeltaMinutes = Get-Delta $afterOption.durationMinutes $beforeOption.durationMinutes
            beforeFinalScore = Get-NumberOrNull $beforeOption.v2FinalScore
            afterFinalScore = Get-NumberOrNull $afterOption.v2FinalScore
            finalScoreDelta = Get-Delta $afterOption.v2FinalScore $beforeOption.v2FinalScore
            beforeScenicPoi = Get-NumberOrNull $beforeOption.v2ScenicPoiScore
            afterScenicPoi = Get-NumberOrNull $afterOption.v2ScenicPoiScore
            scenicPoiDelta = Get-Delta $afterOption.v2ScenicPoiScore $beforeOption.v2ScenicPoiScore
            afterViewpoint = Get-NumberOrNull $afterOption.v2ViewpointScore
            afterBridgeCoastal = Get-NumberOrNull $afterOption.v2BridgeCoastalScore
            beforeWaterShare = Get-NumberOrNull $beforeOption.v2WaterCorridorShare
            afterWaterShare = Get-NumberOrNull $afterOption.v2WaterCorridorShare
            waterShareDelta = Get-Delta $afterOption.v2WaterCorridorShare $beforeOption.v2WaterCorridorShare
            beforePhotoPeak = Get-NumberOrNull $beforeOption.v2PhotoPeakScore
            afterPhotoPeak = Get-NumberOrNull $afterOption.v2PhotoPeakScore
            photoPeakDelta = Get-Delta $afterOption.v2PhotoPeakScore $beforeOption.v2PhotoPeakScore
            beforeBacktracking = Get-NumberOrNull $beforeOption.v2BacktrackingPenalty
            afterBacktracking = Get-NumberOrNull $afterOption.v2BacktrackingPenalty
            backtrackingDelta = Get-Delta $afterOption.v2BacktrackingPenalty $beforeOption.v2BacktrackingPenalty
            geometryDistanceKm = $geometryDistanceKm
            geometryChanged = ($null -ne $geometryDistanceKm -and $geometryDistanceKm -ge $GeometryChangedThresholdKm)
            explanationChanged = ($beforeOption.explanationSummary -ne $afterOption.explanationSummary)
        })
    }
}

$csvPath = Join-Path $OutputDirectory "route-quality-v35-v37-option-comparison.csv"
$rows | Export-Csv -Path $csvPath -NoTypeInformation

$completedBefore = @($before.scenarios | Where-Object { $_.status -eq "COMPLETED" }).Count
$completedAfter = @($after.scenarios | Where-Object { $_.status -eq "COMPLETED" }).Count
$unavailableBefore = @($before.scenarios | Where-Object { $_.status -ne "COMPLETED" }).Count
$unavailableAfter = @($after.scenarios | Where-Object { $_.status -ne "COMPLETED" }).Count

$comparableRows = @($rows | Where-Object { $_.beforeStatus -eq "COMPLETED" -and $_.afterStatus -eq "COMPLETED" })
$geometryChangedRows = @($comparableRows | Where-Object { $_.geometryChanged })
$scoreChangedRows = @($comparableRows | Where-Object { $null -ne $_.finalScoreDelta -and [math]::Abs($_.finalScoreDelta) -ge 0.005 })
$withViewpointRows = @($comparableRows | Where-Object { $null -ne $_.afterViewpoint -and $_.afterViewpoint -gt 0.01 })
$withBridgeCoastalRows = @($comparableRows | Where-Object { $null -ne $_.afterBridgeCoastal -and $_.afterBridgeCoastal -gt 0.01 })
$photoCoastalRows = @($comparableRows | Where-Object { $_.vibes -match "photo|coastal|date|sunset|hidden" })

$avgGeometryDistance = if ($comparableRows.Count -gt 0) {
    ($comparableRows | Measure-Object -Property geometryDistanceKm -Average).Average
} else {
    $null
}
$avgScoreDelta = if ($comparableRows.Count -gt 0) {
    ($comparableRows | Measure-Object -Property finalScoreDelta -Average).Average
} else {
    $null
}
$avgViewpoint = if ($comparableRows.Count -gt 0) {
    ($comparableRows | Measure-Object -Property afterViewpoint -Average).Average
} else {
    $null
}
$avgBridgeCoastal = if ($comparableRows.Count -gt 0) {
    ($comparableRows | Measure-Object -Property afterBridgeCoastal -Average).Average
} else {
    $null
}

$topGeometry = @($comparableRows |
    Sort-Object -Property geometryDistanceKm -Descending |
    Select-Object -First 10 scenarioId, vibes, profile, geometryDistanceKm, distanceDeltaKm, durationDeltaMinutes, finalScoreDelta, afterViewpoint, afterBridgeCoastal)

$topSignal = @($comparableRows |
    Sort-Object -Property afterViewpoint, afterBridgeCoastal -Descending |
    Select-Object -First 12 scenarioId, vibes, profile, finalScoreDelta, afterScenicPoi, afterViewpoint, afterBridgeCoastal, geometryDistanceKm)

$summaryPath = Join-Path $OutputDirectory "route-quality-v35-v37-comparison.md"
$lines = New-Object System.Collections.Generic.List[string]
$lines.Add("# Route Quality v3.5 to v3.7 Comparison")
$lines.Add("")
$lines.Add("Generated: $((Get-Date).ToString("yyyy-MM-dd HH:mm:ss zzz"))")
$lines.Add("")
$lines.Add("Before: ``$BeforeJsonPath``")
$lines.Add("")
$lines.Add("After: ``$AfterJsonPath``")
$lines.Add("")
$lines.Add("## Summary")
$lines.Add("")
$lines.Add("- Scenario count: before $($before.scenarioCount), after $($after.scenarioCount)")
$lines.Add("- Completed routes: before $completedBefore, after $completedAfter")
$lines.Add("- Unavailable routes: before $unavailableBefore, after $unavailableAfter")
$lines.Add("- Comparable completed options: $($comparableRows.Count)")
$lines.Add("- Options with geometry changed >= $GeometryChangedThresholdKm km: $($geometryChangedRows.Count)")
$lines.Add("- Options with final score changed >= 0.005: $($scoreChangedRows.Count)")
$lines.Add("- Options with non-trivial viewpoint signal: $($withViewpointRows.Count)")
$lines.Add("- Options with non-trivial bridge/coastal signal: $($withBridgeCoastalRows.Count)")
$lines.Add("- Average geometry distance: $([math]::Round($avgGeometryDistance, 4)) km")
$lines.Add("- Average final-score delta: $([math]::Round($avgScoreDelta, 5))")
$lines.Add("- Average v3.7 viewpoint score: $([math]::Round($avgViewpoint, 5))")
$lines.Add("- Average v3.7 bridge/coastal score: $([math]::Round($avgBridgeCoastal, 5))")
$lines.Add("")
$lines.Add("## Interpretation")
$lines.Add("")
if ($geometryChangedRows.Count -eq 0) {
    $lines.Add("The saved v3.5 and v3.7 evals selected essentially the same route geometries under this comparison threshold. That means the new data is visible to the scoring/explanation layer, but this eval run does not prove it is strongly changing route selection yet.")
} else {
    $lines.Add("The saved v3.5 and v3.7 evals selected different route geometries for some options, so the new data affected route selection in at least part of the scenario set.")
}
if ($scoreChangedRows.Count -eq 0) {
    $lines.Add("Final route scores stayed effectively flat. If the goal is visible route-choice improvement, the next step is to tune the weights/selection logic so viewpoint and bridge/coastal signals can change candidate ranking when they matter.")
} else {
    $lines.Add("Final route scores changed on some options, which means the scoring surface is responding to the new v3.6/v3.7 signals.")
}
$lines.Add("")
$lines.Add("## Largest Geometry Changes")
$lines.Add("")
if ($topGeometry.Count -eq 0) {
    $lines.Add("No comparable completed routes were available.")
} else {
    $lines.Add("| Scenario | Vibes | Profile | Geometry km | Distance delta km | Duration delta min | Score delta | Viewpoint | Bridge/coastal |")
    $lines.Add("| --- | --- | --- | ---: | ---: | ---: | ---: | ---: | ---: |")
    foreach ($row in $topGeometry) {
        $lines.Add("| $($row.scenarioId) | $($row.vibes) | $($row.profile) | $([math]::Round($row.geometryDistanceKm, 3)) | $([math]::Round($row.distanceDeltaKm, 3)) | $([math]::Round($row.durationDeltaMinutes, 2)) | $([math]::Round($row.finalScoreDelta, 5)) | $([math]::Round($row.afterViewpoint, 3)) | $([math]::Round($row.afterBridgeCoastal, 3)) |")
    }
}
$lines.Add("")
$lines.Add("## Strongest New Signals")
$lines.Add("")
if ($topSignal.Count -eq 0) {
    $lines.Add("No comparable completed routes had new v3.6/v3.7 signal values.")
} else {
    $lines.Add("| Scenario | Vibes | Profile | Score delta | Scenic POI | Viewpoint | Bridge/coastal | Geometry km |")
    $lines.Add("| --- | --- | --- | ---: | ---: | ---: | ---: | ---: |")
    foreach ($row in $topSignal) {
        $lines.Add("| $($row.scenarioId) | $($row.vibes) | $($row.profile) | $([math]::Round($row.finalScoreDelta, 5)) | $([math]::Round($row.afterScenicPoi, 3)) | $([math]::Round($row.afterViewpoint, 3)) | $([math]::Round($row.afterBridgeCoastal, 3)) | $([math]::Round($row.geometryDistanceKm, 3)) |")
    }
}
$lines.Add("")
$lines.Add("## Photo/Coastal/Date Subset")
$lines.Add("")
$lines.Add("- Comparable options in subset: $($photoCoastalRows.Count)")
$lines.Add("- Geometry changes in subset: $(@($photoCoastalRows | Where-Object { $_.geometryChanged }).Count)")
$lines.Add("- Score changes in subset: $(@($photoCoastalRows | Where-Object { $null -ne $_.finalScoreDelta -and [math]::Abs($_.finalScoreDelta) -ge 0.005 }).Count)")
$lines.Add("- Non-trivial viewpoint signal in subset: $(@($photoCoastalRows | Where-Object { $null -ne $_.afterViewpoint -and $_.afterViewpoint -gt 0.01 }).Count)")
$lines.Add("- Non-trivial bridge/coastal signal in subset: $(@($photoCoastalRows | Where-Object { $null -ne $_.afterBridgeCoastal -and $_.afterBridgeCoastal -gt 0.01 }).Count)")

$lines | Set-Content -Path $summaryPath -Encoding UTF8

Write-Host "Wrote $summaryPath"
Write-Host "Wrote $csvPath"
