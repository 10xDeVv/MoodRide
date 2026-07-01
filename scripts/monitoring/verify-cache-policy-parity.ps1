param(
    [string]$WorkspaceRoot = "C:\Users\aadeb\OneDrive\Desktop\Wayward",
    [switch]$EmitJson
)

$ErrorActionPreference = "Stop"

function Read-Text([string]$path) {
    if (-not (Test-Path -Path $path -PathType Leaf)) {
        throw "Required file missing: $path"
    }
    return Get-Content -Path $path -Raw
}

$routeApiKeySchemaPath = Join-Path $WorkspaceRoot "services\route-api\src\main\java\com\moodride\routeapi\cache\CacheKeySchema.java"
$routeWorkerKeySchemaPath = Join-Path $WorkspaceRoot "services\route-worker\src\main\java\com\moodride\routeworker\cache\CacheKeySchema.java"
$routeApiPolicyPath = Join-Path $WorkspaceRoot "services\route-api\src\main\java\com\moodride\routeapi\cache\CachePolicy.java"
$routeWorkerPolicyPath = Join-Path $WorkspaceRoot "services\route-worker\src\main\java\com\moodride\routeworker\cache\CachePolicy.java"
$routeApiConfigPath = Join-Path $WorkspaceRoot "services\route-api\src\main\java\com\moodride\routeapi\config\CacheConfig.java"
$routeWorkerConfigPath = Join-Path $WorkspaceRoot "services\route-worker\src\main\java\com\moodride\routeworker\config\CacheConfig.java"

$routeApiKeySchema = Read-Text $routeApiKeySchemaPath
$routeWorkerKeySchema = Read-Text $routeWorkerKeySchemaPath
$routeApiPolicy = Read-Text $routeApiPolicyPath
$routeWorkerPolicy = Read-Text $routeWorkerPolicyPath
$routeApiConfig = Read-Text $routeApiConfigPath
$routeWorkerConfig = Read-Text $routeWorkerConfigPath

$expectedKeyMethods = @("routeResult", "scenicTile", "segmentMeta", "regionalPopularity")
$expectedPrefixes = @("route:result:", "scenic:tile:", "segment:meta:", "popular:routes:")
$expectedPolicyConstants = @("ROUTE_RESULTS_TTL", "SCENIC_TILES_TTL", "ROAD_SEGMENTS_TTL", "REGIONAL_POPULARITY_TTL")

function Contains-All([string]$text, [string[]]$needles) {
    foreach ($needle in $needles) {
        if ($text -notmatch [Regex]::Escape($needle)) {
            return $false
        }
    }
    return $true
}

$routeApiKeysOk = (Contains-All $routeApiKeySchema $expectedKeyMethods) -and (Contains-All $routeApiKeySchema $expectedPrefixes)
$routeWorkerKeysOk = (Contains-All $routeWorkerKeySchema $expectedKeyMethods) -and (Contains-All $routeWorkerKeySchema $expectedPrefixes)
$routeApiPolicyOk = Contains-All $routeApiPolicy $expectedPolicyConstants
$routeWorkerPolicyOk = Contains-All $routeWorkerPolicy $expectedPolicyConstants
$routeApiConfigOk = Contains-All $routeApiConfig @("CachePolicy.ROUTE_RESULTS_TTL", "CachePolicy.SCENIC_TILES_TTL", "CachePolicy.ROAD_SEGMENTS_TTL", "CachePolicy.REGIONAL_POPULARITY_TTL")
$routeWorkerConfigOk = Contains-All $routeWorkerConfig @("CachePolicy.ROUTE_RESULTS_TTL", "CachePolicy.SCENIC_TILES_TTL", "CachePolicy.ROAD_SEGMENTS_TTL", "CachePolicy.REGIONAL_POPULARITY_TTL")

$passed = $routeApiKeysOk -and $routeWorkerKeysOk -and $routeApiPolicyOk -and $routeWorkerPolicyOk -and $routeApiConfigOk -and $routeWorkerConfigOk

$result = [PSCustomObject]@{
    checkedAt = (Get-Date).ToString("o")
    passed = $passed
    routeApi = [PSCustomObject]@{
        keySchema = $routeApiKeysOk
        policy = $routeApiPolicyOk
        configUsesPolicy = $routeApiConfigOk
    }
    routeWorker = [PSCustomObject]@{
        keySchema = $routeWorkerKeysOk
        policy = $routeWorkerPolicyOk
        configUsesPolicy = $routeWorkerConfigOk
    }
}

if ($EmitJson) {
    $result | ConvertTo-Json -Depth 5
} else {
    $result | Format-List
}

if (-not $passed) {
    throw "Cache policy parity verification failed."
}

