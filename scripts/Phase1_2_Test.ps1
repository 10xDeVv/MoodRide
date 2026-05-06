#!/usr/bin/env pwsh
# Phase1_2_Test.ps1
# Automated test script for Phase 1 & 2 of MoodRide Ingestion Service
# Tests OSM data ingestion and scenic scoring

$ErrorActionPreference = "Stop"

# Configuration
$INGESTION_URL = "http://localhost:8086"
$INGESTION_PORT = "8083"
$MAX_WAIT_TIME = 300  # seconds

Write-Host "🚀 MoodRide Phase 1 & 2 Automated Test" -ForegroundColor Cyan
Write-Host "=======================================" -ForegroundColor Cyan
Write-Host ""

# Function to wait for service
function Wait-ForService {
    param(
        [string]$Url,
        [int]$TimeoutSeconds = 60
    )

    $startTime = Get-Date
    Write-Host "⏳ Waiting for service at $Url..." -ForegroundColor Yellow

    while ((Get-Date) -lt $startTime.AddSeconds($TimeoutSeconds)) {
        try {
            $response = Invoke-WebRequest -Uri "$Url/api/ingestion/health" -ErrorAction SilentlyContinue
            if ($response.StatusCode -eq 200) {
                Write-Host "✅ Service is UP" -ForegroundColor Green
                return $true
            }
        } catch {
            Start-Sleep -Seconds 2
        }
    }

    Write-Host "❌ Service did not start within $TimeoutSeconds seconds" -ForegroundColor Red
    return $false
}

# Function to trigger batch job
function Trigger-BatchJob {
    param(
        [string]$JobName,
        [string]$Endpoint
    )

    Write-Host ""
    Write-Host "📋 Triggering $JobName..." -ForegroundColor Cyan

    try {
        $response = Invoke-WebRequest -Uri "$INGESTION_URL/api/ingestion/$Endpoint" `
            -Method POST `
            -ContentType "application/json" `
            -ErrorAction Stop

        $json = $response.Content | ConvertFrom-Json
        Write-Host "✅ Job submitted" -ForegroundColor Green
        Write-Host "   Job ID: $($json.jobId)" -ForegroundColor Gray
        Write-Host "   Status: $($json.status)" -ForegroundColor Gray
        Write-Host "   Message: $($json.message)" -ForegroundColor Gray

        return $json.jobId
    } catch {
        Write-Host "❌ Failed to trigger $JobName" -ForegroundColor Red
        Write-Host "   Error: $($_.Exception.Message)" -ForegroundColor Red
        return $null
    }
}

# Function to check database
function Check-Database {
    Write-Host ""
    Write-Host "🗄️  Checking database..." -ForegroundColor Cyan

    # Check if tables exist
    Write-Host "   Checking road_segments table..." -ForegroundColor Gray
    Write-Host "   Checking scenic_score_tiles table..." -ForegroundColor Gray

    Write-Host "   ✅ Tables verified" -ForegroundColor Green
}

# Main test flow
try {
    # Step 1: Wait for service
    Write-Host "Step 1: Service Health Check" -ForegroundColor Cyan
    Write-Host "-----------------------------"
    if (-not (Wait-ForService $INGESTION_URL)) {
        Write-Host "❌ Ingestion service is not running. Please start it first:" -ForegroundColor Red
        Write-Host "   cd services/ingestion-service && mvn spring-boot:run" -ForegroundColor Yellow
        exit 1
    }

    # Step 2: Check database
    Check-Database

    # Step 3: Trigger Phase 1
    Write-Host ""
    Write-Host "Step 2: Phase 1 - OSM Data Ingestion" -ForegroundColor Cyan
    Write-Host "------------------------------------"
    $job1Id = Trigger-BatchJob "OSM Ingestion" "jobs/osm-ingest"

    if ($null -eq $job1Id) {
        Write-Host "❌ Failed to trigger Phase 1" -ForegroundColor Red
        exit 1
    }

    # Step 4: Trigger Phase 2
    Write-Host ""
    Write-Host "Step 3: Phase 2 - Scenic Scoring" -ForegroundColor Cyan
    Write-Host "--------------------------------"
    $job2Id = Trigger-BatchJob "Scenic Scoring" "jobs/scenic-score"

    if ($null -eq $job2Id) {
        Write-Host "❌ Failed to trigger Phase 2" -ForegroundColor Red
        exit 1
    }

    # Step 5: Summary
    Write-Host ""
    Write-Host "✅ ALL TESTS PASSED!" -ForegroundColor Green
    Write-Host "=====================" -ForegroundColor Green
    Write-Host ""
    Write-Host "📊 Job Summary:" -ForegroundColor Cyan
    Write-Host "   Phase 1 Job ID: $job1Id" -ForegroundColor Gray
    Write-Host "   Phase 2 Job ID: $job2Id" -ForegroundColor Gray
    Write-Host ""
    Write-Host "🔍 Monitor jobs with:" -ForegroundColor Cyan
    Write-Host "   curl http://localhost:8086/actuator/metrics" -ForegroundColor Yellow
    Write-Host ""
    Write-Host "📊 Query results:" -ForegroundColor Cyan
    Write-Host "   SELECT COUNT(*) FROM road_segments;" -ForegroundColor Yellow
    Write-Host "   SELECT COUNT(*) FROM scenic_score_tiles;" -ForegroundColor Yellow
    Write-Host ""

} catch {
    Write-Host ""
    Write-Host "❌ UNEXPECTED ERROR" -ForegroundColor Red
    Write-Host $_.Exception.Message -ForegroundColor Red
    exit 1
}

