# MoodRide Frontend Rebuild Implementation Plan

## 1. Goal
Rebuild the MoodRide Next.js frontend into a map-first, portfolio-grade scenic route generation interface, applying the "Sage + Cream" (Archetype E) design system and layout inspirations from the provided HTML references.

## 2. Component Architecture Refactoring

The current `RoutePlanner.tsx` is a monolithic 1000-line component mixing state, business logic, and UI rendering. We will refactor it by extracting the UI into focused sub-components while preserving the core state and API interactions in the parent (or a custom hook).

### New Component Structure:
*   `RoutePlanner.tsx` (Main Orchestrator): Holds state (lat, lng, vibes, phase, route, etc.), API calls, and layout structure.
*   `MapCanvas.tsx` (Replaces `RouteMap.tsx`): The full-bleed background map. Needs to be visible from the start, showing the current location or search area before a route is generated.
*   `Header.tsx`: The top navigation bar (from `start-drive-handoff.html` and `technical-map-workspace.html`), containing the logo, beta tags, and optional nav links.
*   `PlannerPanel.tsx`: The floating or left-anchored control panel containing the location search, travel mode, time budget, vibe selection, and the primary "Generate Route" CTA.
*   `LoadingOverlay.tsx`: The animated progress overlay shown during the "submitting" and "tracking" phases, inspired by `route-loader.html`.
*   `RouteResultsPanel.tsx`: The panel shown after generation, displaying route options, metrics (distance, duration, score), explanation bars, and scenic highlights.
*   `StartDriveHandoff.tsx`: The modal or dedicated view for the "Ready for the road?" handoff, containing Google Maps, Apple Maps, GPX export actions, and the journey summary.

## 3. Design System Integration

We will replace `globals.css` with a new version based on `design(1).md`.

*   **Colors**: Sage (`#e8f0e0`), Surface (`#f5f9f0`), Card (`#ffffff`), Primary (`#1a2e1a`), Accent (`#6aaa2a`).
*   **Typography**: Syne (Display/Headings) and DM Sans (Body/UI). We will update `layout.tsx` to load these fonts from Google Fonts.
*   **Layout**: Map-first. The map takes up the full viewport (`100vh`, `100vw`). The `PlannerPanel` and `RouteResultsPanel` float over the map (e.g., left-aligned on desktop, bottom sheet on mobile).
*   **Components**: Pill-shaped buttons, rounded cards (`24px`), Lucide icons (replacing Material Symbols where possible, or adapting Material Symbols to match the weight).

## 4. Execution Steps

1.  **Phase 4: Design Tokens & Layout Setup**
    *   Update `src/app/layout.tsx` to load Syne and DM Sans.
    *   Completely rewrite `src/app/globals.css` with the new CSS variables, typography rules, utility classes, and base layout styles.
    *   Add Lucide React or configure Material Symbols to match the design system's stroke weight.

2.  **Phase 5: Map-First Layout & Idle State**
    *   Refactor `RouteMap.tsx` into `MapCanvas.tsx` so it renders immediately, even without a generated route.
    *   Create `Header.tsx`.
    *   Create `PlannerPanel.tsx` by extracting the form controls (location, mode, time, vibes) from `RoutePlanner.tsx`.
    *   Assemble these in `RoutePlanner.tsx` to form the "idle" state.

3.  **Phase 6: Loading State**
    *   Create `LoadingOverlay.tsx` based on `route-loader.html`.
    *   Integrate it into `RoutePlanner.tsx` to show during `phase === "submitting" || phase === "tracking"`.

4.  **Phase 7: Completed Route State**
    *   Create `RouteResultsPanel.tsx` to handle the route options, metrics, and explanations.
    *   Refactor `ScenicHighlightsPanel.tsx` to match the new card/typography styles.

5.  **Phase 8: Handoff & Failed States**
    *   Create `StartDriveHandoff.tsx` based on `start-drive-handoff.html`.
    *   Style the failed state message gracefully.
    *   Ensure debug information is collapsed and styled cleanly.

6.  **Phase 9: Polish & Integration**
    *   Ensure mobile responsiveness (bottom sheets/stacking).
    *   Add subtle animations (staggered reveals, button presses) as defined in the design system.

7.  **Phase 10: Verification**
    *   Run `npm run lint` and `npm run build` to ensure no TypeScript or build errors were introduced.
