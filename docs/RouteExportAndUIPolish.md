

# MoodRide – Route Export & UI Polish Execution Plan

## 🧭 Context

The hybrid routing system is complete. Routes generate, loop back, OSRM provides real timing, and three options are returned. The core engine works.

This plan covers the next build phase: **making the product usable and presentable.**

### What This Plan Covers
1. "Start Drive" → auto-open in Google Maps / Apple Maps
2. GPX export as secondary option
3. Fix identical scenic scores across route options
4. Clean up UI — remove debug info, fix Scenic Highlights
5. Hide developer-facing data from user view

### Core Principle

> "The backend works. Now make the frontend worthy of it."

No new backend services. No new infrastructure. Frontend changes + one small API endpoint for GPX.

---

## 🚨 Known Issues (From Live UX Proof)

| Issue | Severity | Section |
|---|---|---|
| All 3 route options show score 30.00 | **High** — breaks the multi-option value | Fix in Phase 1 |
| "Start Drive" opens Mapbox signup page | **High** — product is unusable for actual driving | Fix in Phase 2 |
| Scenic Highlights show "Continue to waypoint 430" | **Medium** — meaningless to users | Fix in Phase 3 |
| Nearby Scenic Regions shows H3 hex IDs | **Medium** — debug data, not user-facing | Fix in Phase 3 |
| UI shows Job ID, WS Channel, Retry count, etc. | **Medium** — implementation details visible | Fix in Phase 3 |

---

## 📋 Execution Plan

### 🔥 PHASE 1 — Fix Scoring Display (Do This First)

**Problem:** All three route options display `score 30.00`. Either the backend computes identical scores or the frontend isn't reading per-route scores correctly.

**Diagnosis steps:**
- [ ] Query the database for the most recent 3-option job:
```sql
SELECT
  route_profile,
  distance_km,
  duration_minutes,
  scenic_score
FROM routes
WHERE job_id = '<recent_job_id>'
ORDER BY generated_at;
```
- [ ] If scores differ in DB → frontend display bug. Fix the component reading `scenic_score` per option.
- [ ] If scores are identical in DB → backend scoring bug. Trace `RoutePlanner` corridor scoring to confirm each candidate gets a unique scenic density value.

**Acceptance criteria:**
- Three route options show meaningfully different scenic scores
- "Most Scenic" has the highest score
- "Shorter" has the lowest score (or close)

---

### ⚡ PHASE 2 — Start Drive & GPX Export

#### 2A: "Start Drive" → Auto-Open in Maps App

**Goal:** One tap → user's preferred maps app opens with the scenic route loaded. No downloads, no signups, no friction.

**Approach:** Construct a URL with key waypoints sampled from the route polyline and open it in Google Maps (default) or Apple Maps (iOS).

**Why NOT Mapbox Navigation SDK:**
- Paid per-trip pricing
- Requires user signup
- Rebuilds what every phone already has

**Why NOT in-browser turn-by-turn:**
- Months of work to build
- Every user already has Google Maps / Apple Maps
- We are a scenic intelligence layer, not a navigation app

**Google Maps URL format:**
```
https://www.google.com/maps/dir/?api=1
  &origin={startLat},{startLng}
  &destination={startLat},{startLng}
  &waypoints={lat1},{lng1}|{lat2},{lng2}|...
  &travelmode=driving
```

- Origin and destination are the same (loop route)
- Maximum ~25 waypoints in URL
- Google Maps recalculates road-level routing between waypoints

**Apple Maps URL format (iOS only):**
```
https://maps.apple.com/?saddr={startLat},{startLng}
  &daddr={lat1},{lng1}+to:{lat2},{lng2}+to:...+to:{startLat},{startLng}
```

**Implementation:**

