"use client";

import { useEffect, useRef, type MutableRefObject } from "react";
import mapboxgl, { type GeoJSONSource, type Map as MapboxMap, type MapLayerMouseEvent } from "mapbox-gl";
import "mapbox-gl/dist/mapbox-gl.css";
import { Minus, Plus } from "lucide-react";
import type { FeatureCollection, LineString } from "geojson";
import type { RouteDetailResponse } from "@/lib/types";

interface RouteMapProps {
  route: RouteDetailResponse | null;
  selectedRouteId?: string;
  centerLat?: number;
  centerLng?: number;
  theme?: "day" | "night";
  onRouteSelect?: (routeId: string) => void;
}

const MAPBOX_TOKEN = process.env.NEXT_PUBLIC_MAPBOX_ACCESS_TOKEN;
const ALL_ROUTES_SOURCE_ID = "all-routes";

const ROUTE_BLUE = "#2563eb";
const ROUTE_LIME = "#e8f04a";

const MAPBOX_STYLES = {
  day: "mapbox://styles/mapbox/outdoors-v12",
  night: "mapbox://styles/mapbox/dark-v11"
} as const;

const PROFILE_COLORS: Record<string, string> = {
  most_scenic: ROUTE_BLUE,
  balanced: ROUTE_BLUE,
  shorter: ROUTE_BLUE
};

type RouteLineProperties = {
  routeId: string;
  color: string;
  lightColor: string;
  selected: boolean;
  offset: number;
  order: number;
};

function TopoBackground({ theme }: { theme: "day" | "night" }) {
  const topoStroke = theme === "night" ? "#e8f04a" : "#1a3020";
  const topoOpacity = theme === "night" ? 0.12 : 0.18;

  return (
    <svg
      style={{ position: "absolute", inset: 0, width: "100%", height: "100%", opacity: topoOpacity }}
      viewBox="0 0 1200 700"
      preserveAspectRatio="xMidYMid slice"
      aria-hidden="true"
    >
      <ellipse cx="600" cy="350" rx="560" ry="320" fill="none" stroke={topoStroke} strokeWidth="1" />
      <ellipse cx="600" cy="350" rx="480" ry="270" fill="none" stroke={topoStroke} strokeWidth="1" />
      <ellipse cx="600" cy="350" rx="400" ry="220" fill="none" stroke={topoStroke} strokeWidth="1" />
      <ellipse cx="600" cy="350" rx="320" ry="170" fill="none" stroke={topoStroke} strokeWidth="1" />
      <ellipse cx="600" cy="350" rx="240" ry="125" fill="none" stroke={topoStroke} strokeWidth="1" />
      <ellipse cx="600" cy="350" rx="160" ry="80" fill="none" stroke={topoStroke} strokeWidth="1.5" />
      <ellipse cx="600" cy="350" rx="80" ry="40" fill="none" stroke={topoStroke} strokeWidth="1.5" />
      <ellipse cx="200" cy="180" rx="200" ry="140" fill="none" stroke={topoStroke} strokeWidth="1" />
      <ellipse cx="200" cy="180" rx="140" ry="100" fill="none" stroke={topoStroke} strokeWidth="1" />
      <ellipse cx="200" cy="180" rx="80" ry="60" fill="none" stroke={topoStroke} strokeWidth="1" />
      <ellipse cx="1000" cy="520" rx="220" ry="160" fill="none" stroke={topoStroke} strokeWidth="1" />
      <ellipse cx="1000" cy="520" rx="150" ry="110" fill="none" stroke={topoStroke} strokeWidth="1" />
      <ellipse cx="1000" cy="520" rx="80" ry="60" fill="none" stroke={topoStroke} strokeWidth="1" />
      {Array.from({ length: 14 }).map((_, i) => (
        <line key={`v${i}`} x1={i * 90} y1="0" x2={i * 90} y2="700" stroke={topoStroke} strokeWidth="0.4" />
      ))}
      {Array.from({ length: 9 }).map((_, i) => (
        <line key={`h${i}`} x1="0" y1={i * 90} x2="1200" y2={i * 90} stroke={topoStroke} strokeWidth="0.4" />
      ))}
    </svg>
  );
}

