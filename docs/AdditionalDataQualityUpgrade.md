

# MoodRide – Extended Data Sources Execution Plan

## 🧭 Context

The core data quality upgrade (Canada Land Cover + Copernicus DEM) addresses the three weakest component scores: `green_score`, `solitude_score`, and `elevation_score`.

This plan covers **additional data sources** to integrate after the core upgrade is validated. These sources add incremental scenic intelligence on top of the foundation.

### Core Principle

> "Only add a data source if it solves a specific scoring problem you can identify from test results."

Do not integrate these speculatively. Run the test matrix after the core data upgrade. Identify where routes still feel wrong. Then pick the source that addresses that specific gap.

---

## 📊 Current Component Score Sources (After Core Upgrade)

| Component | Current Source | Quality |
|---|---|---|
| `water_score` | OSM water features + pre-aggregation | ✅ Good |
| `green_score` | Canada Land Cover (satellite, 30m) | ✅ Good (after upgrade) |
| `elevation_score` | Copernicus DEM (30m) | ✅ Good (after upgrade) |
| `solitude_score` | Land cover + road density | ⚠️ Adequate but improvable |
| `curve_score` | Road geometry sinuosity | ✅ Good |
| `poi_score` | OSM POI tags | ⚠️ Inconsistent coverage |

**This plan targets the ⚠️ components and adds new scoring dimensions.**

---

## 📋 Integration Priority Order

Ranked by impact-per-effort. Do them in this order, stopping when routes feel good enough.

---

### 🔥 Priority 1: Protected Areas / National Parks (CPCAD)

**Why first:** Lowest effort, highest confidence signal. A tile inside a national park is almost guaranteed to be scenic. This is a simple boolean boost that immediately improves route quality near parks.

**Dataset:** Canadian Protected and Conserved Areas Database

**URL:** https://www.canada.ca/en/environment-climate-change/services/national-wildlife-areas/protected-conserved-areas-database.html

**Format:** Shapefile / GeoJSON, free, open data

**What it provides:**
- Boundaries of all national parks, provincial parks, conservation areas, wildlife reserves, marine protected areas
- Protection level classification (IUCN categories)

**How to use it:**

Create a park proximity / overlap score per H3 tile:

```sql
-- Load CPCAD boundaries into PostGIS
-- Table: protected_areas (geometry, name, iucn_category, designation_type)

-- Compute park_score per tile
UPDATE scenic_score_tiles sst
SET park_score = sub.park_score
FROM (
    SELECT
        sst2.h3_index,
        CASE
            -- Tile is inside a protected area
            WHEN EXISTS (
                SELECT 1 FROM protected_areas pa
                WHERE ST_Intersects(pa.geometry, sst2.geometry)
            ) THEN 1.0
            -- Tile is within 2km of a protected area
            WHEN EXISTS (
                SELECT 1 FROM protected_areas pa
                WHERE ST_DWithin(pa.geometry::geography, sst2.geometry::geography, 2000)
            ) THEN 0.6
            -- Tile is within 5km of a protected area
            WHEN EXISTS (
                SELECT 1 FROM protected_areas pa
                WHERE ST_DWithin(pa.geometry::geography, sst2.geometry::geography, 5000)
            ) THEN 0.3
            ELSE 0.0
        END AS park_score
    FROM scenic_score_tiles sst2
) sub
WHERE sst.h3_index = sub.h3_index;
```

**Schema change:**
```sql
ALTER TABLE scenic_score_tiles ADD COLUMN IF NOT EXISTS park_score FLOAT DEFAULT 0.0;
```

**Integration into composite score:**

Option A — Add as a new component:
```
scenic_score = water * 0.20 + green * 0.18 + elevation * 0.18 + 
               solitude * 0.10 + curve * 0.10 + poi * 0.12 + park * 0.12
```

Option B — Use as a multiplier on existing scores:
```
scenic_score = base_score * (1.0 + park_score * 0.3)
```

Option B is simpler and doesn't require rebalancing all weights. Start with B.

