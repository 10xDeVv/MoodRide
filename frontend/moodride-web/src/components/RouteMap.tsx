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
    return (
      <div className="map map-placeholder">
        <p className="small">
          No NEXT_PUBLIC_MAPBOX_ACCESS_TOKEN is set. Route geometry loaded with {route.geometry.geometry.coordinates.length} points.
        </p>
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



