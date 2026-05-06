# MoodRide – Scenic Routing System (Final Engineering Plan)

## 🧭 Vision (DO NOT SKIP THIS)

**MoodRide is NOT a routing engine.**

👉 **MoodRide = Scenic Intelligence Layer**

- You decide where to go (scenic waypoints)
- OSRM decides how to get there (real roads, timing, legality)

### Core Principle

> "Routing is solved. Scenic intelligence is not."

If you ever find yourself:
- tuning edge weights
- fixing turn logic
- improving ETA manually

👉 you are going in the wrong direction.

---

## 🏗️ Current Architecture (Baseline)

### Services (Current)
- **route-api** (8080) → request handling + job creation
- **route-worker** (8081) → route generation (beam search)
- **cdc-service** (8082) → Debezium-based cache invalidation
- **notification-service** (8084) → websocket updates
- **scenic-scoring-service** (8085) → tile scoring
- **ingestion-service** (8086) → OSM ingestion

### Infrastructure
- PostgreSQL + PostGIS + H3 ✅
- Kafka (async jobs) ✅
- Redis (multi-layer caching) ✅
- Debezium (CDC) ⚠️

### ✅ Data Pipeline (Strong – Keep This)
⚙️ Scenic Scoring System
- 3M+ road segments ingested from Canada OSM data
- 211,510 H3 scenic tiles computed
- All tiles scored (avg_score = 0.3155, 100% completion)
- Pre-aggregation tables built(211,510 rows each):
  - water
  - landuse
  - POI
- Scenic scoring pipeline complete

👉 **This is your moat. Do not mess this up.**

---

## 🚗 Current Route Generation (Needs Replacement)

### Current approach:
1. Build graph from road_segments
2. Attach H3 scenic scores
3. Run beam search

### Heuristic:
```
time += length / constant_speed
score += edge.scenicScore
```

### Problems:
- ❌ No loop (critical)
- ❌ Fake ETA
- ❌ Longer routes always win
- ❌ Preferences ignored

---

## 🚨 Current Critical Gaps

These are blocking product quality:

- ❌ No loop generation
- ❌ Inaccurate travel time
- ❌ Preference system not used
- ❌ Additive scoring is broken
- ❌ Over-engineered services (CDC, etc.)

---



## 🧠 Core Data Model (CRITICAL CHANGE)

### Current (Not Enough)
```
scenic_score FLOAT
```

### Required (Add These)
```
water_score FLOAT
green_score FLOAT
elevation_score FLOAT
solitude_score FLOAT
curve_score FLOAT
poi_score FLOAT
```

👉 **This enables:**
- personalization
- real preferences
- different route styles

---

## ⚙️ Core Algorithm (NEW SYSTEM)

### Step 1: Tile Selection

**Input:**
- start location
- time budget
- preference vector

**Process:**
1. compute search radius
2. fetch nearby H3 tiles
3. score tiles using preferences
4. filter high-quality tiles

### Step 2: Waypoint Ring Generation

**Goal:** 👉 create a loop structure BEFORE routing

**Algorithm:**
1. Divide area into sectors (e.g. 8)
2. Select top tile per sector
3. Build multiple variants:
   - 4 / 6 / 8 waypoints
   - different radii
4. Form candidate rings

### Step 3: OSRM Routing

**Call:**
```
/trip/v1/driving?...roundtrip=true
```

**Returns:**
- real route
- real duration
- polyline
- turn structure

### Step 4: Route Corridor Scoring

For each route:
1. Sample every ~500m
2. Map each point → H3 tile
3. Compute: `scenic_density = avg(tile scores)`

### Step 5: Final Selection

Choose best route:
```
best = max(scenic_density)
```

(keep simple initially)

---

## 🎯 Preference System (REAL IMPLEMENTATION)

### API Input
```json
{
  "preferences": {
    "water": 0.8,
    "greenery": 0.9,
    "elevation": 0.3,
    "solitude": 0.7,
    "curves": 0.5,
    "poi": 0.2
  }
}
```

### Tile Scoring
```
score =
  tile.waterScore * prefs.water +
  tile.greenScore * prefs.greenery +
  tile.elevation * prefs.elevation +
  tile.solitude * prefs.solitude +
  tile.curveScore * prefs.curves +
  tile.poiScore * prefs.poi;
```

### Vibes → Preferences Mapping

Example:
```
"coastal" →
  water=0.9, greenery=0.7, solitude=0.6
```

---

## 🗺️ Execution Plan (FOLLOW THIS EXACTLY)

### 🔥 WEEK 1 — FOUNDATION

**Component Scores:**
- [ ] Add component scores to schema
- [ ] Update scoring pipeline
- [ ] Recompute tiles (all 211,510)

**OSRM Integration:**
- [ ] Run via Docker
- [ ] Test /trip endpoint
- [ ] Add OSRM client in worker

### ⚡ WEEK 2 — CORE LOGIC

**Replace Beam Search:**
- [ ] ❌ REMOVE: edge-based search
- [ ] ✅ ADD: tile-based waypoint generation

**Implement Waypoint Ring Generator:**
- [ ] sector-based selection
- [ ] multiple variants (4/6/8 waypoints)

**Implement Route Scoring:**
- [ ] polyline sampling
- [ ] H3 lookup
- [ ] density calculation

### 🎯 WEEK 3 — PRODUCT

**Enable Preferences:**
- [ ] API → worker
- [ ] scoring uses preference vector

**Return Multiple Routes:**
- [ ] Return 3 options:
  - most scenic
  - balanced
  - shorter

**Fix Scoring Model:**
- [ ] ❌ remove additive scoring
- [ ] ✅ use scenic density

---

## 🛑 What to STOP Doing
- ❌ Custom routing logic
- ❌ Edge-based beam search

## ✅ What You KEEP
- H3 tile system
- scoring pipeline
- Kafka async jobs
- PostGIS data
- Redis (route caching only)
