"use client";

import { useEffect, useState, useRef } from "react";
import type { Map as MapboxMap } from "mapbox-gl";
import type { RouteDetailResponse } from "@/lib/types";

interface Props {
  route: RouteDetailResponse | null;
}

const PROFILE_COLORS: Record<string, string> = {
  most_scenic: "#0D78FF",
  balanced: "#118AB2",
  shorter: "#1E5AA6"
};

type LngLat = [number, number];

function buildRouteFeature(route: RouteDetailResponse): GeoJSON.FeatureCollection<GeoJSON.LineString> {
  return {
    type: "FeatureCollection",
    features: [
      {
        type: "Feature",
        properties: {},
        geometry: {
          type: "LineString",
          coordinates: route.geometry.geometry.coordinates
        }
      }
    ]
  };
}

function resolveRouteColor(route: RouteDetailResponse): string {
  const activeProfile = route.routeOptions.find((option) => option.routeId === route.routeId)?.profile ?? "most_scenic";
  return PROFILE_COLORS[activeProfile] ?? PROFILE_COLORS.most_scenic;
}

function buildSchematicPoints(coordinates: LngLat[], width: number, height: number, padding: number): string {
  if (coordinates.length === 0) {
    return "";
  }

  const lngValues = coordinates.map((point) => point[0]);
  const latValues = coordinates.map((point) => point[1]);
  const minLng = Math.min(...lngValues);
  const maxLng = Math.max(...lngValues);
  const minLat = Math.min(...latValues);
  const maxLat = Math.max(...latValues);
  const deltaLng = Math.max(maxLng - minLng, 0.0001);
  const deltaLat = Math.max(maxLat - minLat, 0.0001);
  const renderWidth = width - padding * 2;
  const renderHeight = height - padding * 2;

  return coordinates
    .map(([lng, lat]) => {
      const x = padding + ((lng - minLng) / deltaLng) * renderWidth;
      const y = padding + (1 - (lat - minLat) / deltaLat) * renderHeight;
      return `${x.toFixed(1)},${y.toFixed(1)}`;
    })
    .join(" ");
}

export function RouteMap({ route }: Props) {
  const containerRef = useRef<HTMLDivElement | null>(null);
  const mapRef = useRef<MapboxMap | null>(null);
  const [mapReady, setMapReady] = useState(false);

  useEffect(() => {
    setMapReady(false);
  }, [route?.routeId]);

  useEffect(() => {
    if (!containerRef.current || !route) {
      return;
    }

    const token = process.env.NEXT_PUBLIC_MAPBOX_ACCESS_TOKEN;
    if (!token) {
      return;
    }

    let disposed = false;

    const initMap = async () => {
      const mapboxModule = await import("mapbox-gl");
      const mapboxgl = mapboxModule.default;
      mapboxgl.accessToken = token;

      if (disposed || !containerRef.current) {
        return;
      }

      const map = new mapboxgl.Map({
        container: containerRef.current,
        style: "mapbox://styles/mapbox/streets-v12",
        center: [route.startLng, route.startLat],
        zoom: 11
      });

      map.on("load", () => {
        const sourceId = "route-source";
        const casingLayerId = "route-casing-layer";
        const glowLayerId = "route-glow-layer";
        const lineLayerId = "route-line-layer";

        const routeColor = resolveRouteColor(route);
        const featureCollection = buildRouteFeature(route);

        [lineLayerId, glowLayerId, casingLayerId].forEach((id) => {
          if (map.getLayer(id)) {
            map.removeLayer(id);
          }
        });
        if (map.getSource(sourceId)) {
          map.removeSource(sourceId);
        }

        map.addSource(sourceId, {
          type: "geojson",
          data: featureCollection
        });

        map.addLayer({
          id: casingLayerId,
          type: "line",
          source: sourceId,
          layout: {
            "line-join": "round",
            "line-cap": "round"
          },
          paint: {
            "line-color": "#0B203A",
            "line-width": 11,
            "line-opacity": 0.68
          }
        });

        map.addLayer({
          id: glowLayerId,
          type: "line",
          source: sourceId,
          layout: {
            "line-join": "round",
            "line-cap": "round"
          },
          paint: {
            "line-color": "#8FD3FF",
            "line-width": 14,
            "line-blur": 3.4,
            "line-opacity": 0.38
          }
        });

        map.addLayer({
          id: lineLayerId,
          type: "line",
          source: sourceId,
          layout: {
            "line-join": "round",
            "line-cap": "round"
          },
          paint: {
            "line-color": routeColor,
            "line-width": 6.4,
            "line-opacity": 0.98
          }
        });

        if (route.geometry.geometry.coordinates.length > 1) {
          const bounds = route.geometry.geometry.coordinates.reduce(
            (acc, point) => acc.extend(point as [number, number]),
            new mapboxgl.LngLatBounds(route.geometry.geometry.coordinates[0], route.geometry.geometry.coordinates[0])
          );
          map.fitBounds(bounds, { padding: 40, duration: 700 });
        }

        setMapReady(true);
      });

      mapRef.current = map;
    };

    void initMap();

    return () => {
      disposed = true;
      if (mapRef.current) {
        mapRef.current.remove();
        mapRef.current = null;
      }
    };
  }, [route]);

  if (!route) {
    return <div className="map map-placeholder small">Route map appears here after job completion.</div>;
  }

  if (!process.env.NEXT_PUBLIC_MAPBOX_ACCESS_TOKEN) {
    const coordinates = route.geometry.geometry.coordinates as LngLat[];
    const routeColor = resolveRouteColor(route);
    const pathPoints = buildSchematicPoints(coordinates, 880, 520, 34);
    const startPoint = pathPoints.split(" ")[0];
    const endPoint = pathPoints.split(" ").at(-1);
    return (
      <div className="map map-fallback" role="img" aria-label="Route preview map">
        <svg viewBox="0 0 880 520" className="map-fallback-svg">
          <defs>
            <linearGradient id="routeGlowGradient" x1="0%" y1="0%" x2="100%" y2="0%">
              <stop offset="0%" stopColor="#9ed0ff" />
              <stop offset="100%" stopColor={routeColor} />
            </linearGradient>
          </defs>
          <rect x="0" y="0" width="880" height="520" className="map-fallback-bg" />
          <polyline points={pathPoints} className="map-fallback-shadow" />
          <polyline points={pathPoints} className="map-fallback-route" style={{ stroke: "url(#routeGlowGradient)" }} />
          {startPoint && <circle cx={startPoint.split(",")[0]} cy={startPoint.split(",")[1]} r="7.5" className="map-fallback-start" />}
          {endPoint && <circle cx={endPoint.split(",")[0]} cy={endPoint.split(",")[1]} r="6.5" className="map-fallback-end" />}
        </svg>
        <div className="map-fallback-caption small">
          Live route preview ({coordinates.length} points). Set `NEXT_PUBLIC_MAPBOX_ACCESS_TOKEN` for full basemap tiles.
        </div>
      </div>
    );
  }

  return (
    <div className={`map ${mapReady ? "map-ready" : ""}`} aria-busy={!mapReady} role="status" aria-live="polite">
      <div className="map-canvas" ref={containerRef} />
      {!mapReady && (
        <div className="map-skeleton" aria-label="Loading route map">
          <div className="map-skeleton-line map-skeleton-line-long" />
          <div className="map-skeleton-line" />
          <div className="map-skeleton-line map-skeleton-line-short" />
        </div>
      )}
    </div>
  );
}



