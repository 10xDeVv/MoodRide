# Product Analytics

Wayward tracks anonymous product analytics so route quality and usage can be measured without user accounts or personal identity.

## Privacy Contract

- No names, emails, phone numbers, or account identifiers are stored.
- The browser creates a random anonymous session id in local storage.
- Events may include job id, route id, route profile, route mode, selected vibes, time budget, route count, generation duration, scenic score, and small non-identifying metadata.
- Precise start coordinates are not sent as analytics metadata.

## Events

- `route_generate_clicked`: user clicked generate.
- `route_generate_submitted`: API accepted a route job.
- `route_generation_completed`: a route job returned route options.
- `route_generation_failed`: route generation failed.
- `vibe_unavailable`: selected vibe could not be honestly satisfied nearby.
- `route_option_selected`: user selected Most Scenic, Balanced, or Shorter.
- `start_drive_clicked`: user opened the drive handoff modal.
- `navigation_opened`: user opened Google Maps or Apple Maps.
- `gpx_exported`: user exported route data.
- `plan_new_route_clicked`: user returned to planning.
- `route_results_minimized`: user hid route results.
- `route_results_viewed`: user reopened route results.
- `location_selected`: user selected a searched location.
- `geolocate_clicked`: user tried browser geolocation.
- `theme_toggled`: user switched light/dark mode.

## Metrics Enabled

- Routes generated per day/week.
- Route generation success rate vs failure/unavailable rate.
- Average generation duration.
- Average route options returned.
- Most selected vibes.
- Most selected route profile.
- Start-drive click-through rate.
- Navigation handoff intent.
- Plan-new-route rate.
- Average scenic score for completed routes.

## API

- Record event: `POST /api/analytics/events`
- Aggregate summary: `GET /api/analytics/summary?days=30`

The summary endpoint reports aggregate product metrics for the requested window, capped to 90 days.
