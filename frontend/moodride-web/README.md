# Wayward Web Frontend

Production-oriented Next.js frontend for route generation, async job tracking, and route visualization.

## Features

- Location picker (geolocation + place search + manual coordinates)
- Time budget slider and vibe multi-select (max 3)
- Route submission via Kong (`/api/routes`)
- Job tracking via STOMP-over-SockJS + polling fallback
- Route rendering map (Mapbox with no-key fallback preview) + scenic highlights panel
- Responsive layout for mobile and desktop

## Environment

Copy `.env.example` to `.env.local` and set values.

- `NEXT_PUBLIC_API_BASE_URL`: Kong gateway base URL
- `NEXT_PUBLIC_WS_BASE_URL`: Notification websocket endpoint (SockJS)
- `NEXT_PUBLIC_MAPBOX_ACCESS_TOKEN`: optional Mapbox token for map rendering

## Run

```powershell
Set-Location "C:\Users\aadeb\OneDrive\Desktop\MoodRide\frontend\moodride-web"
Copy-Item .env.example .env.local -Force
npm install
npm run dev
```

## Verify

```powershell
Set-Location "C:\Users\aadeb\OneDrive\Desktop\MoodRide\frontend\moodride-web"
npm run lint
npm run build
```

