

# MoodRide – Data Quality Upgrade Execution Plan

## 🧭 Context

The hybrid routing system works end-to-end. Routes generate, loop back, OSRM provides real timing, three options are returned, and the UI is being polished.

**However, the scenic scoring data is incomplete.** Two critical data sources are missing:

- **Land cover data** → affects `green_score`, `solitude_score`
- **Elevation data** → affects `elevation_score`

Without these, the scoring pipeline is guessing. A forest and a parking lot might score similarly. The Laurentians and Saskatchewan prairie look the same to the system. Preferences like "greenery" and "elevation" don't meaningfully change routes because the underlying component scores are weak.

### Core Principle

> "The algorithm is only as good as the data feeding it."

Fix the data **before** running the test matrix. Otherwise you're tuning an algorithm on garbage inputs.

## 📌 Execution Assets In This Repo

To run this plan directly from the repository:

- Orchestration script: `scripts/setup/run-data-quality-upgrade.ps1`
- Raster loader script: `scripts/setup/import-raster-to-postgis.ps1`
- Scoring SQL: `scripts/setup/data-quality-upgrade.sql`
- Progress tracker: `docs/DataQualityUpgradeProgress.md`

Example:

```powershell
.\scripts\setup\run-data-quality-upgrade.ps1 `
  -ElevationInputPath "C:\path\to\elevation_merged.tif" `
  -Password "<postgres-password>"
```

---

## 📊 What's Missing and Why It Matters

| Component Score | Current Source | Problem | Fix |
|---|---|---|---|
| `green_score` | OSM landuse tags | Inconsistent, incomplete, many areas untagged | Canada Land Cover (satellite-derived, 30m resolution) |
| `solitude_score` | OSM road density / traffic tags | Missing land context — a road through forest vs through suburbs looks the same | Canada Land Cover (distinguish wilderness from developed land) |
| `elevation_score` | OSM contour data (if any) | Unreliable, sparse, often missing entirely | SRTM / Copernicus DEM via OpenTopography (global, 30m resolution) |
| `water_score` | OSM water features + pre-aggregation | **Already good** — keep as-is | No change |
| `curve_score` | Road geometry sinuosity | **Already good** — computed from road segments | No change |
| `poi_score` | OSM POI tags + pre-aggregation | **Adequate** — can improve later but not blocking | No change |

**This plan upgrades `green_score`, `solitude_score`, and `elevation_score` only.** The other components are fine.

---

## 🗂️ Data Sources

### 1. Canada Land Cover (Natural Resources Canada)

**Dataset:** Annual Crop Inventory / Land Cover of Canada

**URL:** https://open.canada.ca/data/en/dataset/fa84a70f-03ad-4946-b0f8-a3b481dd5571

**Alternative:** Canada Centre for Remote Sensing — Land Cover, circa 2020
https://natural-resources.canada.ca/maps-tools-and-publications/satellite-imagery-and-air-photos/tutorial-fundamentals-remote-sensing/educational-resources-applications/land-cover/9373

**What it provides:**
- 30m resolution raster grid
- Every pixel classified into land cover types:
  - Temperate/boreal forest
  - Grassland / shrubland
  - Cropland
  - Urban / developed
  - Water
  - Wetland
  - Barren land
  - Snow / ice

**Format:** GeoTIFF raster

**Coverage:** All of Canada

**Size:** Large — expect multiple GB for national coverage. Can clip to provinces if needed.

### 2. Elevation Data (SRTM / Copernicus DEM via OpenTopography)

**Dataset:** Copernicus GLO-30 DEM (preferred) or SRTM v3

**URL:** https://portal.opentopography.org/raster?opentopoID=OTSDEM.032021.4326.3

**Alternative direct download:** https://copernicus-dem-30m.s3.amazonaws.com/

**What it provides:**
- 30m resolution elevation grid
- Elevation in meters for every pixel
- Global coverage including all of Canada

**Format:** GeoTIFF raster

**Coverage:** Global (clip to Canada / your operational area)

**Size:** Large per tile — Canada spans many tiles. Download only what you need.

---

## ⚙️ Processing Pipeline

### Overview

```
Raw GeoTIFF (land cover / elevation)
        │
        ▼
Clip to operational area (optional — save processing time)
        │
        ▼
For each H3 tile (211,510 tiles):
   1. Find all raster pixels within the tile's hexagon boundary
   2. Aggregate pixel values into a score
        │
        ▼
Update scenic_score_tiles with new component scores
        │
        ▼
Recompute composite scenic_score from all components
```

### Step-by-Step

#### Step 1: Download and Prepare Raster Data

**Land Cover:**
- [ ] Download Canada Land Cover GeoTIFF
- [ ] Verify CRS (reproject to EPSG:4326 if needed)
- [ ] Clip to operational bounding box if full national dataset is too large

**Elevation:**
- [ ] Download Copernicus DEM tiles covering your operational area
- [ ] Merge tiles into a single mosaic if needed (or process tile by tile)
- [ ] Verify CRS (should be EPSG:4326)