**Checklist:**
- [ ] Download CPCAD dataset
- [ ] Load into PostGIS (`protected_areas` table)
- [ ] Verify with known parks (Algonquin, Banff, Fundy)
- [ ] Add `park_score` column to `scenic_score_tiles`
- [ ] Compute park_score for all 211K tiles
- [ ] Update composite score calculation
- [ ] Validate: tiles inside known national parks should score significantly higher

**Effort:** 3-4 hours

---

### 🔥 Priority 2: Light Pollution Data

**Why second:** Single raster file, same processing pipeline as elevation/land cover, directly and significantly improves `solitude_score`. Dark areas feel remote. Bright areas feel suburban. This is a stronger signal than road density alone.

**Dataset:** World Atlas of Artificial Night Sky Brightness (Falchi et al. 2016) or VIIRS Nighttime Lights (NASA)

**URLs:**
- Light pollution map data: https://www.lightpollutionmap.info
- Academic dataset: https://doi.org/10.5880/GFZ.1.4.2016.001
- VIIRS Nighttime Lights: https://earthdata.nasa.gov/topics/human-dimensions/nighttime-lights

**Format:** GeoTIFF raster, free

**Resolution:** ~750m (light pollution atlas) or ~500m (VIIRS)

**How to use it:**

```bash
# Load into PostGIS (same approach as elevation/land cover)
raster2pgsql -s 4326 -t 256x256 -I -C -M light_pollution.tif public.light_pollution_raster | psql -d moodride
```

```sql
-- Compute darkness_score per tile (inverse of light pollution)
UPDATE scenic_score_tiles sst
SET darkness_score = sub.darkness_score
FROM (
    SELECT
        sst2.h3_index,
        -- Light pollution values: higher = more polluted
        -- Invert and normalize: dark areas score high
        GREATEST(0.0, LEAST(1.0,
            1.0 - (AVG(ST_Value(lp.rast, pt.geom)) / max_brightness_value)
        )) AS darkness_score
    FROM scenic_score_tiles sst2
    CROSS JOIN LATERAL (
        SELECT (ST_DumpPoints(sst2.geometry)).geom
    ) pt
    JOIN light_pollution_raster lp ON ST_Intersects(lp.rast, pt.geom)
    GROUP BY sst2.h3_index
) sub
WHERE sst.h3_index = sub.h3_index;
```

> **Note:** `max_brightness_value` depends on the dataset units. Check the documentation. For VIIRS nanoWatts/cm²/sr, typical urban values are 50-200, rural values are <1.

**Use darkness_score to improve solitude_score:**

```sql
UPDATE scenic_score_tiles
SET solitude_score = (
    solitude_score * 0.5 +      -- existing road density / land cover signal
    darkness_score * 0.5         -- light pollution signal
);
```

Or add `darkness_score` as a standalone component that feeds into preferences (users who want "remote" or "peaceful" routes).

**Schema change:**
```sql
ALTER TABLE scenic_score_tiles ADD COLUMN IF NOT EXISTS darkness_score FLOAT DEFAULT 0.0;
```

**Checklist:**
- [ ] Download light pollution raster (VIIRS or Falchi atlas)
- [ ] Clip to Canada / operational area
- [ ] Load into PostGIS (`light_pollution_raster`)
- [ ] Compute `darkness_score` for all tiles
- [ ] Integrate into `solitude_score` or add as separate component
- [ ] Validate: downtown Toronto should score ~0.0, northern Ontario wilderness should score ~1.0

**Effort:** 3-4 hours (same pipeline as elevation data)

---

### ⚡ Priority 3: Photo Density (Flickr API)

**Why third:** Crowdsourced scenic validation. Where people take photos = what people think is worth looking at. This is an independent signal that validates (or challenges) your geographic scoring.

**API:** Flickr API — `flickr.photos.search` with bounding box

**URL:** https://www.flickr.com/services/api/flickr.photos.search.html

**Free tier:** 3600 requests/hour with API key (free to obtain)

