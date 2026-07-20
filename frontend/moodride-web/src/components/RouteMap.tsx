"use client";

import { useCallback, useEffect, useLayoutEffect, useRef, useState, type MutableRefObject } from "react";
import mapboxgl, { type GeoJSONSource, type Map as MapboxMap, type MapLayerMouseEvent } from "mapbox-gl";
import "mapbox-gl/dist/mapbox-gl.css";
import { Minus, Plus } from "lucide-react";
import type { FeatureCollection, LineString, Point } from "geojson";
import type { RouteDetailResponse } from "@/lib/types";

interface RouteMapProps {
  route: RouteDetailResponse | null;
  selectedRouteId?: string;
  centerLat?: number;
  centerLng?: number;
  theme?: "day" | "night";
  onRouteSelect?: (routeId: string) => void;
  onRouteMapPainted?: (jobId: string, routeId: string, optionRevision: number) => boolean;
}

const MAPBOX_TOKEN = process.env.NEXT_PUBLIC_MAPBOX_ACCESS_TOKEN;
const ALL_ROUTES_SOURCE_ID = "all-routes";
const ROUTE_WAYPOINTS_SOURCE_ID = "route-waypoints";

const ROUTE_BLUE = "#2563eb";
const ROUTE_LIME = "#e8f04a";

const MAPBOX_STYLES = {
  day: "mapbox://styles/mapbox/outdoors-v12",
  night: "mapbox://styles/mapbox/dark-v11"
} as const;

const MAPBOX_INITIAL_LOAD_TIMEOUT_MS = 10_000;
const MAPBOX_STYLE_LOAD_TIMEOUT_MS = 10_000;

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

type RouteWaypointProperties = {
  kind: "start" | "via" | "finish";
  label: string;
  order: number;
};
type RoutePaintTarget = {
  jobId: string;
  routeId: string;
  optionRevision: number;
  key: string;
};

type PendingMapPaint = {
  map: MapboxMap;
  listener: () => void;
};

type LatestSourceUpdate = {
  map: MapboxMap;
  styleEpoch: number;
  updateToken: number;
  target: RoutePaintTarget;
};

type MapboxRuntimeStatus = "unavailable" | "loading" | "ready" | "failed";

type MapboxRuntimeError = {
  error?: { message?: string };
  source?: unknown;
  sourceId?: string;
  tile?: unknown;
};

function isFatalMapboxRuntimeError(event: MapboxRuntimeError): boolean {
  if (event.source || event.sourceId || event.tile) return false;
  const message = event.error?.message?.toLowerCase() ?? "";
  return message.includes("webgl")
    || message.includes("web gl")
    || message.includes("context lost")
    || message.includes("failed to initialize")
    || message.includes("failed to load style")
    || message.includes("unable to load style")
    || message.includes("style failed")
    || message.includes("style could not")
    || message.includes("/styles/v1/")
    || message.includes("access token")
    || message.includes("unauthorized");
}

function getRoutePaintTarget(route: RouteDetailResponse): RoutePaintTarget | null {
  if (!Number.isSafeInteger(route.optionRevision) || route.optionRevision < 0) return null;
  if ((route.geometry?.geometry?.coordinates?.length ?? 0) < 2) return null;
  return {
    jobId: route.jobId,
    routeId: route.routeId,
    optionRevision: route.optionRevision,
    key: `${route.jobId}:${route.routeId}:${route.optionRevision}`
  };
}


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

