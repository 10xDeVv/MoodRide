param(
    [Parameter(Mandatory = $true)]
    [string]$ServiceUrl,

    [int[]]$ObjectIds = @(),
    [string]$ObjectIdsFile,
    [switch]$FetchAllIds,

    [string]$WorkDir = "D:\MoodRide\data\protected-areas\cpcad-rest",
    [string]$StateFilePath,
    [int]$BatchSize = 50,
    [int]$MaxRetries = 4,
    [int]$RetryDelaySeconds = 8,

    [string]$Database = "moodride",
    [string]$Username = "postgres",
    [string]$Password,
    [string]$PostgresContainerName = "moodride-postgres",
    [string]$DockerGdalImage = "ghcr.io/osgeo/gdal:ubuntu-small-latest",
    [string]$DockerMemoryLimit = "1g",

    [string]$ObjectIdColumn = "objectid",
    [string]$NameColumn = "name",
    [string]$IucnColumn = "iucn_cat",
    [string]$DesignationColumn = "designation",
    [string]$SourceLabel = "CPCAD",

    [switch]$SkipImport
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

if ($BatchSize -lt 1) {
    throw "BatchSize must be at least 1."
}
if ($MaxRetries -lt 1) {
    throw "MaxRetries must be at least 1."
}

if (-not $StateFilePath) {
    $StateFilePath = Join-Path $WorkDir "cpcad-rest-import-state.json"
}

New-Item -ItemType Directory -Force -Path $WorkDir | Out-Null
$batchDir = Join-Path $WorkDir "batches"
New-Item -ItemType Directory -Force -Path $batchDir | Out-Null

function ConvertTo-HashtableRecursive {
    param([Parameter(Mandatory = $true)]$InputObject)

    if ($null -eq $InputObject) { return $null }
    if ($InputObject -is [System.Collections.IDictionary]) {
        $dict = @{}
        foreach ($key in $InputObject.Keys) {
            $dict[$key] = ConvertTo-HashtableRecursive -InputObject $InputObject[$key]
        }
        return $dict
    }
    if ($InputObject -is [pscustomobject]) {
        $dict = @{}
        foreach ($property in $InputObject.PSObject.Properties) {
            $dict[$property.Name] = ConvertTo-HashtableRecursive -InputObject $property.Value
        }
        return $dict
    }
    if ($InputObject -is [System.Collections.IEnumerable] -and -not ($InputObject -is [string])) {
        $items = @()
        foreach ($item in $InputObject) {
            $items += ,(ConvertTo-HashtableRecursive -InputObject $item)
        }
        return $items
    }
    return $InputObject
}

function New-State {
    return @{
        serviceUrl = $ServiceUrl
        sourceLabel = $SourceLabel
        createdAt = (Get-Date).ToString("o")
        updatedAt = $null
        completed = @()
        failed = @{}
    }
}

function Load-State {
    if (-not (Test-Path -LiteralPath $StateFilePath)) {
        return (New-State)
    }
    $raw = Get-Content -LiteralPath $StateFilePath -Raw
    if ([string]::IsNullOrWhiteSpace($raw)) {
        return (New-State)
    }
    return (ConvertTo-HashtableRecursive -InputObject ($raw | ConvertFrom-Json))
}

function Save-State {
    param([hashtable]$State)
    $State.updatedAt = (Get-Date).ToString("o")
    $State | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $StateFilePath -Encoding UTF8
}

function Get-InputObjectIds {
    $ids = New-Object System.Collections.Generic.List[int]

    foreach ($id in $ObjectIds) {
        $ids.Add([int]$id)
    }

    if ($ObjectIdsFile) {
        if (-not (Test-Path -LiteralPath $ObjectIdsFile)) {
            throw "ObjectIdsFile does not exist: $ObjectIdsFile"
        }
        foreach ($line in Get-Content -LiteralPath $ObjectIdsFile) {
            $trimmed = [string]$line
            $trimmed = $trimmed.Trim()
            if ([string]::IsNullOrWhiteSpace($trimmed) -or $trimmed.StartsWith("#")) {
                continue
            }
            foreach ($part in ($trimmed -split "[,\s]+")) {
                if (-not [string]::IsNullOrWhiteSpace($part)) {
                    $ids.Add([int]$part)
                }
            }
        }
    }

    if ($FetchAllIds) {
        $uri = "$ServiceUrl/query?where=1%3D1&returnIdsOnly=true&f=json"
        Write-Host "Fetching all OBJECTIDs from service..."
        $response = Invoke-RestMethod -Uri $uri -Method Get -TimeoutSec 120
        foreach ($id in @($response.objectIds)) {
            $ids.Add([int]$id)
        }
    }

    $uniqueIds = @($ids | Sort-Object -Unique)
    if ($uniqueIds.Count -eq 0) {
        throw "No OBJECTIDs supplied. Use -ObjectIds, -ObjectIdsFile, or -FetchAllIds."
    }
    return $uniqueIds
}

function Invoke-WithRetry {
    param(
        [scriptblock]$Operation,
        [string]$Description
    )

    $lastError = $null
    for ($attempt = 1; $attempt -le $MaxRetries; $attempt++) {
        try {
            return & $Operation
        } catch {
            $lastError = $_
            if ($attempt -ge $MaxRetries) {
                break
            }
            $sleep = $RetryDelaySeconds * $attempt
            Write-Host "$Description failed on attempt $attempt/$MaxRetries. Retrying in ${sleep}s: $($_.Exception.Message)" -ForegroundColor DarkYellow
            Start-Sleep -Seconds $sleep
        }
    }
    throw $lastError
}

function Download-GeoJsonBatch {
    param(
        [int[]]$Ids,
        [string]$OutputPath
    )

    $idText = ($Ids -join ",")
    $encodedIds = [System.Uri]::EscapeDataString($idText)
    $uri = "$ServiceUrl/query?objectIds=$encodedIds&outFields=*&returnGeometry=true&outSR=4326&f=geojson"

    Invoke-WithRetry -Description "Download OBJECTIDs $($Ids[0])..$($Ids[-1])" -Operation {
        Invoke-WebRequest -UseBasicParsing -Uri $uri -Method Get -TimeoutSec 180 -OutFile $OutputPath | Out-Null
    }

    $info = Get-Item -LiteralPath $OutputPath
    if ($info.Length -lt 64) {
        throw "Downloaded GeoJSON is too small: $OutputPath ($($info.Length) bytes)"
    }

    $prefix = Get-Content -LiteralPath $OutputPath -TotalCount 1
    if ($prefix -notmatch "FeatureCollection" -and $prefix -notmatch '"features"') {
        $sample = $prefix
        if ($sample.Length -gt 240) {
            $sample = $sample.Substring(0, 240)
        }
        throw "Downloaded file does not look like GeoJSON: $sample"
    }
}

function Invoke-DockerPsql {
    param([string]$Sql)

    $dockerArgs = @("exec")
    if ($Password) {
        $dockerArgs += @("-e", "PGPASSWORD=$Password")
    }
    $dockerArgs += @($PostgresContainerName, "psql", "-U", $Username, "-d", $Database, "-v", "ON_ERROR_STOP=1", "-c", $Sql)
    & docker @dockerArgs
    if ($LASTEXITCODE -ne 0) {
        throw "psql failed with exit code $LASTEXITCODE"
    }
}

function Ensure-ProtectedAreasSchema {
    $sql = @"
CREATE TABLE IF NOT EXISTS protected_areas (
    id BIGSERIAL PRIMARY KEY,
    geometry geometry(MULTIPOLYGON, 4326) NOT NULL,
    name text,
    iucn_category text,
    designation_type text,
    source text,
    source_object_id bigint,
    last_updated timestamp without time zone DEFAULT CURRENT_TIMESTAMP
);
ALTER TABLE protected_areas
    ADD COLUMN IF NOT EXISTS source_object_id bigint;
CREATE INDEX IF NOT EXISTS protected_areas_geom_idx
    ON protected_areas USING GIST (geometry);
CREATE UNIQUE INDEX IF NOT EXISTS protected_areas_source_source_object_id_uidx
    ON protected_areas (source, source_object_id)
    WHERE source_object_id IS NOT NULL;
"@
    Invoke-DockerPsql -Sql $sql
}

function Import-GeoJsonBatch {
    param(
        [string]$GeoJsonPath,
        [string]$BatchName
    )

    $dockerNetwork = (docker inspect $PostgresContainerName | ConvertFrom-Json)[0].NetworkSettings.Networks.PSObject.Properties.Name | Select-Object -First 1
    if ([string]::IsNullOrWhiteSpace($dockerNetwork)) {
        throw "Could not determine docker network for container '$PostgresContainerName'."
    }

    $inputDir = Split-Path -Path $GeoJsonPath -Parent
    $inputFile = Split-Path -Path $GeoJsonPath -Leaf
    $stagingTable = "protected_areas_rest_staging_$BatchName"
    $dockerPgConn = "PG:host=$PostgresContainerName port=5432 dbname=$Database user=$Username"
    if ($Password) {
        $dockerPgConn += " password=$Password"
    }

    & docker run --rm --memory $DockerMemoryLimit --memory-swap $DockerMemoryLimit --network $dockerNetwork -v "${inputDir}:/workspace/input" $DockerGdalImage ogr2ogr `
        -f PostgreSQL $dockerPgConn "/workspace/input/$inputFile" `
        -nln $stagingTable `
        -overwrite `
        -nlt MULTIPOLYGON `
        -lco GEOMETRY_NAME=geometry `
        -lco FID=id `
        -t_srs EPSG:4326

    if ($LASTEXITCODE -ne 0) {
        throw "ogr2ogr failed with exit code $LASTEXITCODE for $GeoJsonPath"
    }

    $safeStaging = $stagingTable -replace '[^a-zA-Z0-9_]', ''
    $source = $SourceLabel.Replace("'", "''")
    $objectColumnCandidates = @($ObjectIdColumn, "objectid", "object_id", "objectid_1", "OBJECTID") | ForEach-Object { ($_ -replace '[^a-zA-Z0-9_]', '').ToLowerInvariant() } | Select-Object -Unique
    $nameColumnCandidates = @($NameColumn, "name", "english_name", "ename", "name_e") | ForEach-Object { ($_ -replace '[^a-zA-Z0-9_]', '').ToLowerInvariant() } | Select-Object -Unique
    $iucnColumnCandidates = @($IucnColumn, "iucn_cat", "iucn", "iucn_category") | ForEach-Object { ($_ -replace '[^a-zA-Z0-9_]', '').ToLowerInvariant() } | Select-Object -Unique
    $designationColumnCandidates = @($DesignationColumn, "designation", "desig", "type", "designation_type") | ForEach-Object { ($_ -replace '[^a-zA-Z0-9_]', '').ToLowerInvariant() } | Select-Object -Unique

    $objectArray = "'" + ($objectColumnCandidates -join "','") + "'"
    $nameArray = "'" + ($nameColumnCandidates -join "','") + "'"
    $iucnArray = "'" + ($iucnColumnCandidates -join "','") + "'"
    $designationArray = "'" + ($designationColumnCandidates -join "','") + "'"

    $insertSql = @"
DO `$$
DECLARE
    object_col text;
    name_col text;
    iucn_col text;
    designation_col text;
BEGIN
    SELECT column_name
    FROM information_schema.columns
    WHERE table_schema = 'public'
      AND table_name = '$safeStaging'
      AND lower(column_name) IN ($objectArray)
    ORDER BY CASE lower(column_name)
        WHEN '$($ObjectIdColumn.ToLowerInvariant())' THEN 0
        WHEN 'objectid' THEN 1
        ELSE 2
    END
    LIMIT 1
    INTO object_col;

    IF object_col IS NULL THEN
        RAISE EXCEPTION 'No OBJECTID column found in staging table %', '$safeStaging';
    END IF;

    SELECT column_name
    FROM information_schema.columns
    WHERE table_schema = 'public'
      AND table_name = '$safeStaging'
      AND lower(column_name) IN ($nameArray)
    LIMIT 1
    INTO name_col;

    SELECT column_name
    FROM information_schema.columns
    WHERE table_schema = 'public'
      AND table_name = '$safeStaging'
      AND lower(column_name) IN ($iucnArray)
    LIMIT 1
    INTO iucn_col;

    SELECT column_name
    FROM information_schema.columns
    WHERE table_schema = 'public'
      AND table_name = '$safeStaging'
      AND lower(column_name) IN ($designationArray)
    LIMIT 1
    INTO designation_col;

    EXECUTE format(
        'INSERT INTO protected_areas (
            geometry,
            name,
            iucn_category,
            designation_type,
            source,
            source_object_id,
            last_updated
         )
         SELECT
            ST_Multi(ST_CollectionExtract(ST_MakeValid(geometry), 3))::geometry(MULTIPOLYGON, 4326),
            %s,
            %s,
            %s,
            %L,
            NULLIF(%s::text, '''')::bigint,
            CURRENT_TIMESTAMP
         FROM %I
         WHERE geometry IS NOT NULL
           AND NOT ST_IsEmpty(ST_CollectionExtract(ST_MakeValid(geometry), 3))
         ON CONFLICT (source, source_object_id)
         WHERE source_object_id IS NOT NULL
         DO UPDATE SET
            geometry = EXCLUDED.geometry,
            name = COALESCE(EXCLUDED.name, protected_areas.name),
            iucn_category = COALESCE(EXCLUDED.iucn_category, protected_areas.iucn_category),
            designation_type = COALESCE(EXCLUDED.designation_type, protected_areas.designation_type),
            last_updated = CURRENT_TIMESTAMP',
        CASE WHEN name_col IS NULL THEN 'NULL::text' ELSE quote_ident(name_col) END,
        CASE WHEN iucn_col IS NULL THEN 'NULL::text' ELSE quote_ident(iucn_col) END,
        CASE WHEN designation_col IS NULL THEN 'NULL::text' ELSE quote_ident(designation_col) END,
        '$source',
        quote_ident(object_col),
        '$safeStaging'
    );
END `$$;
DROP TABLE IF EXISTS $safeStaging;
"@

    Invoke-DockerPsql -Sql $insertSql
}

function Mark-Completed {
    param([hashtable]$State, [int[]]$Ids)
    $completedSet = @{}
    foreach ($id in @($State.completed)) {
        $completedSet[[string]$id] = $true
    }
    foreach ($id in $Ids) {
        $completedSet[[string]$id] = $true
        if ($State.failed.ContainsKey([string]$id)) {
            $State.failed.Remove([string]$id)
        }
    }
    $State.completed = @($completedSet.Keys | ForEach-Object { [int]$_ } | Sort-Object)
}

function Mark-Failed {
    param([hashtable]$State, [int]$Id, [string]$Reason)
    $State.failed[[string]$Id] = $Reason
}

function Process-Batch {
    param(
        [hashtable]$State,
        [int[]]$Ids
    )

    if ($Ids.Count -eq 0) {
        return
    }

    $batchName = "ids_$($Ids[0])_$($Ids[-1])_$($Ids.Count)"
    $geoJsonPath = Join-Path $batchDir "$batchName.geojson"
    Write-Host "Processing OBJECTIDs $($Ids[0])..$($Ids[-1]) ($($Ids.Count))"

    try {
        Download-GeoJsonBatch -Ids $Ids -OutputPath $geoJsonPath
        if (-not $SkipImport) {
            Import-GeoJsonBatch -GeoJsonPath $geoJsonPath -BatchName (($batchName -replace '[^a-zA-Z0-9_]', '_').ToLowerInvariant())
        }
        Mark-Completed -State $State -Ids $Ids
        Save-State -State $State
        return
    } catch {
        $reason = $_.Exception.Message
        Write-Host "Batch $($Ids[0])..$($Ids[-1]) failed: $reason" -ForegroundColor DarkYellow
        if ($Ids.Count -gt 1) {
            $mid = [Math]::Floor($Ids.Count / 2)
            $left = @($Ids | Select-Object -First $mid)
            $right = @($Ids | Select-Object -Skip $mid)
            Process-Batch -State $State -Ids $left
            Process-Batch -State $State -Ids $right
        } else {
            Mark-Failed -State $State -Id $Ids[0] -Reason $reason
            Save-State -State $State
        }
    }
}

$docker = Get-Command docker -ErrorAction SilentlyContinue
if (-not $docker) {
    throw "Docker is required for the GDAL and psql import path used by this resumable importer."
}

if (-not $SkipImport) {
    Ensure-ProtectedAreasSchema
}

$state = Load-State
$completed = @{}
foreach ($id in @($state.completed)) {
    $completed[[string]$id] = $true
}

$allIds = @(Get-InputObjectIds)
$pendingIds = @($allIds | Where-Object { -not $completed.ContainsKey([string]$_) })

Write-Host "CPCAD REST import:"
Write-Host "  total_input=$($allIds.Count)"
Write-Host "  already_completed=$($completed.Count)"
Write-Host "  pending=$($pendingIds.Count)"
Write-Host "  failed_previous=$(@($state.failed.Keys).Count)"
Write-Host "  batch_size=$BatchSize"
Write-Host "  state=$StateFilePath"

for ($i = 0; $i -lt $pendingIds.Count; $i += $BatchSize) {
    $batch = @($pendingIds | Select-Object -Skip $i -First $BatchSize)
    Process-Batch -State $state -Ids $batch
}

Save-State -State $state

Write-Host "CPCAD REST import complete:"
Write-Host "  completed=$(@($state.completed).Count)"
Write-Host "  failed=$(@($state.failed.Keys).Count)"
if (@($state.failed.Keys).Count -gt 0) {
    Write-Host "Failed OBJECTIDs remain in $StateFilePath" -ForegroundColor DarkYellow
}
