# Vibe Profile Calibration

Wayward's vibe profiles are backend contracts for turning user-facing route moods into route-scoring behavior.

## Current Status

The current component weights in `VibeCatalog` are seed heuristics. They are intentionally product-driven defaults, not calibrated truth.

They are defensible as first-pass mappings because each weight points to an available scenic signal:

- `coastal`: water proximity
- `mountain`: elevation and curves
- `countryside`: solitude, greenery, and low urban pressure
- `quiet`: solitude with lower POI, building-density, and urban-pressure influence
- `photo_worthy`: water, elevation, and scenic stops
- `adventure`: curves and elevation

The exact numeric values are not yet proven by user behavior or a labeled ground-truth dataset.

## What Is Authoritative

For now, the most important parts of each profile are:

- Canonical vibe id and aliases
- Target components
- Anti-components
- Strict-intent flag
- Outward-routing hint
- Availability thresholds
- Explanation template

These fields define what the backend must try to produce for a vibe. The numeric weights are adjustable defaults that should be tuned over time.

## Calibration Plan

Future calibration should use:

- Route-quality eval results across multiple cities and regions
- User route ratings
- Route component profiles for accepted/rejected routes
- Per-vibe failure analysis
- Eventually, per-user preference learning

The calibration loop should update weights only after the route generator is using vibe-specific anchors and pathfinding constraints correctly. If generation cannot reach the right route family, changing weights alone is not a real fix.

## Engineering Rule

Do not add one-off vibe logic outside `VibeCatalog` unless it is explicitly a route-generation strategy that consumes a `VibeProfile`.

The intended flow is:

1. UI label or alias
2. `VibeCatalog.normalize`
3. `VibeProfile`
4. candidate selection
5. target-anchor route generation
6. path scoring
7. route explanation
8. route-quality eval