**How to use it:**

For each H3 tile (or a sample), query Flickr for geotagged photos within the tile boundary:

```python
# Pseudocode for batch processing
import requests

def get_photo_count(lat, lng, radius_km=1.0):
    response = requests.get('https://api.flickr.com/services/rest/', params={
        'method': 'flickr.photos.search',
        'api_key': FLICKR_API_KEY,
        'lat': lat,
        'lon': lng,
        'radius': radius_km,
        'radius_units': 'km',
        'min_taken_date': '2015-01-01',  # recent photos only
        'per_page': 1,  # we only need the count
        'format': 'json',
        'nojsoncallback': 1
    })
    data = response.json()
    return int(data['photos']['total'])
```

**Rate limiting consideration:** 211K tiles at 3600 req/hour = ~59 hours of continuous querying. Strategies:
- Sample only tiles that already have moderate-to-high scenic scores (skip urban cores and empty areas)
- Use a coarser grid first (parent H3 resolution), then drill down
- Run over multiple days
- Cache results — photo density doesn't change quickly

**Schema change:**
```sql
ALTER TABLE scenic_score_tiles ADD COLUMN IF NOT EXISTS photo_density_score FLOAT DEFAULT 0.0;
```

**Score computation:**
```
photo_density_score = min(1.0, log10(photo_count + 1) / log10(reference_max + 1))
```

Log scale because photo counts follow a power law (a few tourist spots have thousands of photos, most places have few).

