param(
    [string]$WorkspaceRoot = "C:\Users\aadeb\OneDrive\Desktop\MoodRide",
    [switch]$EmitJson
)

$ErrorActionPreference = "Stop"

$kongDir = Join-Path $WorkspaceRoot "infrastructure\docker\kong"
$networkName = "moodride-kong-policy-test-net"
$redisContainer = "moodride-kong-policy-test-redis"
$kongContainer = "moodride-kong-policy-test"
$proxyPort = 18000
$adminPort = 18001
$proxyBaseUrl = "http://127.0.0.1:$proxyPort"
$renderedProdConfig = ""

function Test-DockerContainerExists([string]$name) {
    $names = docker ps -a --format "{{.Names}}"
    return $names -contains $name
}

function Test-DockerNetworkExists([string]$name) {
    $names = docker network ls --format "{{.Name}}"
    return $names -contains $name
}

function New-JwtToken([string]$issuer, [string]$secret, [int]$ttlSeconds = 300) {
    $header = '{"alg":"HS256","typ":"JWT"}'
    $exp = [DateTimeOffset]::UtcNow.AddSeconds($ttlSeconds).ToUnixTimeSeconds()
    $payload = @{ iss = $issuer; exp = $exp } | ConvertTo-Json -Compress

    $encode = {
        param([string]$text)
        [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($text)).TrimEnd('=').Replace('+', '-').Replace('/', '_')
    }

    $h = & $encode $header
    $p = & $encode $payload
    $unsigned = "$h.$p"

    $hmac = New-Object Security.Cryptography.HMACSHA256
    $hmac.Key = [Text.Encoding]::UTF8.GetBytes($secret)
    $sig = [Convert]::ToBase64String($hmac.ComputeHash([Text.Encoding]::UTF8.GetBytes($unsigned))).TrimEnd('=').Replace('+', '-').Replace('/', '_')
    "$unsigned.$sig"
}

function Wait-KongReady() {
    for ($i = 0; $i -lt 30; $i++) {
        try {
            $probe = Invoke-Kong
            if ([int]$probe.status -gt 0) { return }
        } catch {
            Start-Sleep -Seconds 1
        }
    }
    throw "Kong test instance did not become ready."
}

function Invoke-Kong([hashtable]$headers = @{}) {
    try {
        $resp = Invoke-WebRequest -Uri "$proxyBaseUrl/routes" -Method Get -Headers $headers -TimeoutSec 10 -ErrorAction Stop
        return [PSCustomObject]@{ status = [int]$resp.StatusCode; headers = $resp.Headers }
    } catch {
        if ($_.Exception.Response) {
            return [PSCustomObject]@{ status = [int]$_.Exception.Response.StatusCode; headers = $_.Exception.Response.Headers }
        }
        throw
    }
}

function Convert-HeadersToMap([object]$headers) {
    $map = @{}
    if ($null -eq $headers) {
        return $map
    }

    try {
        foreach ($k in $headers.Keys) {
            $map[[string]$k] = [string]$headers[$k]
        }
    } catch {
        # Fall through to enumerable parsing.
    }

    if ($map.Count -eq 0) {
        try {
            foreach ($entry in $headers) {
                if ($null -ne $entry.Key) {
                    $map[[string]$entry.Key] = [string](@($entry.Value) -join ",")
                }
            }
        } catch {
            # Return best-effort map.
        }
    }

    return $map
}

function Test-HeaderPresent([object]$headers, [string[]]$exactNames = @(), [string[]]$prefixes = @()) {
    $headerMap = Convert-HeadersToMap -headers $headers
    if ($headerMap.Count -eq 0) {
        return $false
    }

    $allKeys = @($headerMap.Keys | ForEach-Object { [string]$_ })
    $lowerKeys = @($allKeys | ForEach-Object { $_.ToLowerInvariant() })

    foreach ($name in $exactNames) {
        if ($lowerKeys -contains $name.ToLowerInvariant()) {
            return $true
        }
    }

    foreach ($prefix in $prefixes) {
        $p = $prefix.ToLowerInvariant()
        if ($lowerKeys | Where-Object { $_.StartsWith($p) } | Select-Object -First 1) {
            return $true
        }
    }

    return $false
}

function Get-ObservedHeaderKeys([object]$headers) {
    $headerMap = Convert-HeadersToMap -headers $headers
    @($headerMap.Keys | ForEach-Object { [string]$_ } | Sort-Object -Unique)
}

function Start-KongMode([string]$configFile, [string]$jwtKey = "", [string]$jwtSecret = "") {
    if (Test-DockerContainerExists $kongContainer) {
        docker rm -f $kongContainer | Out-Null
    }

    $args = @(
        "run", "-d", "--name", $kongContainer,
        "--network", $networkName,
        "-p", "${proxyPort}:8000",
        "-p", "${adminPort}:8001",
        "-e", "KONG_DATABASE=off",
        "-e", "KONG_DECLARATIVE_CONFIG=/etc/kong/$configFile",
        "-e", "KONG_PROXY_ACCESS_LOG=/dev/stdout",
        "-e", "KONG_ADMIN_ACCESS_LOG=/dev/stdout",
        "-e", "KONG_PROXY_ERROR_LOG=/dev/stderr",
        "-e", "KONG_ADMIN_ERROR_LOG=/dev/stderr"
    )

    if ($jwtKey) { $args += @("-e", "KONG_JWT_KEY=$jwtKey") }
    if ($jwtSecret) { $args += @("-e", "KONG_JWT_SECRET=$jwtSecret") }

    $args += @("-v", "${kongDir}:/etc/kong:ro", "kong:3.7")
    docker @args | Out-Null
    Wait-KongReady
}

