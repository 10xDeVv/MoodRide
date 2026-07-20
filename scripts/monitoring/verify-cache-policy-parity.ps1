param(
    [string]$WorkspaceRoot = "",
    [switch]$EmitJson
)

$ErrorActionPreference = "Stop"

if ([string]::IsNullOrWhiteSpace($WorkspaceRoot)) {
    $WorkspaceRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot "..\.."))
}

function Read-Text([string]$path) {
    if (-not (Test-Path -Path $path -PathType Leaf)) {
        throw "Required file missing: $path"
    }
    return Get-Content -Path $path -Raw
}

function Get-JavaStringConstant([string]$text, [string]$name) {
    $pattern = '(?m)\bpublic\s+static\s+final\s+String\s+' + [Regex]::Escape($name) + '\s*=\s*"([^"]+)"\s*;'
    $match = [Regex]::Match($text, $pattern)
    if (-not $match.Success) {
        return $null
    }
    return $match.Groups[1].Value
}

function Get-KeyPrefix([string]$text, [string]$method) {
    $pattern = '(?s)\bpublic\s+static\s+String\s+' + [Regex]::Escape($method) + '\s*\([^)]*\)\s*\{\s*return\s+"([^"]+)"\s*\+'
    $match = [Regex]::Match($text, $pattern)
    if (-not $match.Success) {
        return $null
    }
    return $match.Groups[1].Value
}

function Get-DurationConstant([string]$text, [string]$name) {
    $pattern = '(?m)\bpublic\s+static\s+final\s+Duration\s+' + [Regex]::Escape($name) + '\s*=\s*(Duration\.of(?:Days|Hours|Minutes|Seconds)\(\s*\d+\s*\))\s*;'
    $match = [Regex]::Match($text, $pattern)
    if (-not $match.Success) {
        return $null
    }
    return ($match.Groups[1].Value -replace '\s', '')
}

function Test-ConfigPolicy([string]$text, [string]$cacheName, [string]$policyName) {
    $pattern = '(?s)cacheConfigurations\.put\(\s*CacheNames\.' + [Regex]::Escape($cacheName) + '\s*,\s*defaultConfig\.entryTtl\(\s*CachePolicy\.' + [Regex]::Escape($policyName) + '\s*\)\s*\)'
    return [Regex]::IsMatch($text, $pattern)
}

$routeApiRoot = Join-Path $WorkspaceRoot "services\route-api\src\main\java\com\moodride\routeapi"
$routeWorkerRoot = Join-Path $WorkspaceRoot "services\route-worker\src\main\java\com\moodride\routeworker"

$routeApiKeySchema = Read-Text (Join-Path $routeApiRoot "cache\CacheKeySchema.java")
$routeWorkerKeySchema = Read-Text (Join-Path $routeWorkerRoot "cache\CacheKeySchema.java")
$routeApiNames = Read-Text (Join-Path $routeApiRoot "cache\CacheNames.java")
$routeWorkerNames = Read-Text (Join-Path $routeWorkerRoot "cache\CacheNames.java")
$routeApiPolicy = Read-Text (Join-Path $routeApiRoot "cache\CachePolicy.java")
$routeWorkerPolicy = Read-Text (Join-Path $routeWorkerRoot "cache\CachePolicy.java")
$routeApiConfig = Read-Text (Join-Path $routeApiRoot "config\CacheConfig.java")
$routeWorkerConfig = Read-Text (Join-Path $routeWorkerRoot "config\CacheConfig.java")

$expectedSharedContracts = @(
    [PSCustomObject]@{
        name = "scenicTiles"
        cacheConstant = "SCENIC_TILES"
        keyMethod = "scenicTile"
        physicalPrefix = "scenicTiles::scenic:tile:"
        ttlConstant = "SCENIC_TILES_TTL"
        ttlExpression = "Duration.ofDays(8)"
    },
    [PSCustomObject]@{
        name = "segmentMetadata"
        cacheConstant = "ROAD_SEGMENTS"
        keyMethod = "segmentMeta"
        physicalPrefix = "roadSegments::segment:meta:"
        ttlConstant = "ROAD_SEGMENTS_TTL"
        ttlExpression = "Duration.ofDays(7)"
    },
    [PSCustomObject]@{
        name = "regionalPopularity"
        cacheConstant = "REGIONAL_POPULARITY"
        keyMethod = "regionalPopularity"
        physicalPrefix = "regionalPopularity::popular:routes:"
        ttlConstant = "REGIONAL_POPULARITY_TTL"
        ttlExpression = "Duration.ofHours(24)"
    }
)