function routeWaypointsToGeoJson(route: RouteDetailResponse): FeatureCollection<Point, RouteWaypointProperties> {
  const coordinates = route.geometry?.geometry?.coordinates ?? [];
  if (coordinates.length < 2) {
    return { type: "FeatureCollection", features: [] };
  }

  const waypointIndexes = [
    0,
    Math.round((coordinates.length - 1) * 0.25),
    Math.round((coordinates.length - 1) * 0.5),
    Math.round((coordinates.length - 1) * 0.75),
    coordinates.length - 1
  ].filter((index, position, indexes) => index >= 0 && index < coordinates.length && indexes.indexOf(index) === position);

  return {
    type: "FeatureCollection",
    features: waypointIndexes.map((index, position) => {
      const isStart = position === 0;
      const isFinish = index === coordinates.length - 1;
      return {
        type: "Feature",
        id: `${route.routeId}-${position}`,
        properties: {
          kind: isStart ? "start" : isFinish ? "finish" : "via",
          label: isStart ? "Start" : isFinish ? "Finish" : `${position}`,
          order: position
        },
        geometry: {
          type: "Point",
          coordinates: coordinates[index]
        }
      };
    })
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

function ensureWaypointLayers(map: MapboxMap) {
  if (!map.getLayer("route-waypoint-halo")) {
    map.addLayer({
      id: "route-waypoint-halo",
      type: "circle",
      source: ROUTE_WAYPOINTS_SOURCE_ID,
      paint: {
        "circle-radius": ["case", ["==", ["get", "kind"], "via"], 6, 9],
        "circle-color": "#ffffff",
        "circle-opacity": 0.95,
        "circle-stroke-color": "#1a3020",
        "circle-stroke-width": 2
      }
    });
  }

  if (!map.getLayer("route-waypoint-core")) {
    map.addLayer({
      id: "route-waypoint-core",
      type: "circle",
      source: ROUTE_WAYPOINTS_SOURCE_ID,
      paint: {
        "circle-radius": ["case", ["==", ["get", "kind"], "via"], 3.5, 5],
        "circle-color": ["case", ["==", ["get", "kind"], "start"], ROUTE_LIME, ["==", ["get", "kind"], "finish"], "#ef4444", ROUTE_BLUE],
        "circle-opacity": 1
      }
    });
  }

  if (!map.getLayer("route-waypoint-label")) {
    map.addLayer({
      id: "route-waypoint-label",
      type: "symbol",
      source: ROUTE_WAYPOINTS_SOURCE_ID,
      filter: ["!=", ["get", "kind"], "via"],
      layout: {
        "text-field": ["get", "label"],
        "text-size": 11,
        "text-font": ["Open Sans Bold", "Arial Unicode MS Bold"],
        "text-offset": [0, 1.25],
        "text-anchor": "top"
      },
      paint: {
        "text-color": "#1a3020",
        "text-halo-color": "#ffffff",
        "text-halo-width": 1.4
      }
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
  const waypointGeojson = routeWaypointsToGeoJson(route);

  if (!map.getSource(ALL_ROUTES_SOURCE_ID)) {
    map.addSource(ALL_ROUTES_SOURCE_ID, { type: "geojson", data: geojson });
    ensureRouteLayers(map);
  } else {
    const source = map.getSource(ALL_ROUTES_SOURCE_ID) as GeoJSONSource | undefined;
    source?.setData(geojson);
    ensureRouteLayers(map);
  }

  if (!map.getSource(ROUTE_WAYPOINTS_SOURCE_ID)) {
    map.addSource(ROUTE_WAYPOINTS_SOURCE_ID, { type: "geojson", data: waypointGeojson });
    ensureWaypointLayers(map);
  } else {
    const source = map.getSource(ROUTE_WAYPOINTS_SOURCE_ID) as GeoJSONSource | undefined;
    source?.setData(waypointGeojson);
    ensureWaypointLayers(map);
  }

  if (!fitRoute) return;
  const coordinates = geojson.features.flatMap((feature) => feature.geometry.coordinates);
  if (coordinates.length > 1) {
    const lngs = coordinates.map(([lng]) => lng);
    const lats = coordinates.map(([, lat]) => lat);
    map.fitBounds([[Math.min(...lngs), Math.min(...lats)], [Math.max(...lngs), Math.max(...lats)]], { padding: getRouteFitPadding(), duration: 260 });
  }
}

export function RouteMap({
  route,
  selectedRouteId,
  centerLat = 49.28,
  centerLng = -123.12,
  theme = "day",
  onRouteSelect,
  onRouteMapPainted
}: RouteMapProps) {
  const hasToken = Boolean(MAPBOX_TOKEN);
  const [mapboxRuntimeStatus, setMapboxRuntimeStatus] = useState<MapboxRuntimeStatus>(
    hasToken ? "loading" : "unavailable"
  );
  const mapContainerRef = useRef<HTMLDivElement>(null);
  const mapRef = useRef<MapboxMap | null>(null);
  const initialLoadCompleteMapRef = useRef<MapboxMap | null>(null);
  const failMapboxRef = useRef<((map: MapboxMap) => void) | null>(null);
  const onRouteSelectRef = useRef(onRouteSelect);
  const onRouteMapPaintedRef = useRef(onRouteMapPainted);
  const routeRef = useRef(route);
  const selectedRouteIdRef = useRef(selectedRouteId);
  const themeRef = useRef(theme);
  const mapThemeRef = useRef<"day" | "night" | null>(null);
  const styleEpochRef = useRef(0);
  const styleReadyEpochRef = useRef(0);
  const sourceUpdateTokenRef = useRef(0);
  const lastSourceRenderKeyRef = useRef<string | null>(null);
  const latestSourceUpdateRef = useRef<LatestSourceUpdate | null>(null);
  const pendingMapPaintRef = useRef<PendingMapPaint | null>(null);
  const schematicPaintTokenRef = useRef(0);
  const paintedRouteRevisionsRef = useRef(new Set<string>());
  const lastFitRouteIdRef = useRef<string | null>(null);
  const useSchematicMap = !hasToken || mapboxRuntimeStatus === "failed";
  const shouldUseMapbox = hasToken && !useSchematicMap;

  useLayoutEffect(() => {
    onRouteSelectRef.current = onRouteSelect;
    onRouteMapPaintedRef.current = onRouteMapPainted;
    routeRef.current = route;
    selectedRouteIdRef.current = selectedRouteId;
    themeRef.current = theme;
  }, [onRouteMapPainted, onRouteSelect, route, selectedRouteId, theme]);

  const isCurrentPaintTarget = useCallback((target: RoutePaintTarget) => {
    const currentRoute = routeRef.current;
    if (!currentRoute) return false;
    const currentTarget = getRoutePaintTarget(currentRoute);
    return currentTarget?.key === target.key;
  }, []);

  const reportCurrentPaint = useCallback((target: RoutePaintTarget) => {
    if (!isCurrentPaintTarget(target) || paintedRouteRevisionsRef.current.has(target.key)) return;
    const callback = onRouteMapPaintedRef.current;
    if (!callback) return;
    if (callback(target.jobId, target.routeId, target.optionRevision)) {
      paintedRouteRevisionsRef.current.add(target.key);
    }
  }, [isCurrentPaintTarget]);

  const cancelPendingMapPaint = useCallback(() => {
    const pending = pendingMapPaintRef.current;
    if (!pending) return;
    pending.map.off("idle", pending.listener);
    pendingMapPaintRef.current = null;
  }, []);

  const commitCurrentRouteToMap = useCallback((map: MapboxMap, forceFitRoute: boolean) => {
    if (mapRef.current !== map || styleReadyEpochRef.current !== styleEpochRef.current) return;

    const currentRoute = routeRef.current;
    if (!currentRoute) return;
    const target = getRoutePaintTarget(currentRoute);
    const currentTheme = themeRef.current;
    const sourceRenderKey = target
      ? [
          styleEpochRef.current,
          target.key,
          selectedRouteIdRef.current ?? "",
          currentTheme
        ].join("|")
      : null;
    if (sourceRenderKey && lastSourceRenderKeyRef.current === sourceRenderKey) return;

    cancelPendingMapPaint();
    const shouldFitRoute = forceFitRoute || lastFitRouteIdRef.current !== currentRoute.routeId;
    renderAllRoutes(map, currentRoute, selectedRouteIdRef.current, currentTheme, shouldFitRoute);
    ensureRouteInteractions(map, onRouteSelectRef);
    if (shouldFitRoute) lastFitRouteIdRef.current = currentRoute.routeId;
    lastSourceRenderKeyRef.current = sourceRenderKey;

    if (!target) {
      latestSourceUpdateRef.current = null;
      return;
    }

    const styleEpoch = styleEpochRef.current;
    const updateToken = ++sourceUpdateTokenRef.current;
    latestSourceUpdateRef.current = { map, styleEpoch, updateToken, target };
    const handleIdle = () => {
      if (pendingMapPaintRef.current?.listener !== handleIdle) return;
      pendingMapPaintRef.current = null;

      const latestUpdate = latestSourceUpdateRef.current;
      if (
        mapRef.current !== map
        || styleEpochRef.current !== styleEpoch
        || latestUpdate?.map !== map
        || latestUpdate.styleEpoch !== styleEpoch
        || latestUpdate.updateToken !== updateToken
        || latestUpdate.target.key !== target.key
      ) {
        return;
      }
      reportCurrentPaint(target);
    };

    pendingMapPaintRef.current = { map, listener: handleIdle };
    map.once("idle", handleIdle);
  }, [cancelPendingMapPaint, reportCurrentPaint]);

  useEffect(() => {
    const mapboxToken = MAPBOX_TOKEN;
    const container = mapContainerRef.current;
    if (!mapboxToken || !container) return;

    let map: MapboxMap | null = null;
    let canvas: HTMLCanvasElement | null = null;
    let initialLoadTimer = 0;
    let disposed = false;
    let failed = false;
    let cleaned = false;

    function clearInitialLoadTimer() {
      window.clearTimeout(initialLoadTimer);
      initialLoadTimer = 0;
    }

    function cleanupMap() {
      if (cleaned) return;
      cleaned = true;
      clearInitialLoadTimer();
      map?.off("load", handleInitialLoad);
      map?.off("error", handleMapError);
      canvas?.removeEventListener("webglcontextlost", handleWebGLContextLost);
      cancelPendingMapPaint();

      if (mapRef.current === map) {
        mapRef.current = null;
        initialLoadCompleteMapRef.current = null;
        mapThemeRef.current = null;
        styleEpochRef.current += 1;
        styleReadyEpochRef.current = 0;
        sourceUpdateTokenRef.current += 1;
        lastSourceRenderKeyRef.current = null;
        latestSourceUpdateRef.current = null;
        lastFitRouteIdRef.current = null;
      }
      if (failMapboxRef.current === requestCurrentMapFailure) {
        failMapboxRef.current = null;
      }

      if (map) {
        try {
          map.remove();
        } catch {
          // A failed WebGL context can make Mapbox cleanup throw.
        }
      }
    }

    function failCurrentMap() {
      if (disposed || failed) return;
      failed = true;
      cleanupMap();
      setMapboxRuntimeStatus("failed");
    }

    function requestCurrentMapFailure(candidate: MapboxMap) {
      if (candidate === map) failCurrentMap();
    }

    function handleMapError(event: MapboxRuntimeError) {
      if (isFatalMapboxRuntimeError(event)) {
        failCurrentMap();
      }
    }

    function handleWebGLContextLost(event: Event) {
      event.preventDefault();
      failCurrentMap();
    }

    function handleInitialLoad() {
      if (!map || disposed || failed || mapRef.current !== map) return;
      try {
        styleReadyEpochRef.current = styleEpochRef.current;
        commitCurrentRouteToMap(map, true);
        initialLoadCompleteMapRef.current = map;
        clearInitialLoadTimer();
        setMapboxRuntimeStatus("ready");
      } catch {
        failCurrentMap();
      }
    }

    setMapboxRuntimeStatus("loading");
    try {
      mapboxgl.accessToken = mapboxToken;
      map = new mapboxgl.Map({
        container,
        style: MAPBOX_STYLES[themeRef.current],
        center: [centerLng, centerLat],
        zoom: 10,
        attributionControl: false
      });

      mapRef.current = map;
      mapThemeRef.current = themeRef.current;
      styleEpochRef.current += 1;
      styleReadyEpochRef.current = 0;
      lastSourceRenderKeyRef.current = null;
      latestSourceUpdateRef.current = null;
      failMapboxRef.current = requestCurrentMapFailure;

      canvas = map.getCanvas();
      canvas.addEventListener("webglcontextlost", handleWebGLContextLost);
      map.on("error", handleMapError);
      map.once("load", handleInitialLoad);
      initialLoadTimer = window.setTimeout(failCurrentMap, MAPBOX_INITIAL_LOAD_TIMEOUT_MS);
    } catch {
      failCurrentMap();
    }

    return () => {
      disposed = true;
      cleanupMap();
    };
  }, [cancelPendingMapPaint, centerLat, centerLng, commitCurrentRouteToMap, hasToken]);

  useEffect(() => {
    if (!shouldUseMapbox || !mapRef.current || mapThemeRef.current === theme) return;
    const map = mapRef.current;
    mapThemeRef.current = theme;
    setMapboxRuntimeStatus("loading");
    const styleEpoch = ++styleEpochRef.current;
    lastSourceRenderKeyRef.current = null;
    latestSourceUpdateRef.current = null;
    cancelPendingMapPaint();

    let styleLoadTimer = 0;
    const clearStyleLoadTimer = () => {
      window.clearTimeout(styleLoadTimer);
      styleLoadTimer = 0;
    };
    const failStyleLoad = () => {
      if (
        mapRef.current === map
        && styleEpochRef.current === styleEpoch
        && themeRef.current === theme
      ) {
        failMapboxRef.current?.(map);
      }
    };
    const handleStyleLoad = () => {
      if (
        mapRef.current !== map
        || styleEpochRef.current !== styleEpoch
        || themeRef.current !== theme
      ) {
        return;
      }

      clearStyleLoadTimer();
      try {
        styleReadyEpochRef.current = styleEpoch;
        commitCurrentRouteToMap(map, true);
        if (initialLoadCompleteMapRef.current === map) {
          setMapboxRuntimeStatus("ready");
        }
      } catch {
        failMapboxRef.current?.(map);
      }
    };
    map.once("style.load", handleStyleLoad);
    styleLoadTimer = window.setTimeout(failStyleLoad, MAPBOX_STYLE_LOAD_TIMEOUT_MS);
    try {
      map.setStyle(MAPBOX_STYLES[theme]);
    } catch {
      clearStyleLoadTimer();
      map.off("style.load", handleStyleLoad);
      failMapboxRef.current?.(map);
    }

    return () => {
      clearStyleLoadTimer();
      map.off("style.load", handleStyleLoad);
    };
  }, [cancelPendingMapPaint, commitCurrentRouteToMap, shouldUseMapbox, theme]);

  useEffect(() => {
    if (!shouldUseMapbox || !mapRef.current) return;
    if (!route) {
      cancelPendingMapPaint();
      latestSourceUpdateRef.current = null;
      lastSourceRenderKeyRef.current = null;
      return;
    }
    const map = mapRef.current;
    try {
      commitCurrentRouteToMap(map, false);
    } catch {
      failMapboxRef.current?.(map);
    }
  }, [cancelPendingMapPaint, commitCurrentRouteToMap, route, selectedRouteId, shouldUseMapbox, theme]);

  useEffect(() => {
    if (!useSchematicMap || !route) return;
    const target = getRoutePaintTarget(route);
    if (!target || paintedRouteRevisionsRef.current.has(target.key)) return;

    const paintToken = ++schematicPaintTokenRef.current;
    let secondFrame = 0;
    const firstFrame = window.requestAnimationFrame(() => {
      secondFrame = window.requestAnimationFrame(() => {
        if (schematicPaintTokenRef.current !== paintToken) return;
        reportCurrentPaint(target);
      });
    });

    return () => {
      schematicPaintTokenRef.current += 1;
      window.cancelAnimationFrame(firstFrame);
      window.cancelAnimationFrame(secondFrame);
    };
  }, [onRouteMapPainted, reportCurrentPaint, route, useSchematicMap]);

  useEffect(() => {
    if (!shouldUseMapbox || !mapContainerRef.current) return;

    let resizeFrame = 0;
    const resizeMap = () => {
      window.cancelAnimationFrame(resizeFrame);
      resizeFrame = window.requestAnimationFrame(() => mapRef.current?.resize());
    };

    const observer = new ResizeObserver(resizeMap);
    observer.observe(mapContainerRef.current);
    window.addEventListener("resize", resizeMap);
    window.visualViewport?.addEventListener("resize", resizeMap);
    resizeMap();

    return () => {
      window.cancelAnimationFrame(resizeFrame);
      observer.disconnect();
      window.removeEventListener("resize", resizeMap);
      window.visualViewport?.removeEventListener("resize", resizeMap);
    };
  }, [shouldUseMapbox]);

  if (useSchematicMap) {
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