function SchematicMap({ route, theme }: { route: RouteDetailResponse | null; theme: "day" | "night" }) {
  if (!route) {
    return (
      <div className="map-schematic">
        <div className="map-schematic-topo" />
        <TopoBackground theme={theme} />
        <div className="map-notice">Add NEXT_PUBLIC_MAPBOX_ACCESS_TOKEN to enable live maps</div>
      </div>
    );
  }

  const coords = route.geometry?.geometry?.coordinates ?? [];
  if (coords.length < 2) {
    return (
      <div className="map-schematic">
        <div className="map-schematic-topo" />
        <TopoBackground theme={theme} />
        <div className="map-notice">Route geometry unavailable</div>
      </div>
    );
  }

  const W = 880;
  const H = 520;
  const PAD = 60;
  const lngs = coords.map(([lng]) => lng);
  const lats = coords.map(([, lat]) => lat);
  const minLng = Math.min(...lngs);
  const maxLng = Math.max(...lngs);
  const minLat = Math.min(...lats);
  const maxLat = Math.max(...lats);
  const scaleX = (W - PAD * 2) / (maxLng - minLng || 1);
  const scaleY = (H - PAD * 2) / (maxLat - minLat || 1);
  const scale = Math.min(scaleX, scaleY);
  const offsetX = (W - (maxLng - minLng) * scale) / 2;
  const offsetY = (H - (maxLat - minLat) * scale) / 2;
  const project = ([lng, lat]: [number, number]) => ({
    x: offsetX + (lng - minLng) * scale,
    y: H - offsetY - (lat - minLat) * scale
  });
  const points = coords.map(project);
  const pathD = points.map((p, i) => `${i === 0 ? "M" : "L"} ${p.x.toFixed(1)} ${p.y.toFixed(1)}`).join(" ");
  const activeOption = route.routeOptions?.find((o) => o.routeId === route.routeId);
  const color = theme === "night"
    ? ROUTE_LIME
    : PROFILE_COLORS[activeOption?.profile ?? "most_scenic"] ?? ROUTE_BLUE;

  return (
    <div className="map-schematic">
      <div className="map-schematic-topo" />
      <TopoBackground theme={theme} />
      <svg viewBox={`0 0 ${W} ${H}`} preserveAspectRatio="xMidYMid meet" style={{ position: "relative", width: "100%", height: "100%", display: "block", zIndex: 1 }} aria-label="Schematic route map">
        <path d={pathD} fill="none" stroke="rgba(229,234,109,0.15)" strokeWidth={18} strokeLinecap="round" strokeLinejoin="round" />
        <path d={pathD} fill="none" stroke="rgba(26,48,32,0.5)" strokeWidth={10} strokeLinecap="round" strokeLinejoin="round" />
        <path d={pathD} fill="none" stroke={color} strokeWidth={5} strokeLinecap="round" strokeLinejoin="round" />
        <circle cx={points[0].x} cy={points[0].y} r={10} fill="#1a3020" stroke="#e5ea6d" strokeWidth={2.5} />
        <circle cx={points[0].x} cy={points[0].y} r={4} fill="#e5ea6d" />
        <circle cx={points[points.length - 1].x} cy={points[points.length - 1].y} r={10} fill={color} stroke="#fff" strokeWidth={2.5} />
        <circle cx={points[points.length - 1].x} cy={points[points.length - 1].y} r={4} fill="#fff" />
      </svg>
      <div className="map-notice">Schematic view — add Mapbox token for live map</div>
    </div>
  );
}

function lightenColor(hex: string, amount = 0.62): string {
  const cleanHex = hex.replace("#", "");
  const r = parseInt(cleanHex.substring(0, 2), 16);
  const g = parseInt(cleanHex.substring(2, 4), 16);
  const b = parseInt(cleanHex.substring(4, 6), 16);
  const newR = Math.round(r + (255 - r) * amount);
  const newG = Math.round(g + (255 - g) * amount);
  const newB = Math.round(b + (255 - b) * amount);
  return `rgb(${newR}, ${newG}, ${newB})`;
}

function routesToGeoJson(route: RouteDetailResponse, selectedRouteId: string | undefined, theme: "day" | "night"): FeatureCollection<LineString, RouteLineProperties> {
  const activeOption = route.routeOptions?.find((option) => option.routeId === (selectedRouteId ?? route.routeId));
  const color = theme === "night"
    ? ROUTE_LIME
    : PROFILE_COLORS[activeOption?.profile ?? "most_scenic"] ?? ROUTE_BLUE;
  const coordinates = route.geometry?.geometry?.coordinates ?? [];

  return {
    type: "FeatureCollection",
    features: coordinates.length > 1
      ? [{
          type: "Feature",
          id: route.routeId,
          properties: {
            routeId: route.routeId,
            color,
            lightColor: lightenColor(color),
            selected: true,
            offset: 0,
            order: 1
          },
          geometry: {
            type: "LineString",
            coordinates
          }
        }]
      : []
  };
}

