param(
    [Parameter(Mandatory = $false)]
    [string]$InputPbf = "D:\DATA\canada-260405.osm.pbf",

    [Parameter(Mandatory = $false)]
    [string]$OutputDir = "D:\DATA\osrm\canada",

    [Parameter(Mandatory = $false)]
    [string]$DatasetBasename = "canada-latest",

    [Parameter(Mandatory = $false)]
    [ValidateRange(1, 32)]
    [int]$Threads = 4,

    [Parameter(Mandatory = $false)]
    [switch]$Force,

    [Parameter(Mandatory = $false)]
    [switch]$SkipExtract,

    [Parameter(Mandatory = $false)]
    [switch]$SkipPartition,

    [Parameter(Mandatory = $false)]
    [switch]$SkipCustomize
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$image = "ghcr.io/project-osrm/osrm-backend:v5.27.1"

function Write-Step([string]$msg) {
    Write-Host ""
    Write-Host "==> $msg" -ForegroundColor Cyan
}

function Assert-Command([string]$name) {
    if (-not (Get-Command $name -ErrorAction SilentlyContinue)) {
        throw "Required command not found: $name"
    }
}

Assert-Command "docker"

if (-not (Test-Path -LiteralPath $InputPbf)) {
    throw "Input PBF not found: $InputPbf"
}

if (-not (Test-Path -LiteralPath $OutputDir)) {
    New-Item -ItemType Directory -Path $OutputDir | Out-Null
}

$resolvedInput = (Resolve-Path -LiteralPath $InputPbf).Path
$resolvedOutput = (Resolve-Path -LiteralPath $OutputDir).Path

$outputRoot = Split-Path -Path $resolvedOutput -Parent
if ([string]::IsNullOrWhiteSpace($outputRoot)) {
    throw "Invalid output directory path: $resolvedOutput"
}

$targetPbf = Join-Path $resolvedOutput "$DatasetBasename.osm.pbf"
$targetOsrm = Join-Path $resolvedOutput "$DatasetBasename.osrm"
$logDir = Join-Path $resolvedOutput "logs"
if (-not (Test-Path -LiteralPath $logDir)) {
    New-Item -ItemType Directory -Path $logDir | Out-Null
}

Write-Step "Preparing inputs"
if ($Force -or -not (Test-Path -LiteralPath $targetPbf)) {
    Copy-Item -LiteralPath $resolvedInput -Destination $targetPbf -Force
    Write-Host "Copied input PBF to $targetPbf"
} else {
    Write-Host "Reusing existing $targetPbf"
}

Write-Host "Input: $targetPbf"
Write-Host "Output: $resolvedOutput"
Write-Host "Dataset basename: $DatasetBasename"
Write-Host "Extract threads: $Threads"

function Run-OsrmStep([string]$stepName, [string[]]$commandArgs, [string]$logFile) {
    if ($null -eq $commandArgs -or $commandArgs.Count -eq 0) {
        throw "$stepName called without OSRM command arguments."
    }
    Write-Step $stepName
    Write-Host ("docker run --rm -v `"$outputRoot`":/data $image " + ($commandArgs -join " "))
    & docker run --rm -v "${outputRoot}:/data" $image @commandArgs 2>&1 | Tee-Object -FilePath $logFile
    if ($LASTEXITCODE -ne 0) {
        throw "$stepName failed. See log: $logFile"
    }
}

$extractLog = Join-Path $logDir "osrm-extract.log"
$partitionLog = Join-Path $logDir "osrm-partition.log"
$customizeLog = Join-Path $logDir "osrm-customize.log"

if (-not $SkipExtract) {
    if ($Force -or -not (Test-Path -LiteralPath $targetOsrm)) {
        Run-OsrmStep -stepName "Running osrm-extract (this is the longest step)" `
            -commandArgs @("osrm-extract", "--threads", "$Threads", "-p", "/opt/car.lua", "/data/canada/$DatasetBasename.osm.pbf") `
            -logFile $extractLog
    } else {
        Write-Step "Skipping osrm-extract because $targetOsrm already exists"
    }
}

if (-not $SkipPartition) {
    $cellsFile = Join-Path $resolvedOutput "$DatasetBasename.osrm.cells"
    if ($Force -or -not (Test-Path -LiteralPath $cellsFile)) {
        Run-OsrmStep -stepName "Running osrm-partition" `
            -commandArgs @("osrm-partition", "/data/canada/$DatasetBasename.osrm") `
            -logFile $partitionLog
    } else {
        Write-Step "Skipping osrm-partition because $cellsFile already exists"
    }
}

if (-not $SkipCustomize) {
    $cellMetricsFile = Join-Path $resolvedOutput "$DatasetBasename.osrm.cell_metrics"
    if ($Force -or -not (Test-Path -LiteralPath $cellMetricsFile)) {
        Run-OsrmStep -stepName "Running osrm-customize" `
            -commandArgs @("osrm-customize", "/data/canada/$DatasetBasename.osrm") `
            -logFile $customizeLog
    } else {
        Write-Step "Skipping osrm-customize because $cellMetricsFile already exists"
    }
}

Write-Step "Verifying output files"
$expected = @(
    "$DatasetBasename.osrm.names",
    "$DatasetBasename.osrm.fileIndex",
    "$DatasetBasename.osrm.properties",
    "$DatasetBasename.osrm.partition",
    "$DatasetBasename.osrm.cells",
    "$DatasetBasename.osrm.cell_metrics",
    "$DatasetBasename.osrm.mldgr"
)

$missing = @()
foreach ($name in $expected) {
    $path = Join-Path $resolvedOutput $name
    if (-not (Test-Path -LiteralPath $path)) {
        $missing += $name
    }
}

if ($missing.Count -gt 0) {
    throw "Build completed with missing expected files: $($missing -join ', ')"
}

Get-ChildItem -LiteralPath $resolvedOutput -File |
    Where-Object { $_.Name -like "$DatasetBasename.osrm*" } |
    Sort-Object Length -Descending |
    Select-Object Name, @{Name = "SizeGB"; Expression = { [math]::Round($_.Length / 1GB, 3) } } |
    Format-Table -AutoSize

Write-Step "Done"
Write-Host "OSRM dataset is ready under: $resolvedOutput"
Write-Host "Next publish command:"
Write-Host "./scripts/deploy/publish_data_release.ps1 -DatasetBasename $DatasetBasename -DataDirectory `"$resolvedOutput`" -ReleaseTag `"data-$DatasetBasename-$(Get-Date -Format 'yyyyMMdd-HHmm')`" -Repo `"10xDeVv/MoodRide`""
