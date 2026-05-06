param(
    [Parameter(Mandatory = $true)]
    [string]$DatasetBasename,

    [Parameter(Mandatory = $true)]
    [string]$DataDirectory,

    [Parameter(Mandatory = $false)]
    [string]$ReleaseTag = "",

    [Parameter(Mandatory = $false)]
    [string]$Repo = ""
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

if (-not $ReleaseTag) {
    $ReleaseTag = "data-$DatasetBasename-$(Get-Date -Format 'yyyyMMdd-HHmmss')"
}

if (-not (Get-Command gh -ErrorAction SilentlyContinue)) {
    throw "GitHub CLI (gh) is required. Install from https://cli.github.com/ and run 'gh auth login'."
}

$resolvedDataDir = (Resolve-Path -Path $DataDirectory).Path
$datasetFiles = Get-ChildItem -Path $resolvedDataDir -File | Where-Object {
    $_.Name -like "$DatasetBasename.osrm*"
}

if ($datasetFiles.Count -eq 0) {
    throw "No files matching '$DatasetBasename.osrm*' found in '$resolvedDataDir'."
}

$workDir = Join-Path -Path ([System.IO.Path]::GetTempPath()) -ChildPath ("moodride-data-release-" + [Guid]::NewGuid().ToString("N"))
New-Item -ItemType Directory -Path $workDir | Out-Null

try {
    foreach ($file in $datasetFiles) {
        Copy-Item -LiteralPath $file.FullName -Destination (Join-Path $workDir $file.Name)
    }

    $metadata = [ordered]@{
        datasetBasename = $DatasetBasename
        generatedAtUtc  = (Get-Date).ToUniversalTime().ToString("o")
        sourceDirectory = $resolvedDataDir
        fileCount       = $datasetFiles.Count
    } | ConvertTo-Json -Depth 4

    $metadataPath = Join-Path $workDir "metadata.json"
    Set-Content -LiteralPath $metadataPath -Value $metadata -Encoding UTF8

    $assetName = "osrm-$DatasetBasename.tar.gz"
    $assetPath = Join-Path (Get-Location) $assetName
    if (Test-Path $assetPath) {
        Remove-Item -LiteralPath $assetPath -Force
    }

    Push-Location $workDir
    try {
        tar -czf $assetPath .
    } finally {
        Pop-Location
    }

    $hash = (Get-FileHash -Algorithm SHA256 -Path $assetPath).Hash.ToLowerInvariant()
    $checksumPath = "$assetPath.sha256"
    Set-Content -LiteralPath $checksumPath -Value "$hash  $assetName" -Encoding ASCII

    $repoArgs = @()
    if ($Repo) {
        $repoArgs = @("--repo", $Repo)
    }

    & gh release view $ReleaseTag @repoArgs *> $null
    if ($LASTEXITCODE -ne 0) {
        & gh release create $ReleaseTag --title "Data release $ReleaseTag" --notes "OSRM dataset release for $DatasetBasename." @repoArgs
        if ($LASTEXITCODE -ne 0) {
            throw "Failed to create release $ReleaseTag."
        }
    }

    & gh release upload $ReleaseTag $assetPath $checksumPath --clobber @repoArgs
    if ($LASTEXITCODE -ne 0) {
        throw "Failed to upload release assets."
    }

    Write-Host "Published data release tag: $ReleaseTag"
    Write-Host "Assets:"
    Write-Host "  - $assetName"
    Write-Host "  - $(Split-Path -Leaf $checksumPath)"
} finally {
    if (Test-Path $workDir) {
        Remove-Item -LiteralPath $workDir -Recurse -Force
    }
}