function Resolve-KongConfigForMode([string]$configFile, [string]$jwtKey = "", [string]$jwtSecret = "") {
    if ($configFile -ne "kong.prod.yml" -or -not $jwtKey -or -not $jwtSecret) {
        return $configFile
    }

    $source = Join-Path $kongDir $configFile
    $target = Join-Path $kongDir "kong.prod.rendered.yml"
    $content = Get-Content -Raw -Path $source
    $content = $content.Replace('${KONG_JWT_KEY:-moodride-client}', $jwtKey)
    $content = $content.Replace('${KONG_JWT_SECRET:-change-me-in-prod}', $jwtSecret)
    Set-Content -Path $target -Value $content -Encoding Ascii
    $script:renderedProdConfig = $target
    return "kong.prod.rendered.yml"
}

try {
    if (Test-DockerContainerExists $kongContainer) {
        docker rm -f $kongContainer | Out-Null
    }
    if (Test-DockerContainerExists $redisContainer) {
        docker rm -f $redisContainer | Out-Null
    }
    if (Test-DockerNetworkExists $networkName) {
        docker network rm $networkName | Out-Null
    }
    docker network create $networkName | Out-Null
    docker run -d --name $redisContainer --network $networkName --network-alias redis redis:7.2-alpine redis-server --save "" --appendonly no | Out-Null

    Start-KongMode -configFile "kong.yml"
    $dev = Invoke-Kong

    $prodConfigFile = Resolve-KongConfigForMode -configFile "kong.prod.yml" -jwtKey "moodride-client" -jwtSecret "change-me-in-prod"
    Start-KongMode -configFile $prodConfigFile -jwtKey "moodride-client" -jwtSecret "change-me-in-prod"
    $prodNoJwt = Invoke-Kong
    $token = New-JwtToken -issuer "moodride-client" -secret "change-me-in-prod"
    $prodWithJwt = Invoke-Kong -headers @{ Authorization = "Bearer $token" }

    $devChecks = [ordered]@{
        statusCode = $dev.status
        authPermissive = ($dev.status -ne 401 -and $dev.status -ne 403)
        correlationHeaderPresent = Test-HeaderPresent -headers $dev.headers -exactNames @("X-Request-ID", "X-Correlation-ID", "X-Kong-Request-Id")
        rateLimitHeaderPresent = Test-HeaderPresent -headers $dev.headers -prefixes @("X-RateLimit-Limit-", "RateLimit-Limit")
        observedHeaders = Get-ObservedHeaderKeys -headers $dev.headers
    }

    $prodChecks = [ordered]@{
        noJwtStatusCode = $prodNoJwt.status
        withJwtStatusCode = $prodWithJwt.status
        jwtEnforced = ($prodNoJwt.status -eq 401)
        jwtAccepted = ($prodWithJwt.status -ne 401 -and $prodWithJwt.status -ne 403)
        correlationHeaderPresent = Test-HeaderPresent -headers $prodNoJwt.headers -exactNames @("X-Request-ID", "X-Correlation-ID", "X-Kong-Request-Id")
        rateLimitHeaderPresent = Test-HeaderPresent -headers $prodWithJwt.headers -prefixes @("X-RateLimit-Limit-", "RateLimit-Limit")
        observedHeadersNoJwt = Get-ObservedHeaderKeys -headers $prodNoJwt.headers
        observedHeadersWithJwt = Get-ObservedHeaderKeys -headers $prodWithJwt.headers
    }

    $passed =
        $devChecks.authPermissive -and
        $devChecks.correlationHeaderPresent -and
        $devChecks.rateLimitHeaderPresent -and
        $prodChecks.jwtEnforced -and
        $prodChecks.jwtAccepted -and
        $prodChecks.correlationHeaderPresent -and
        $prodChecks.rateLimitHeaderPresent

    $result = [PSCustomObject]@{
        checkedAt = (Get-Date).ToString("o")
        passed = $passed
        dev = $devChecks
        prod = $prodChecks
    }

    if ($EmitJson) {
        $result | ConvertTo-Json -Depth 6
    } else {
        $result | Format-List
    }

    if (-not $passed) {
        throw "Kong policy tests failed."
    }
}
finally {
    if (Test-DockerContainerExists $kongContainer) {
        docker rm -f $kongContainer | Out-Null
    }
    if (Test-DockerContainerExists $redisContainer) {
        docker rm -f $redisContainer | Out-Null
    }
    if (Test-DockerNetworkExists $networkName) {
        docker network rm $networkName | Out-Null
    }
    if ($renderedProdConfig -and (Test-Path $renderedProdConfig)) {
        Remove-Item -Path $renderedProdConfig -Force
    }
}


