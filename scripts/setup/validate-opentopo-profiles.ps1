param(
    [string]$IngestionResourcesDir = "C:\Users\aadeb\OneDrive\Desktop\MoodRide\services\ingestion-service\src\main\resources",
    [string]$ScenicResourcesDir = "C:\Users\aadeb\OneDrive\Desktop\MoodRide\services\scenic-scoring-service\src\main\resources",
    [string[]]$TargetProfiles = @("local", "docker", "prod"),
    [switch]$EmitJson
)

$ErrorActionPreference = "Stop"

function Get-YamlValue {
    param(
        [string[]]$Lines,
        [string]$Key
    )

    foreach ($line in $Lines) {
        $trimmed = $line.Trim()
        if ($trimmed -match "^$([Regex]::Escape($Key))\s*:\s*(.+)$") {
            return $Matches[1].Trim()
        }
    }

    return $null
}

$results = @()
$targets = @(
    @{ service = "ingestion-service"; resourcesDir = $IngestionResourcesDir },
    @{ service = "scenic-scoring-service"; resourcesDir = $ScenicResourcesDir }
)

foreach ($target in $targets) {
foreach ($profile in $TargetProfiles) {
    $filePath = Join-Path $target.resourcesDir ("application-{0}.yml" -f $profile)

    if (-not (Test-Path -Path $filePath -PathType Leaf)) {
        $results += [PSCustomObject]@{
            service = $target.service
            profile = $profile
            file = $filePath
            fileExists = $false
            enabled = $null
            baseUrl = $null
            dataset = $null
            valid = $false
            issue = "Profile file missing"
        }
        continue
    }

    $lines = Get-Content -Path $filePath
    $enabled = Get-YamlValue -Lines $lines -Key "enabled"
    $baseUrl = Get-YamlValue -Lines $lines -Key "base-url"
    $dataset = Get-YamlValue -Lines $lines -Key "dataset"

    $isValid =
        -not [string]::IsNullOrWhiteSpace($enabled) -and
        -not [string]::IsNullOrWhiteSpace($baseUrl) -and
        -not [string]::IsNullOrWhiteSpace($dataset)

    $issue = if ($isValid) { "" } else { "Missing one or more required OpenTopo keys (enabled/base-url/dataset)" }

    $results += [PSCustomObject]@{
        service = $target.service
        profile = $profile
        file = $filePath
        fileExists = $true
        enabled = $enabled
        baseUrl = $baseUrl
        dataset = $dataset
        valid = $isValid
        issue = $issue
    }
}
}

$summary = [PSCustomObject]@{
    targetProfiles = $TargetProfiles
    passed = (($results | Where-Object { -not $_.valid }).Count -eq 0)
    checkedAt = (Get-Date).ToString("o")
    results = $results
}

if ($EmitJson) {
    $summary | ConvertTo-Json -Depth 6
} else {
    $results | Format-Table service, profile, fileExists, valid, enabled, baseUrl, dataset, issue -AutoSize
    Write-Host "OpenTopo profile validation passed=$($summary.passed)"
}

if (-not $summary.passed) {
    throw "OpenTopo profile validation failed."
}

