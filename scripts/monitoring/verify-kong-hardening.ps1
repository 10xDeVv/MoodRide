param(
    [string]$WorkspaceRoot = "C:\Users\aadeb\OneDrive\Desktop\MoodRide",
    [switch]$EmitJson
)

$ErrorActionPreference = "Stop"

function Read-ContentStrict([string]$path) {
    if (-not (Test-Path -Path $path -PathType Leaf)) {
        throw "Required file missing: $path"
    }
    Get-Content -Path $path -Raw
}

function Has-All([string]$text, [string[]]$needles) {
    foreach ($needle in $needles) {
        if ($text -notmatch [Regex]::Escape($needle)) {
            return $false
        }
    }
    return $true
}

$devKong = Read-ContentStrict (Join-Path $WorkspaceRoot "infrastructure\docker\kong\kong.yml")
$prodKong = Read-ContentStrict (Join-Path $WorkspaceRoot "infrastructure\docker\kong\kong.prod.yml")
$k8sKongConfig = Read-ContentStrict (Join-Path $WorkspaceRoot "infrastructure\k8s\shared\kong-config.configmap.yaml")
$compose = Read-ContentStrict (Join-Path $WorkspaceRoot "infrastructure\docker\docker-compose.yml")

$devChecks = [PSCustomObject]@{
    permissiveHeader = $devKong -match "x-auth-policy:dev-permissive"
    jwtAbsent = -not ($devKong -match "name:\s*jwt")
    correlationIdPresent = $devKong -match "name:\s*correlation-id"
    rateLimitingPresent = $devKong -match "name:\s*rate-limiting"
    otelPresent = $devKong -match "name:\s*opentelemetry"
}

$prodChecks = [PSCustomObject]@{
    enforcedHeader = $prodKong -match "x-auth-policy:prod-enforced"
    jwtPresent = $prodKong -match "name:\s*jwt"
    consumerPresent = $prodKong -match "consumers:"
    correlationIdPresent = $prodKong -match "name:\s*correlation-id"
    rateLimitingPresent = $prodKong -match "name:\s*rate-limiting"
    otelPresent = $prodKong -match "name:\s*opentelemetry"
}

$composeChecks = [PSCustomObject]@{
    configToggle = $compose -match "KONG_DECLARATIVE_CONFIG_FILE"
    prodConfigMounted = $compose -match "kong\.prod\.yml"
    otelEndpointEnv = $compose -match "KONG_OTEL_TRACES_ENDPOINT"
    tracingEnv = Has-All $compose @("KONG_TRACING_INSTRUMENTATIONS", "KONG_TRACING_SAMPLING_RATE")
}

$k8sChecks = [PSCustomObject]@{
    jwtPresent = $k8sKongConfig -match "name:\s*jwt"
    enforcedHeader = $k8sKongConfig -match "x-auth-policy:prod-enforced"
    otelPresent = $k8sKongConfig -match "name:\s*opentelemetry"
    otelEndpointConfigured = $k8sKongConfig.Contains("http://jaeger-collector.moodride.svc.cluster.local:4318/v1/traces")
}

$passed = @(
    $devChecks.permissiveHeader,
    $devChecks.jwtAbsent,
    $devChecks.correlationIdPresent,
    $devChecks.rateLimitingPresent,
    $devChecks.otelPresent,
    $prodChecks.enforcedHeader,
    $prodChecks.jwtPresent,
    $prodChecks.consumerPresent,
    $prodChecks.correlationIdPresent,
    $prodChecks.rateLimitingPresent,
    $prodChecks.otelPresent,
    $composeChecks.configToggle,
    $composeChecks.prodConfigMounted,
    $composeChecks.otelEndpointEnv,
    $composeChecks.tracingEnv,
    $k8sChecks.jwtPresent,
    $k8sChecks.enforcedHeader,
    $k8sChecks.otelPresent,
    $k8sChecks.otelEndpointConfigured
) -notcontains $false

$result = [PSCustomObject]@{
    checkedAt = (Get-Date).ToString("o")
    passed = $passed
    dev = $devChecks
    prod = $prodChecks
    compose = $composeChecks
    k8s = $k8sChecks
}

if ($EmitJson) {
    $result | ConvertTo-Json -Depth 6
} else {
    $result | Format-List
}

if (-not $passed) {
    throw "Kong hardening policy verification failed."
}