function getRouteFitPadding() {
  if (typeof window === "undefined") return 70;

  const width = window.innerWidth;
  const height = window.visualViewport?.height ?? window.innerHeight;

  if (width <= 767) {
    return {
      top: 88,
      right: 28,
      bottom: Math.round(height * 0.58),
      left: 28
    };
  }

  if (width <= 1440) {
    return {
      top: 96,
      right: 56,
      bottom: 48,
      left: Math.round(width * 0.42)
    };
  }

  return 70;
}

function ensureRouteLayers(map: MapboxMap) {
  if (!map.getLayer("routes-alternate-bg")) {
    map.addLayer({
      id: "routes-alternate-bg",
      type: "line",
      source: ALL_ROUTES_SOURCE_ID,
      filter: ["!=", ["get", "selected"], true],
      layout: { "line-cap": "round", "line-join": "round" },
      paint: { "line-color": "#ffffff", "line-width": 13, "line-opacity": 0.9, "line-offset": ["get", "offset"] }
    });
  }

  if (!map.getLayer("routes-alternate-stroke")) {
    map.addLayer({
      id: "routes-alternate-stroke",
      type: "line",
      source: ALL_ROUTES_SOURCE_ID,
      filter: ["!=", ["get", "selected"], true],
      layout: { "line-cap": "round", "line-join": "round" },
      paint: { "line-color": ["get", "color"], "line-width": 7, "line-opacity": 0.98, "line-offset": ["get", "offset"] }
    });
  }

  if (!map.getLayer("routes-selected-bg")) {
    map.addLayer({
      id: "routes-selected-bg",
      type: "line",
      source: ALL_ROUTES_SOURCE_ID,
      filter: ["==", ["get", "selected"], true],
      layout: { "line-cap": "round", "line-join": "round" },
      paint: { "line-color": ["get", "color"], "line-width": 8, "line-opacity": 0.35, "line-blur": 6, "line-offset": ["get", "offset"] }
    });
  }

  if (!map.getLayer("routes-selected-stroke")) {
    map.addLayer({
      id: "routes-selected-stroke",
      type: "line",
      source: ALL_ROUTES_SOURCE_ID,
      filter: ["==", ["get", "selected"], true],
      layout: { "line-cap": "round", "line-join": "round" },
      paint: { "line-color": ["get", "color"], "line-width": 5, "line-opacity": 1, "line-offset": ["get", "offset"] }
    });
  }
}

function routeIdFromMapEvent(event: MapLayerMouseEvent): string | undefined {
  const routeId = event.features?.[0]?.properties?.routeId;
  return typeof routeId === "string" ? routeId : undefined;
}

function ensureRouteInteractions(map: MapboxMap, onSelectRef: MutableRefObject<((routeId: string) => void) | undefined>) {
  if (map.getCanvas().dataset.waywardRouteInteractionsBound === "true") return;
  const handleRouteClick = (event: MapLayerMouseEvent) => {
    const routeId = routeIdFromMapEvent(event);
    if (routeId) onSelectRef.current?.(routeId);
  };

  map.on("click", "routes-alternate-stroke", handleRouteClick);
  map.on("click", "routes-selected-stroke", handleRouteClick);
  map.on("mouseenter", "routes-alternate-stroke", () => { map.getCanvas().style.cursor = "pointer"; });
  map.on("mouseleave", "routes-alternate-stroke", () => { map.getCanvas().style.cursor = ""; });
  map.getCanvas().dataset.waywardRouteInteractionsBound = "true";
}

function renderAllRoutes(map: MapboxMap, route: RouteDetailResponse, selectedRouteId: string | undefined, theme: "day" | "night", fitRoute: boolean) {
  const geojson = routesToGeoJson(route, selectedRouteId, theme);

  if (!map.getSource(ALL_ROUTES_SOURCE_ID)) {
    map.addSource(ALL_ROUTES_SOURCE_ID, { type: "geojson", data: geojson });
    ensureRouteLayers(map);
  } else {
    const source = map.getSource(ALL_ROUTES_SOURCE_ID) as GeoJSONSource | undefined;
    source?.setData(geojson);
    ensureRouteLayers(map);
  }

  if (!fitRoute) return;
  const coordinates = geojson.features.flatMap((feature) => feature.geometry.coordinates);
  if (coordinates.length > 1) {
    const lngs = coordinates.map(([lng]) => lng);
    const lats = coordinates.map(([, lat]) => lat);
    map.fitBounds([[Math.min(...lngs), Math.min(...lats)], [Math.max(...lngs), Math.max(...lats)]], { padding: getRouteFitPadding(), duration: 260 });
  }
}