**Tools:** `gdal_translate`, `gdalwarp`, `gdal_merge.py` — all available via GDAL in Docker or system install.

```bash
# Example: reproject land cover to EPSG:4326
gdalwarp -t_srs EPSG:4326 landcover_raw.tif landcover_4326.tif

# Example: clip to bounding box (e.g., New Brunswick area for testing)
gdalwarp -te -67.5 45.0 -65.5 47.5 landcover_4326.tif landcover_nb.tif

# Example: merge multiple DEM tiles
gdal_merge.py -o elevation_merged.tif tile1.tif tile2.tif tile3.tif
```

---

#### Step 2: Load Raster Data into PostGIS

Use `raster2pgsql` to load the GeoTIFFs into PostGIS raster tables.

```bash
# Load land cover
raster2pgsql -s 4326 -t 256x256 -I -C -M landcover_4326.tif public.landcover_raster | psql -d moodride

# Load elevation
raster2pgsql -s 4326 -t 256x256 -I -C -M elevation_merged.tif public.elevation_raster | psql -d moodride
```

**`-t 256x256`** tiles the raster for efficient spatial queries.
**`-I`** creates a spatial index.

**Checklist:**
- [ ] Load land cover raster into `landcover_raster` table
- [ ] Load elevation raster into `elevation_raster` table
- [ ] Verify both tables have spatial indexes
- [ ] Spot check: query a known location and confirm values make sense

```sql
-- Spot check: get land cover class at a known forested location
SELECT ST_Value(rast, ST_SetSRID(ST_MakePoint(-66.63, 45.94), 4326))
FROM landcover_raster
WHERE ST_Intersects(rast, ST_SetSRID(ST_MakePoint(-66.63, 45.94), 4326));
```

---

#### Step 3: Compute Green Score from Land Cover

**Logic:** For each H3 tile, sample the land cover pixels within the hex boundary. Compute the proportion of "green" land cover types.

**Land cover class mapping:**

| Class | Category | Green Weight |
|---|---|---|
| Forest (all types) | High greenery | 1.0 |
| Wetland | Moderate greenery | 0.7 |
| Grassland / shrubland | Moderate greenery | 0.6 |
| Cropland | Low greenery | 0.3 |
| Water | Neutral | 0.0 (handled by water_score) |
| Urban / developed | No greenery | 0.0 |
| Barren | No greenery | 0.0 |

```sql
-- Example: compute green_score for each H3 tile
UPDATE scenic_score_tiles sst
SET green_score = sub.green_score
FROM (
    SELECT
        sst2.h3_index,
        AVG(
            CASE
                WHEN ST_Value(lr.rast, pt.geom) IN (1,2,3,4,5,6)  -- forest classes (adjust to actual class IDs)
                    THEN 1.0
                WHEN ST_Value(lr.rast, pt.geom) IN (10,11)  -- wetland
                    THEN 0.7
                WHEN ST_Value(lr.rast, pt.geom) IN (7,8)  -- grassland/shrub
                    THEN 0.6
                WHEN ST_Value(lr.rast, pt.geom) IN (15,16)  -- cropland
                    THEN 0.3
                ELSE 0.0
            END
        ) AS green_score
    FROM scenic_score_tiles sst2
    CROSS JOIN LATERAL (
        -- sample points within the H3 hex
        SELECT (ST_DumpPoints(sst2.geometry)).geom
    ) pt
    JOIN landcover_raster lr ON ST_Intersects(lr.rast, pt.geom)
    GROUP BY sst2.h3_index
) sub
WHERE sst.h3_index = sub.h3_index;
```

> **Note:** The actual class IDs depend on the specific Canada Land Cover dataset version. Check the dataset documentation for the legend / class mapping. Adjust the CASE statement accordingly.

> **Performance note:** This query touches 211K tiles × multiple sample points × raster lookups. It will be slow. Run it as a batch job, not a live query. Consider processing in chunks (by province or bounding box) if it times out.

**Checklist:**
- [ ] Identify the exact land cover class IDs from the dataset documentation
- [ ] Write and test the green_score computation query on a small subset (e.g., 1000 tiles)
- [ ] Verify results make sense (forested areas score high, urban areas score low)
- [ ] Run on full dataset
- [ ] Validate: `SELECT avg(green_score), stddev(green_score) FROM scenic_score_tiles WHERE green_score > 0;`
- [ ] Confirm meaningful variance (stddev should not be tiny)

---

#### Step 4: Compute Solitude Score from Land Cover + Road Data

**Logic:** Solitude = low development + low road density. Combine land cover (proportion of undeveloped land) with existing road density data.

```
solitude_raw = (1.0 - urban_proportion) × 0.6 + (1.0 - road_density_normalized) × 0.4
solitude_score = clamp(solitude_raw, 0.0, 1.0)
```

**Urban proportion** comes from the land cover raster (proportion of urban/developed pixels in the tile).

**Road density** you may already have from the road_segments data (count of road segments per tile, normalized).