$sharedContracts = @()
foreach ($expected in $expectedSharedContracts) {
    $apiPhysicalPrefix = (Get-JavaStringConstant $routeApiNames $expected.cacheConstant) + "::" + (Get-KeyPrefix $routeApiKeySchema $expected.keyMethod)
    $workerPhysicalPrefix = (Get-JavaStringConstant $routeWorkerNames $expected.cacheConstant) + "::" + (Get-KeyPrefix $routeWorkerKeySchema $expected.keyMethod)
    $apiTtl = Get-DurationConstant $routeApiPolicy $expected.ttlConstant
    $workerTtl = Get-DurationConstant $routeWorkerPolicy $expected.ttlConstant
    $apiConfig = Test-ConfigPolicy $routeApiConfig $expected.cacheConstant $expected.ttlConstant
    $workerConfig = Test-ConfigPolicy $routeWorkerConfig $expected.cacheConstant $expected.ttlConstant
    $prefixMatches = $apiPhysicalPrefix -eq $expected.physicalPrefix -and $workerPhysicalPrefix -eq $expected.physicalPrefix
    $ttlMatches = $apiTtl -eq $expected.ttlExpression -and $workerTtl -eq $expected.ttlExpression

    $sharedContracts += [PSCustomObject]@{
        name = $expected.name
        passed = $prefixMatches -and $ttlMatches -and $apiConfig -and $workerConfig
        expectedPhysicalPrefix = $expected.physicalPrefix
        apiPhysicalPrefix = $apiPhysicalPrefix
        workerPhysicalPrefix = $workerPhysicalPrefix
        expectedTtl = $expected.ttlExpression
        apiTtl = $apiTtl
        workerTtl = $workerTtl
        apiConfigUsesPolicy = $apiConfig
        workerConfigUsesPolicy = $workerConfig
    }
}

$apiRoutePhysicalPrefix = (Get-JavaStringConstant $routeApiNames "ROUTE_DETAILS_V2") + "::" + (Get-KeyPrefix $routeApiKeySchema "routeDetailV2")
$apiRouteTtl = Get-DurationConstant $routeApiPolicy "ROUTE_DETAILS_V2_TTL"
$apiRouteConfig = Test-ConfigPolicy $routeApiConfig "ROUTE_DETAILS_V2" "ROUTE_DETAILS_V2_TTL"
$apiRoutePassed = $apiRoutePhysicalPrefix -eq "routeDetailsV2::route:detail:v2:" -and $apiRouteTtl -eq "Duration.ofHours(24)" -and $apiRouteConfig

$workerRoutePhysicalPrefix = (Get-JavaStringConstant $routeWorkerNames "ROUTE_RESULTS") + "::" + (Get-KeyPrefix $routeWorkerKeySchema "routeResult")
$workerRouteTtl = Get-DurationConstant $routeWorkerPolicy "ROUTE_RESULTS_TTL"
$workerRouteConfig = Test-ConfigPolicy $routeWorkerConfig "ROUTE_RESULTS" "ROUTE_RESULTS_TTL"
$workerRoutePassed = $workerRoutePhysicalPrefix -eq "routeResults::route:result:" -and $workerRouteTtl -eq "Duration.ofHours(24)" -and $workerRouteConfig

$sharedPassed = @($sharedContracts | Where-Object { -not $_.passed }).Count -eq 0
$passed = $sharedPassed -and $apiRoutePassed -and $workerRoutePassed

$result = [PSCustomObject]@{
    checkedAt = (Get-Date).ToString("o")
    passed = $passed
    sharedContracts = $sharedContracts
    routeContracts = [PSCustomObject]@{
        routeApi = [PSCustomObject]@{
            passed = $apiRoutePassed
            expectedPhysicalPrefix = "routeDetailsV2::route:detail:v2:"
            physicalPrefix = $apiRoutePhysicalPrefix
            expectedTtl = "Duration.ofHours(24)"
            ttl = $apiRouteTtl
            configUsesPolicy = $apiRouteConfig
        }
        routeWorker = [PSCustomObject]@{
            passed = $workerRoutePassed
            expectedPhysicalPrefix = "routeResults::route:result:"
            physicalPrefix = $workerRoutePhysicalPrefix
            expectedTtl = "Duration.ofHours(24)"
            ttl = $workerRouteTtl
            configUsesPolicy = $workerRouteConfig
        }
    }
}

if ($EmitJson) {
    $result | ConvertTo-Json -Depth 6
} else {
    $result | Format-List
}

if (-not $passed) {
    throw "Cache policy parity verification failed."
}