export function RouteMap({ route, selectedRouteId, centerLat = 49.28, centerLng = -123.12, theme = "day", onRouteSelect }: RouteMapProps) {
  const mapContainerRef = useRef<HTMLDivElement>(null);
  const mapRef = useRef<MapboxMap | null>(null);
  const onRouteSelectRef = useRef(onRouteSelect);
  const routeRef = useRef(route);
  const selectedRouteIdRef = useRef(selectedRouteId);
  const themeRef = useRef(theme);
  const lastFitRouteIdRef = useRef<string | null>(null);
  const hasToken = Boolean(MAPBOX_TOKEN);

  useEffect(() => {
    onRouteSelectRef.current = onRouteSelect;
  }, [onRouteSelect]);

  useEffect(() => {
    routeRef.current = route;
    selectedRouteIdRef.current = selectedRouteId;
    themeRef.current = theme;
  }, [route, selectedRouteId, theme]);

  useEffect(() => {
    const mapboxToken = MAPBOX_TOKEN;
    if (!mapboxToken || !mapContainerRef.current) return;
    mapboxgl.accessToken = mapboxToken;
    const map = new mapboxgl.Map({
      container: mapContainerRef.current,
      style: MAPBOX_STYLES[themeRef.current],
      center: [centerLng, centerLat],
      zoom: 10,
      attributionControl: false
    });

    mapRef.current = map;
    map.on("load", () => {
      const currentRoute = routeRef.current;
      if (currentRoute) {
        renderAllRoutes(map, currentRoute, selectedRouteIdRef.current, themeRef.current, true);
        lastFitRouteIdRef.current = currentRoute.routeId;
        ensureRouteInteractions(map, onRouteSelectRef);
      }
    });

    return () => {
      map.remove();
      mapRef.current = null;
      lastFitRouteIdRef.current = null;
    };
  }, [centerLat, centerLng, hasToken]);

  useEffect(() => {
    if (!hasToken || !mapRef.current) return;
    const map = mapRef.current;

    map.setStyle(MAPBOX_STYLES[theme]);
    map.once("style.load", () => {
      const currentRoute = routeRef.current;
      if (currentRoute) {
        renderAllRoutes(map, currentRoute, selectedRouteIdRef.current, theme, true);
        lastFitRouteIdRef.current = currentRoute.routeId;
        ensureRouteInteractions(map, onRouteSelectRef);
      }
    });
  }, [theme, hasToken]);

  useEffect(() => {
    if (!hasToken || !mapRef.current || !route) return;
    const map = mapRef.current;
    const shouldFitRoute = lastFitRouteIdRef.current !== route.routeId;

    if (map.loaded()) {
      renderAllRoutes(map, route, selectedRouteId, theme, shouldFitRoute);
      if (shouldFitRoute) lastFitRouteIdRef.current = route.routeId;
      ensureRouteInteractions(map, onRouteSelectRef);
    } else {
      map.once("load", () => {
        renderAllRoutes(map, route, selectedRouteId, theme, true);
        lastFitRouteIdRef.current = route.routeId;
        ensureRouteInteractions(map, onRouteSelectRef);
      });
    }
  }, [route, selectedRouteId, theme, hasToken]);

  if (!hasToken) {
    return <SchematicMap route={route} theme={theme} />;
  }

  const handleZoomIn = () => {
    mapRef.current?.zoomIn({ duration: 220 });
  };

  const handleZoomOut = () => {
    mapRef.current?.zoomOut({ duration: 220 });
  };

  return (
    <div className="route-map-shell">
      <div ref={mapContainerRef} className="route-mapbox-canvas" />
      <div className="map-zoom-controls" aria-label="Map zoom controls">
        <button className="map-zoom-button" type="button" aria-label="Zoom in" title="Zoom in" onClick={handleZoomIn}>
          <Plus size={18} strokeWidth={2.6} />
        </button>
        <button className="map-zoom-button" type="button" aria-label="Zoom out" title="Zoom out" onClick={handleZoomOut}>
          <Minus size={18} strokeWidth={2.6} />
        </button>
      </div>
    </div>
  );
}
