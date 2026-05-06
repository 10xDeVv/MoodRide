# Regional DEM Download and Scoring Log

Purpose: Track regional Copernicus DEM downloads, imports, and scoped scoring runs.
Storage root: D:\MoodRide\data\elevation\dem_tiles

## Region bounds
- West: lon -141 to -115
- Prairies: lon -114 to -96
- Central: lon -95 to -64
- Atlantic: lon -63 to -52
- Lat range for all: 41 to 83

## Per-region workflow (Option A: Docker GDAL)
1) Download region tiles into a subfolder.
2) Build a VRT mosaic for that region.
3) Import VRT into elevation_raster (first region replace, next regions append).
4) Run scoped batched scoring.

### Download
powershell
$destRoot = "D:\MoodRide\data\elevation\dem_tiles"

# Example for West
.\scripts\setup\download-copernicus-dem.ps1 -OutputDir $destRoot -RegionName "west" -MinLat 41 -MaxLat 83 -MinLon -141 -MaxLon -115 -UseBits

### Build VRT (Docker GDAL)
# Example for West
$regionDir = "D:\MoodRide\data\elevation\dem_tiles\west"
docker run --rm -v "$regionDir:/data" ghcr.io/osgeo/gdal:ubuntu-small-latest \
  sh -lc "gdalbuildvrt /data/elevation_merged.vrt /data/*.tif"

### Import into PostGIS
# First region (replace)
.\scripts\setup\import-raster-to-postgis.ps1 -InputPath "$regionDir\elevation_merged.vrt" -TargetTable elevation_raster

# Next regions (append)
.\scripts\setup\import-raster-to-postgis.ps1 -InputPath "$regionDir\elevation_merged.vrt" -TargetTable elevation_raster -Append

### Scoped scoring
Get-Content -Raw "scripts/setup/data-quality-upgrade-scoped-batched.sql" |
  docker exec -e PGAPPNAME=dq_scoped_batched -e PGOPTIONS="-c max_parallel_workers_per_gather=0" -i moodride-postgres \
  psql -U postgres -d moodride -v ON_ERROR_STOP=1 -v chunk_size=200 -f /dev/stdin

## Status log
- 2026-04-30: Switched to regional downloads (D: drive). Downloader updated for region subfolders. West download started.
- 2026-04-30: Fixed Copernicus DEM URL pattern (tile folder + .tif). West download restarted with corrected URLs.
- 2026-04-30: West download completed. West VRT built. Import to elevation_raster completed (111,280 tiles, SRID 4326).
- 2026-04-30: West scoped batched scoring started (PGAPPNAME=dq_scoped_batched_west).