**Checklist:**
- [ ] Compute urban_proportion per tile from land cover raster
- [ ] Compute road_density_normalized per tile from road_segments (if not already available)
- [ ] Combine into solitude_score
- [ ] Validate: rural tiles should score > 0.7, downtown cores should score < 0.2

---

#### Step 5: Compute Elevation Score from DEM

**Logic:** For each H3 tile, compute the variance (or range) of elevation values within the hex boundary. High variance = interesting terrain. Low variance = flat.

```sql
UPDATE scenic_score_tiles sst
SET elevation_score = sub.elevation_score
FROM (
    SELECT
        sst2.h3_index,
        -- Normalize elevation variance to 0-1 range
        LEAST(
            (STDDEV(ST_Value(er.rast, pt.geom)) / 100.0),  -- 100m stddev = score 1.0
            1.0
        ) AS elevation_score
    FROM scenic_score_tiles sst2
    CROSS JOIN LATERAL (
        SELECT (ST_DumpPoints(sst2.geometry)).geom
    ) pt
    JOIN elevation_raster er ON ST_Intersects(er.rast, pt.geom)
    GROUP BY sst2.h3_index
) sub
WHERE sst.h3_index = sub.h3_index;
```

> **Tuning:** The `/100.0` normalization means 100m of elevation standard deviation within a single H3 tile = perfect score. Adjust this based on what you see. In the Laurentians you might see 50-150m variance per tile. On the prairies, <5m. Test and adjust the denominator.

**Checklist:**
- [ ] Compute elevation_score for a small subset (e.g., known mountainous area + known flat area)
- [ ] Verify Laurentians tiles score high, Saskatchewan tiles score low
- [ ] Adjust normalization denominator if needed
- [ ] Run on full dataset
- [ ] Validate: `SELECT avg(elevation_score), stddev(elevation_score) FROM scenic_score_tiles WHERE elevation_score > 0;`

---

#### Step 6: Recompute Composite Scenic Score

After updating `green_score`, `solitude_score`, and `elevation_score`, recompute the overall `scenic_score` for all tiles.

```sql
UPDATE scenic_score_tiles
SET scenic_score = (
    water_score    * 0.25 +
    green_score    * 0.20 +
    elevation_score * 0.20 +
    solitude_score * 0.10 +
    curve_score    * 0.10 +
    poi_score      * 0.15
);
```

Or trigger your existing scenic scoring pipeline recompute endpoint if it handles this.

**Checklist:**
- [ ] Recompute composite scores
- [ ] Validate: `SELECT avg(scenic_score), stddev(scenic_score), min(scenic_score), max(scenic_score) FROM scenic_score_tiles;`
- [ ] Compare before/after distribution — scores should have more variance now
- [ ] Spot check known locations (national parks should score high, industrial areas should score low)

---

## 📋 Full Execution Checklist

### Phase 1: Data Acquisition
- [ ] Download Canada Land Cover GeoTIFF
- [ ] Download Copernicus DEM tiles for operational area
- [ ] Reproject / clip as needed with GDAL
- [ ] Load land cover raster into PostGIS (`landcover_raster`)
- [ ] Load elevation raster into PostGIS (`elevation_raster`)
- [ ] Spot check both tables with known locations

### Phase 2: Score Computation
- [ ] Map land cover classes to green weights (document the mapping)
- [ ] Compute `green_score` for all 211K tiles
- [ ] Compute `solitude_score` (land cover + road density)
- [ ] Compute `elevation_score` from DEM variance
- [ ] Validate each score: check avg, stddev, spot check known locations

### Phase 3: Recompute and Validate
- [ ] Recompute composite `scenic_score` for all tiles
- [ ] Compare score distribution before vs after
- [ ] Spot check: national park tiles, urban cores, coastal areas, prairies
- [ ] Confirm preferences now produce meaningfully different routes (generate a test route with elevation=0.9 vs water=0.9 and verify they differ)

---

## ⏱️ Timeline Estimate

| Phase | Work | Estimate |
|---|---|---|
| Data acquisition + loading | Download, reproject, load into PostGIS | 3-4 hours |
| Green score computation | Query development, testing, full run | 3-4 hours |
| Solitude score computation | Combine land cover + road density | 2-3 hours |
| Elevation score computation | Query development, testing, full run | 2-3 hours |
| Recompute + validation | Full recompute, spot checks, distribution analysis | 1-2 hours |

**Total: ~2-3 days of focused work**

> **Warning:** The raster processing queries will be slow on 211K tiles. Budget time for the actual computation runs. Consider processing in geographic chunks and running overnight if needed.

---

## 🛑 What NOT to Do in This Phase
- ❌ Change the routing algorithm
- ❌ Change the waypoint ring generation
- ❌ Add new component scores beyond green/solitude/elevation
- ❌ Rebuild the ingestion pipeline architecture
- ❌ Optimize query performance (get correct results first, optimize later)

## ✅ After This Phase
- Component scores have real satellite-derived data behind them
- Preferences meaningfully change route selection
- "Greenery" routes actually go through forests
- "Elevation" routes actually find hills and mountains
- **Then → run the test matrix** (now with real data)
- Then → deploy live