**Checklist:**
- [ ] Obtain Flickr API key
- [ ] Write batch photo density fetcher
- [ ] Test on a small tile sample (~100 tiles across different area types)
- [ ] Validate: known scenic spots (Niagara, Peggy's Cove) should score high
- [ ] Run on full dataset (plan for multi-day execution)
- [ ] Integrate into composite score or use as validation signal

**Effort:** 1-2 days (mostly waiting for API rate limits)

---

### ⚡ Priority 4: Overture Maps (Better POIs + Building Density)

**Why fourth:** Replaces your weakest data source (OSM POIs) with much richer data. Also provides building footprints for better solitude scoring.

**URL:** https://overturemaps.org/download/

**Format:** GeoParquet, partitioned by theme (places, buildings, transportation, land use)

**Size:** Large — Canada extract will be multiple GB

**What to extract:**

**Places (POIs):**
- Scenic viewpoints, parks, beaches, waterfalls, historic sites
- Restaurants, cafes (nice stops along a scenic drive)
- Filter out non-scenic POIs (gas stations, banks, etc.)

**Buildings:**
- Building footprint density per tile
- High density = urban, low density = rural
- Stronger signal for `solitude_score` than road density alone

**How to process:**

```bash
# Download Overture places theme for Canada
# Filter to relevant categories
# Load into PostGIS

# Using DuckDB to extract (Overture's recommended approach)
duckdb -c "
    COPY (
        SELECT id, geometry, categories, names
        FROM read_parquet('s3://overturemaps-us-west-2/release/2024-*/theme=places/type=place/*')
        WHERE bbox.minX > -141 AND bbox.maxX < -52
          AND bbox.minY > 41 AND bbox.maxY < 84
    ) TO 'canada_places.parquet';
"
```

**Checklist:**
- [ ] Download Overture places + buildings themes for Canada
- [ ] Filter places to scenic-relevant categories
- [ ] Load into PostGIS
- [ ] Compute improved `poi_score` from Overture places
- [ ] Compute building density per tile for `solitude_score` refinement
- [ ] Compare old vs new POI coverage

**Effort:** 4-6 hours

---

### 🎯 Priority 5: Weather API (OpenMeteo)

**Why fifth:** This is different from all the others — it's a **real-time** signal, not an offline computation. It adjusts scores at route generation time based on current or forecasted conditions.

**URL:** https://open-meteo.com/

**Format:** REST API, free, no key required

**What it provides:**
- Current conditions: temperature, precipitation, cloud cover, visibility, wind
- Forecasts: hourly and daily
- Historical averages by month

**How to use it:**

At route generation time (in the route-worker), after selecting candidate waypoint rings:

```java
// Before scoring candidates, fetch weather for the area
WeatherData weather = openMeteoClient.getCurrentWeather(startLat, startLng);

// Apply weather modifier to scenic density scoring
double weatherModifier = computeWeatherModifier(weather);

// Example modifiers:
// Clear sky, good visibility     → 1.2 (boost)
// Partly cloudy                  → 1.0 (neutral)
// Light rain                     → 0.8 (mild penalty)
// Heavy rain / fog               → 0.5 (strong penalty)
// Snow (winter scenic)           → 1.1 (slight boost — snow is scenic)

candidateScore = scenicDensity * weatherModifier;
```

**This does NOT change stored tile scores.** It only modifies the real-time route selection. Same tiles, same components, but weather adjusts which candidates win.

**API call example:**
```
GET https://api.open-meteo.com/v1/forecast
    ?latitude=45.94
    &longitude=-66.63
    &current=temperature_2m,precipitation,cloud_cover,visibility,wind_speed_10m
```

**Checklist:**
- [ ] Add OpenMeteo client to route-worker
- [ ] Fetch current weather at route generation time
- [ ] Define weather modifier rules
- [ ] Apply modifier to candidate scoring
- [ ] Test: generate routes in rain vs clear conditions and verify different candidates are selected
- [ ] Add weather info to route response (optional: "Current conditions: Clear, 22°C")

**Effort:** 3-4 hours

---

## 🗓️ Integration Timeline

**Do these ONLY after the core data upgrade (Land Cover + DEM) is complete and the test matrix has been run.**

| Priority | Source | When | Effort | Trigger |
|---|---|---|---|---|
| 1 | CPCAD Parks | First, after test matrix | 3-4 hours | Routes don't prefer obvious scenic areas (parks) |
| 2 | Light Pollution | Second | 3-4 hours | Solitude preference doesn't meaningfully differentiate urban vs rural |
| 3 | Flickr Photo Density | Third | 1-2 days | POI scores are flat, need independent scenic validation |
| 4 | Overture Maps | Fourth | 4-6 hours | OSM POI coverage has obvious gaps |
| 5 | Weather API | Fifth | 3-4 hours | After all static scoring is solid, add real-time awareness |

---

## 🔮 Future Sources (Not Now)

These are valuable but significantly more complex. Consider only if pursuing MoodRide as a real product beyond portfolio.

| Source | What It Does | Why Defer |
|---|---|---|
| **NDVI / Sentinel-2 vegetation index** | Seasonal greenness from satellite imagery | Time series data, periodic refresh, large storage — adds seasonal awareness but high complexity |
| **Viewshed analysis (from DEM)** | Computes visible area from road points | Computationally very expensive, requires line-of-sight calculations per sample point |
| **Strava Metro data** | Where cyclists/runners recreate | Requires partnership application, not freely available |
| **iNaturalist biodiversity** | Wildlife/plant observation density | Interesting signal but weak correlation with driving scenic quality |
| **Road surface quality (OSM `surface` tag)** | Paved vs gravel vs dirt | Low priority — most scenic drives in Canada are on paved roads |
| **Noise pollution maps** | Ambient noise levels | Very limited data availability in Canada |

---

## 🛑 Rules for This Phase
- ❌ Do not integrate any source before completing the core data upgrade
- ❌ Do not integrate any source before running the test matrix
- ❌ Do not integrate a source unless test results show a specific gap it would fix
- ❌ Do not change the routing algorithm — these are data layer changes only
- ✅ Each source follows the same pattern: download → load → compute per tile → update scores → validate

## ✅ After This Phase
- Component scores backed by multiple high-quality data sources
- Preferences produce meaningfully different routes across all test scenarios
- Routes through national parks are appropriately favored
- Remote/wilderness routes are clearly differentiated from suburban routes
- Optional: weather-aware scoring adds real-time intelligence
- **Then → deploy live**