```typescript
function startDrive(route: Route) {
  const keyPoints = sampleWaypoints(route.coordinates, 15);

  const origin = keyPoints[0];
  const destination = keyPoints[keyPoints.length - 1];
  const waypoints = keyPoints.slice(1, -1);

  const isIOS = /iPad|iPhone|iPod/.test(navigator.userAgent);

  if (isIOS) {
    const daddr = waypoints
      .map(p => `${p.lat},${p.lng}`)
      .join('+to:');
    const url = `https://maps.apple.com/?saddr=${origin.lat},${origin.lng}`
      + `&daddr=${daddr}+to:${destination.lat},${destination.lng}`;
    window.open(url, '_blank');
  } else {
    const waypointStr = waypoints
      .map(p => `${p.lat},${p.lng}`)
      .join('|');
    const url = `https://www.google.com/maps/dir/?api=1`
      + `&origin=${origin.lat},${origin.lng}`
      + `&destination=${destination.lat},${destination.lng}`
      + `&waypoints=${waypointStr}`
      + `&travelmode=driving`;
    window.open(url, '_blank');
  }
}
```

**Waypoint sampling strategy:**

The route polyline has hundreds of points (e.g., 859). We need ~15 key waypoints that preserve the route shape.

```typescript
function sampleWaypoints(coords: Coordinate[], maxPoints: number): Coordinate[] {
  if (coords.length <= maxPoints) return coords;

  const sampled: Coordinate[] = [coords[0]]; // always include start
  const step = Math.floor(coords.length / (maxPoints - 1));

  for (let i = step; i < coords.length - 1; i += step) {
    sampled.push(coords[i]);
  }

  sampled.push(coords[coords.length - 1]); // always include end
  return sampled;
}
```

> **Note:** Evenly spaced sampling is fine for v1. A heading-change-based sampler (picking points where the route turns) would be better but is not required now. The even spacing will keep Google Maps on the scenic path in most cases.

**Checklist:**
- [ ] Remove current "Start Drive" Mapbox navigation link
- [ ] Implement `sampleWaypoints()` utility function
- [ ] Implement `startDrive()` with Google Maps / Apple Maps URL construction
- [ ] Add iOS detection for Apple Maps
- [ ] Wire "Start Drive" button to new function
- [ ] Test on desktop (should open Google Maps in browser)
- [ ] Test on mobile if possible (should open maps app)

---

#### 2B: GPX Export (Secondary Action)

**Goal:** Power users can download the full route as a GPX file for use in any GPS app, car navigation system, or fitness tracker.

**Implementation — Frontend approach (no backend endpoint needed):**

```typescript
function exportGpx(route: Route, routeName: string) {
  const points = route.coordinates
    .map(p => `      <trkpt lat="${p.lat}" lon="${p.lng}" />`)
    .join('\n');

  const gpx = `<?xml version="1.0" encoding="UTF-8"?>
<gpx version="1.1" creator="MoodRide">
  <trk>
    <name>${routeName}</name>
    <trkseg>
${points}
    </trkseg>
  </trk>
</gpx>`;

  const blob = new Blob([gpx], { type: 'application/gpx+xml' });
  const url = URL.createObjectURL(blob);
  const a = document.createElement('a');
  a.href = url;
  a.download = `${routeName.replace(/\s+/g, '_')}.gpx`;
  a.click();
  URL.revokeObjectURL(url);
}
```

**Button layout:**
```
[  🚗 Start Drive  ]    [ 📥 Export GPX ]
```

- "Start Drive" is primary (larger, prominent color)
- "Export GPX" is secondary (smaller, outline style)

**Checklist:**
- [ ] Implement `exportGpx()` function
- [ ] Add "Export GPX" button next to "Start Drive"
- [ ] Style as secondary action (outline button, smaller)
- [ ] Test download produces valid GPX file
- [ ] Verify GPX opens in Google Earth or similar tool

---

### 🎯 PHASE 3 — UI Cleanup

**Goal:** The user should see: map, route options, rating, and action buttons. Nothing else.

#### 3A: Hide Debug Information

**Move behind a toggle or remove entirely:**

| Element | Action |
|---|---|
| Job ID | Hide (move to debug toggle) |
| WS Channel | Hide |
| Backend status: COMPLETED | Hide |
| Retry: 0/2 | Hide |
| Estimated completion: 5s | Hide |
| "Route completed via polling fallback" | Hide |
| Algorithm: hybrid_osrm_v1 | Hide |

**Implementation:**
- [ ] Add a `showDebug` state toggle (default: false)
- [ ] Wrap all debug elements in `{showDebug && ...}`
- [ ] Add a small "🔧 Debug" toggle link at the bottom of the page
- [ ] All debug info visible when toggled, hidden by default

---

#### 3B: Fix Scenic Highlights

**Current (useless):**
```
start: Continue to waypoint 1
midpoint: Continue to waypoint 430
destination: Arrive at destination
```

**Short-term fix (this phase):** Replace with component-score-based description.

Use the route's dominant scenic components to generate a simple description:

```typescript
function generateHighlights(route: Route, preferences: PreferenceVector): string[] {
  const highlights: string[] = [];

  // Use the vibe/profile to describe the route character
  highlights.push(
    `${route.profile} loop · ${route.distance_km.toFixed(1)} km · ${route.duration_minutes} min`
  );

  // Add preference-based descriptions
  if (preferences.water > 0.5) highlights.push('Follows waterways and lakeshores');
  if (preferences.greenery > 0.5) highlights.push('Routes through green spaces and parks');
  if (preferences.elevation > 0.5) highlights.push('Includes elevation changes and hill views');
  if (preferences.solitude > 0.5) highlights.push('Favors quiet, low-traffic roads');
  if (preferences.curves > 0.5) highlights.push('Features winding, interesting road geometry');

  return highlights;
}
```

> **Future improvement (not now):** Reverse geocode waypoints to get actual place names ("Follows the Saint John River through Devon"). This requires a geocoding API call and is a polish item for later.

**Checklist:**
- [ ] Replace current Scenic Highlights with preference-based descriptions
- [ ] Remove "Continue to waypoint N" text
- [ ] Show route profile, distance, duration as the first highlight line

---

#### 3C: Hide Nearby Scenic Regions (or Make It Useful)

**Current:** Raw H3 hex IDs with confidence scores. Meaningless to users.

**Action:** Hide behind debug toggle.

- [ ] Move Nearby Scenic Regions into `{showDebug && ...}` block
- [ ] If time permits: replace with a simple "This area has high scenic potential for: waterfront, greenery" summary (no hex IDs)

---

## 🧹 Final UI State (After Phase 3)

**User sees:**
```
┌──────────────────────────────────────────────────┐
│  Route Request        │  Route Map               │
│  [location input]     │  [Mapbox GL map with     │
│  [time budget]        │   route polyline]         │
│  [vibe selector]      │                           │
│  [Submit Route]       │                           │
│                       │                           │
│                       ├──────────────────────────-│
│                       │  Route Details            │
│                       │  [Most Scenic] [Balanced] │
│                       │  [Shorter]                │
│                       │                           │
│                       │  Scenic Highlights        │
│                       │  • Countryside loop 39 km │
│                       │  • Follows waterways      │
│                       │  • Favors quiet roads     │
│                       │                           │
│                       │  [🚗 Start Drive]         │
│                       │  [📥 Export GPX]          │
│                       │                           │
│                       │  Rate This Drive          │
│                       │  [1] [2] [3] [4] [5]     │
│                       │                           │
│                       │  🔧 Debug (toggle)        │
└──────────────────────────────────────────────────┘
```

---

## 📅 Timeline

| Phase | Work | Estimate |
|---|---|---|
| Phase 1 | Fix identical scores | 1-2 hours (diagnosis + fix) |
| Phase 2A | Start Drive → Google/Apple Maps | 2-3 hours |
| Phase 2B | GPX Export | 1-2 hours |
| Phase 3A | Hide debug info | 1 hour |
| Phase 3B | Fix Scenic Highlights | 1-2 hours |
| Phase 3C | Hide Scenic Regions | 30 min |

**Total: ~1-2 days of focused work**

---

## 🛑 What NOT to Do in This Phase
- ❌ Build in-browser navigation
- ❌ Integrate Mapbox Navigation SDK
- ❌ Reverse geocode waypoints for place names (future polish)
- ❌ Redesign the full UI layout
- ❌ Add new backend services or infrastructure
- ❌ Touch the routing algorithm

## ✅ After This Phase
- Fix the identical scores → scoring is trustworthy
- Start Drive works → product is usable for real driving
- GPX export works → power users served
- UI is clean → product is presentable
- Then → run the full test matrix across 8-10 locations
- Then → deploy live