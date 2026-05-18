"use client";

import { useEffect, useMemo, useRef, useState, type CSSProperties } from "react";
import { getJobStatus, getRoute, getScenicRegions, searchLocations, submitRoute, submitRouteRating } from "@/lib/api";
import { connectJobChannel } from "@/lib/ws";
import type {
  JobSocketEvent,
  LocationSuggestion,
  RouteDetailResponse,
  RouteJobStatusResponse,
  RouteMode,
  RouteOptionResponse,
  RouteSubmissionResponse,
  ScenicRegionsResponse,
  Vibe
} from "@/lib/types";
import { RouteMap } from "@/components/RouteMap";
import { ScenicHighlightsPanel } from "@/components/ScenicHighlightsPanel";

const VIBE_GROUPS: Array<{ title: string; description: string; options: Vibe[] }> = [
  {
    title: "Browse by scenery",
    description: "What the drive should look like.",
    options: ["coastal", "mountain", "countryside", "riverside", "forest", "open_roads"]
  },
  {
    title: "Refine by feel",
    description: "How the road should behave.",
    options: ["relaxing", "winding_roads", "smooth_cruise", "quiet", "hidden_gems", "minimal_traffic"]
  },
  {
    title: "Quick presets",
    description: "Pick a mood and let MoodRide blend the weights.",
    options: ["scenic", "sunset", "photo_worthy", "nature_escape", "sunday_cruise", "adventure"]
  }
];
const TIME_BUDGET_OPTIONS = [30, 60, 90, 120] as const;
const ROUTE_MODES: Array<{ value: RouteMode; label: string; status: string; enabled: boolean }> = [
  { value: "drive", label: "Drive", status: "Canada live", enabled: true },
  { value: "walk", label: "Walk", status: "City pilot next", enabled: false },
  { value: "bike", label: "Bike", status: "Safety scoring next", enabled: false }
];
const PROFILE_DISPLAY_NAMES: Record<string, string> = {
  most_scenic: "Most Scenic",
  balanced: "Balanced",
  shorter: "Shorter"
};
const VIBE_DISPLAY_NAMES: Record<string, string> = {
  coastal: "Coastal",
  mountain: "Mountain",
  countryside: "Countryside",
  riverside: "Riverside",
  forest: "Forest",
  open_roads: "Open Roads",
  relaxing: "Relaxing",
  winding_roads: "Winding Roads",
  smooth_cruise: "Smooth Cruise",
  quiet: "Quiet",
  hidden_gems: "Hidden Gems",
  minimal_traffic: "Minimal Traffic",
  loop_variety: "Loop Variety",
  scenic: "Scenic",
  clear_my_head: "Clear My Head",
  date_night: "Date Night",
  sunday_cruise: "Sunday Cruise",
  adventure: "Adventure",
  photo_run: "Photo Run",
  photo_worthy: "Photo-Worthy",
  nature_escape: "Nature Escape",
  scenic_reset: "Scenic Reset",
  golden_hour: "Golden Hour",
  sunset: "Sunset",
  sunrise: "Sunrise"
};
const VIBE_PREFERENCE_DEFAULTS: Record<string, Record<string, number>> = {
  coastal: { water: 0.9, greenery: 0.7, elevation: 0.3, solitude: 0.6, curves: 0.45, poi: 0.2 },
  mountain: { water: 0.2, greenery: 0.55, elevation: 0.9, solitude: 0.7, curves: 0.8, poi: 0.2 },
  countryside: { water: 0.4, greenery: 0.7, elevation: 0.45, solitude: 0.7, curves: 0.6, poi: 0.3 },
  riverside: { water: 0.85, greenery: 0.75, elevation: 0.35, solitude: 0.65, curves: 0.45, poi: 0.25 },
  forest: { water: 0.3, greenery: 0.9, elevation: 0.45, solitude: 0.8, curves: 0.45, poi: 0.2 },
  open_roads: { water: 0.25, greenery: 0.45, elevation: 0.35, solitude: 0.4, curves: 0.9, poi: 0.25 },
  relaxing: { water: 0.45, greenery: 0.65, elevation: 0.25, solitude: 0.85, curves: 0.3, poi: 0.25 },
  winding_roads: { water: 0.35, greenery: 0.45, elevation: 0.65, solitude: 0.55, curves: 0.95, poi: 0.15 },
  smooth_cruise: { water: 0.35, greenery: 0.5, elevation: 0.25, solitude: 0.6, curves: 0.25, poi: 0.2 },
  quiet: { water: 0.3, greenery: 0.7, elevation: 0.35, solitude: 0.95, curves: 0.35, poi: 0.1 },
  hidden_gems: { water: 0.45, greenery: 0.7, elevation: 0.55, solitude: 0.8, curves: 0.65, poi: 0.45 },
  minimal_traffic: { water: 0.25, greenery: 0.6, elevation: 0.3, solitude: 0.95, curves: 0.4, poi: 0.1 },
  loop_variety: { water: 0.55, greenery: 0.6, elevation: 0.5, solitude: 0.55, curves: 0.7, poi: 0.35 },
  scenic: { water: 0.65, greenery: 0.7, elevation: 0.6, solitude: 0.65, curves: 0.55, poi: 0.3 },
  clear_my_head: { water: 0.35, greenery: 0.75, elevation: 0.35, solitude: 0.95, curves: 0.25, poi: 0.1 },
  date_night: { water: 0.75, greenery: 0.55, elevation: 0.45, solitude: 0.65, curves: 0.35, poi: 0.55 },
  sunday_cruise: { water: 0.35, greenery: 0.65, elevation: 0.3, solitude: 0.7, curves: 0.45, poi: 0.25 },
  adventure: { water: 0.4, greenery: 0.55, elevation: 0.9, solitude: 0.7, curves: 0.9, poi: 0.25 },
  photo_run: { water: 0.75, greenery: 0.65, elevation: 0.75, solitude: 0.55, curves: 0.6, poi: 0.5 },
  photo_worthy: { water: 0.75, greenery: 0.65, elevation: 0.75, solitude: 0.55, curves: 0.6, poi: 0.5 },
  nature_escape: { water: 0.45, greenery: 0.9, elevation: 0.55, solitude: 0.9, curves: 0.45, poi: 0.15 },
  scenic_reset: { water: 0.55, greenery: 0.7, elevation: 0.45, solitude: 0.8, curves: 0.4, poi: 0.2 },
  golden_hour: { water: 0.75, greenery: 0.5, elevation: 0.55, solitude: 0.55, curves: 0.35, poi: 0.35 },
  sunset: { water: 0.75, greenery: 0.5, elevation: 0.55, solitude: 0.55, curves: 0.35, poi: 0.35 },
  sunrise: { water: 0.7, greenery: 0.55, elevation: 0.55, solitude: 0.6, curves: 0.35, poi: 0.3 }
};
const COMPONENT_DISPLAY_NAMES: Record<string, string> = {
  water: "Water",
  greenery: "Greenery",
  elevation: "Elevation",
  solitude: "Solitude",
  curves: "Curves",
  poi: "Stops"
};
const COMPONENT_ORDER = ["water", "greenery", "elevation", "solitude", "curves", "poi"];
const IOS_DEVICE_REGEX = /iPad|iPhone|iPod/;
type ThemePreference = "dark" | "light" | "system";
type ResolvedTheme = "dark" | "light";

const THEME_STORAGE_KEY = "moodride-theme";
const THEME_OPTIONS: Array<{ value: ThemePreference; label: string; title: string }> = [
  { value: "dark", label: "Night", title: "Use cinematic dark mode" },
  { value: "light", label: "Day", title: "Use editorial light mode" },
  { value: "system", label: "Auto", title: "Follow system theme" }
];
const GOOGLE_TRAVEL_MODES: Record<RouteMode, string> = {
  drive: "driving",
  walk: "walking",
  bike: "bicycling"
};
const APPLE_TRAVEL_FLAGS: Partial<Record<RouteMode, string>> = {
  drive: "d",
  walk: "w"
};

type Coordinate = {
  lat: number;
  lng: number;
};

function sampleWaypoints(coordinates: [number, number][], maxPoints: number): Coordinate[] {
  if (!Array.isArray(coordinates) || coordinates.length === 0 || maxPoints <= 0) {
    return [];
  }

  if (coordinates.length <= maxPoints) {
    return coordinates.map(([lng, lat]) => ({ lat, lng }));
  }

  const sampled: Coordinate[] = [];
  const step = (coordinates.length - 1) / (maxPoints - 1);
  for (let i = 0; i < maxPoints; i++) {
    const index = Math.round(i * step);
    const [lng, lat] = coordinates[Math.min(index, coordinates.length - 1)];
    sampled.push({ lat, lng });
  }

  return sampled.filter((point, index, list) => {
    if (index === 0) {
      return true;
    }
    const previous = list[index - 1];
    return previous.lat !== point.lat || previous.lng !== point.lng;
  });
}

function formatCoordinate(point: Coordinate): string {
  return `${point.lat},${point.lng}`;
}

function buildGoogleMapsUrl(points: Coordinate[], routeMode: RouteMode): string {
  const origin = points[0];
  const destination = points[points.length - 1];
  const waypoints = points.slice(1, -1).map(formatCoordinate).join("|");

  const url = new URL("https://www.google.com/maps/dir/");
  url.searchParams.set("api", "1");
  url.searchParams.set("origin", formatCoordinate(origin));
  url.searchParams.set("destination", formatCoordinate(destination));
  if (waypoints) {
    url.searchParams.set("waypoints", waypoints);
  }
  url.searchParams.set("travelmode", GOOGLE_TRAVEL_MODES[routeMode] ?? "driving");
  return url.toString();
}

function buildAppleMapsUrl(points: Coordinate[], routeMode: RouteMode): string {
  const origin = points[0];
  const destinations = points.slice(1).map(formatCoordinate);

  const url = new URL("https://maps.apple.com/");
  url.searchParams.set("saddr", formatCoordinate(origin));
  if (destinations.length > 0) {
    url.searchParams.set("daddr", destinations.join("+to:"));
  }
  const travelFlag = APPLE_TRAVEL_FLAGS[routeMode];
  if (travelFlag) {
    url.searchParams.set("dirflg", travelFlag);
  }
  return url.toString();
}

function sanitizeFileName(value: string): string {
  const fallback = "moodride_route";
  const trimmed = value.trim();
  if (!trimmed) {
    return fallback;
  }
  return trimmed.replace(/[^a-z0-9-_]+/gi, "_").replace(/_+/g, "_");
}

function buildPreferenceVector(vibes: string[]): Record<string, number> {
  const activeVibes = vibes.length > 0 ? vibes : ["countryside"];
  const accumulators = { water: 0, greenery: 0, elevation: 0, solitude: 0, curves: 0, poi: 0 };
  for (const vibe of activeVibes) {
    const defaults = VIBE_PREFERENCE_DEFAULTS[vibe] ?? VIBE_PREFERENCE_DEFAULTS.countryside;
    accumulators.water += defaults.water;
    accumulators.greenery += defaults.greenery;
    accumulators.elevation += defaults.elevation;
    accumulators.solitude += defaults.solitude;
    accumulators.curves += defaults.curves;
    accumulators.poi += defaults.poi;
  }

  const count = Math.max(1, activeVibes.length);
  return {
    water: Number((accumulators.water / count).toFixed(4)),
    greenery: Number((accumulators.greenery / count).toFixed(4)),
    elevation: Number((accumulators.elevation / count).toFixed(4)),
    solitude: Number((accumulators.solitude / count).toFixed(4)),
    curves: Number((accumulators.curves / count).toFixed(4)),
    poi: Number((accumulators.poi / count).toFixed(4))
  };
}

function escapeXml(value: string): string {
  return value
    .replace(/&/g, "&amp;")
    .replace(/</g, "&lt;")
    .replace(/>/g, "&gt;")
    .replace(/"/g, "&quot;")
    .replace(/'/g, "&apos;");
}

function exportRouteAsGpx(route: RouteDetailResponse, routeName: string) {
  const points = route.geometry.geometry.coordinates
    .map(([lng, lat]) => `      <trkpt lat="${lat}" lon="${lng}" />`)
    .join("\n");

  const gpx = `<?xml version="1.0" encoding="UTF-8"?>
<gpx version="1.1" creator="MoodRide" xmlns="http://www.topografix.com/GPX/1/1">
  <trk>
    <name>${escapeXml(routeName)}</name>
    <trkseg>
${points}
    </trkseg>
  </trk>
</gpx>`;

  const blob = new Blob([gpx], { type: "application/gpx+xml" });
  const downloadUrl = URL.createObjectURL(blob);
  const anchor = document.createElement("a");
  anchor.href = downloadUrl;
  anchor.download = `${sanitizeFileName(routeName)}.gpx`;
  anchor.click();
  URL.revokeObjectURL(downloadUrl);
}

function requestBrowserLocation(
  onSuccess: (latitude: number, longitude: number) => void,
  onError: (message: string) => void
) {
  if (!navigator.geolocation) {
    onError("Geolocation is unavailable in this browser.");
    return;
  }

  navigator.geolocation.getCurrentPosition(
    (position) => {
      onSuccess(Number(position.coords.latitude.toFixed(5)), Number(position.coords.longitude.toFixed(5)));
    },
    (error) => {
      onError(`Unable to fetch geolocation: ${error.message}`);
    },
    { enableHighAccuracy: true, timeout: 8000 }
  );
}

type JobPhase = "idle" | "submitting" | "tracking" | "completed" | "failed";
function staggerStyle(index: number): CSSProperties {
  return { ["--stagger-index" as const]: index } as CSSProperties;
}

function normalizeThemePreference(value: string | null): ThemePreference {
  return value === "light" || value === "system" || value === "dark" ? value : "dark";
}

function readStoredThemePreference(): ThemePreference {
  if (typeof window === "undefined") {
    return "dark";
  }
  return normalizeThemePreference(window.localStorage.getItem(THEME_STORAGE_KEY));
}

function resolveSystemTheme(): ResolvedTheme {
  if (typeof window === "undefined") {
    return "dark";
  }
  return window.matchMedia("(prefers-color-scheme: light)").matches ? "light" : "dark";
}

function uniqueList(values: string[]): string[] {
  return values.filter((value, index, list) => value.length > 0 && list.indexOf(value) === index);
}

function joinHumanList(values: string[]): string {
  const uniqueValues = uniqueList(values);
  if (uniqueValues.length === 0) {
    return "balanced scenic texture";
  }
  if (uniqueValues.length === 1) {
    return uniqueValues[0];
  }
  return `${uniqueValues.slice(0, -1).join(", ")} and ${uniqueValues[uniqueValues.length - 1]}`;
}

function componentStoryPhrase(component: string, value: number | undefined): string {
  const score = typeof value === "number" && Number.isFinite(value) ? value : 0;
  switch (component) {
    case "water":
      return score >= 0.72 ? "strong water views" : "water views";
    case "greenery":
      return score >= 0.58 ? "green corridors" : "open space";
    case "elevation":
      return score >= 0.5 ? "stronger elevation" : "gentle terrain changes";
    case "solitude":
      return score >= 0.58 ? "quiet roads" : "calmer stretches";
    case "curves":
      return score >= 0.34 ? "winding segments" : "light curves";
    case "poi":
      return "a few scenic pull-offs";
    default:
      return "balanced scenic texture";
  }
}

function fallbackVibePhrases(vibes: string[]): string[] {
  const active = new Set(vibes);
  const phrases: string[] = [];
  if (active.has("coastal") || active.has("riverside") || active.has("sunset") || active.has("photo_worthy")) {
    phrases.push("water views");
  }
  if (active.has("mountain") || active.has("adventure") || active.has("winding_roads")) {
    phrases.push("terrain and curves");
  }
  if (active.has("forest") || active.has("nature_escape")) {
    phrases.push("forest cover");
  }
  if (active.has("countryside") || active.has("open_roads") || active.has("sunday_cruise")) {
    phrases.push("open space");
  }
  if (active.has("relaxing") || active.has("smooth_cruise") || active.has("quiet") || active.has("minimal_traffic")) {
    phrases.push("easy cruising");
  }
  if (active.has("hidden_gems")) {
    phrases.push("off-main-road character");
  }
  return phrases;
}

function rankedExplanationComponents(option: RouteOptionResponse | undefined): string[] {
  const explanation = option?.explanation;
  if (!explanation) {
    return [];
  }
  if (Array.isArray(explanation.leadingComponents) && explanation.leadingComponents.length > 0) {
    return explanation.leadingComponents;
  }
  return Object.entries(explanation.weightedContributions ?? {})
    .sort((left, right) => right[1] - left[1])
    .map(([component]) => component);
}

function resolveRouteStoryPrefix(option: RouteOptionResponse | undefined, vibes: string[], rankedComponents: string[]): string {
  const active = new Set(vibes);
  const profile = option?.profile ?? "";
  if (profile === "shorter") {
    return "Compact scenic loop";
  }
  if (active.has("adventure") || active.has("winding_roads") || active.has("mountain") || rankedComponents.includes("curves")) {
    return "More adventurous route";
  }
  if (active.has("relaxing") || active.has("smooth_cruise") || active.has("quiet")) {
    return "Best for a smooth cruise";
  }
  if (active.has("countryside") || active.has("open_roads") || rankedComponents.includes("solitude")) {
    return "Quiet rural loop";
  }
  if (active.has("sunset") || active.has("photo_worthy") || active.has("photo_run") || active.has("date_night")) {
    return "Photo-ready scenic loop";
  }
  if (profile === "balanced") {
    return "Balanced scenic loop";
  }
  return "Curated scenic loop";
}

function buildRouteOptionStory(option: RouteOptionResponse | undefined, vibes: string[]): string {
  const rankedComponents = rankedExplanationComponents(option);
  const averages = option?.explanation?.componentAverages ?? {};
  const componentPhrases = rankedComponents
    .slice(0, 3)
    .map((component) => componentStoryPhrase(component, averages[component]))
    .filter(Boolean);
  const qualities = uniqueList([...componentPhrases, ...fallbackVibePhrases(vibes)]).slice(0, 3);
  return `${resolveRouteStoryPrefix(option, vibes, rankedComponents)} with ${joinHumanList(qualities)}.`;
}

export function RoutePlanner() {
  const [lat, setLat] = useState(45.52);
  const [lng, setLng] = useState(-122.68);
  const [locationQuery, setLocationQuery] = useState("");
  const [locationSuggestions, setLocationSuggestions] = useState<LocationSuggestion[]>([]);
  const [locationLookupPending, setLocationLookupPending] = useState(false);
  const [locationLookupError, setLocationLookupError] = useState<string | null>(null);
  const [locationDropdownVisible, setLocationDropdownVisible] = useState(false);
  const [routeMode, setRouteMode] = useState<RouteMode>("drive");
  const [timeBudgetMinutes, setTimeBudgetMinutes] = useState(60);
  const [vibes, setVibes] = useState<string[]>(["countryside"]);
  const [submission, setSubmission] = useState<RouteSubmissionResponse | null>(null);
  const [jobStatus, setJobStatus] = useState<RouteJobStatusResponse | null>(null);
  const [route, setRoute] = useState<RouteDetailResponse | null>(null);
  const [scenicRegions, setScenicRegions] = useState<ScenicRegionsResponse | null>(null);
  const [phase, setPhase] = useState<JobPhase>("idle");
  const [message, setMessage] = useState<string>("");
  const [pollingEnabled, setPollingEnabled] = useState(false);
  const [selectedRating, setSelectedRating] = useState<number | null>(null);
  const [ratingSubmitted, setRatingSubmitted] = useState(false);
  const [showDebug, setShowDebug] = useState(false);
  const [themePreference, setThemePreference] = useState<ThemePreference>("dark");
  const [resolvedTheme, setResolvedTheme] = useState<ResolvedTheme>(() => resolveSystemTheme());
  const [themeReady, setThemeReady] = useState(false);

  const stopWsRef = useRef<null | (() => void)>(null);
  const pollTimerRef = useRef<ReturnType<typeof setInterval> | null>(null);
  const routeDetailsRef = useRef<Record<string, RouteDetailResponse>>({});
  const locationLookupSequenceRef = useRef(0);

  const formatNumber = (value: number | null | undefined, digits = 2) =>
    typeof value === "number" && Number.isFinite(value) ? value.toFixed(digits) : "N/A";
  const regions = scenicRegions?.regions ?? [];

  const canSubmit = useMemo(
    () => routeMode === "drive" && vibes.length > 0 && vibes.length <= 3 && phase !== "submitting" && phase !== "tracking",
    [phase, routeMode, vibes.length]
  );

  const activeMode = ROUTE_MODES.find((mode) => mode.value === routeMode) ?? ROUTE_MODES[0];

  const formatRouteProfile = (profile: string) => {
    if (PROFILE_DISPLAY_NAMES[profile]) {
      return PROFILE_DISPLAY_NAMES[profile];
    }

    return profile
      .split("_")
      .filter((segment) => segment.length > 0)
      .map((segment) => segment[0].toUpperCase() + segment.slice(1))
      .join(" ");
  };

  const formatVibe = (vibe: string) => {
    if (VIBE_DISPLAY_NAMES[vibe]) {
      return VIBE_DISPLAY_NAMES[vibe];
    }

    return vibe
      .split("_")
      .filter((segment) => segment.length > 0)
      .map((segment) => segment[0].toUpperCase() + segment.slice(1))
      .join(" ");
  };

  const formatComponent = (component: string) => COMPONENT_DISPLAY_NAMES[component] ?? formatVibe(component);

  const formatComponentPercent = (value: number | null | undefined) =>
    typeof value === "number" && Number.isFinite(value) ? `${Math.round(value * 100)}%` : "0%";

  const formatComponentLift = (value: number | null | undefined) => {
    if (typeof value !== "number" || !Number.isFinite(value)) {
      return "baseline n/a";
    }
    const points = Math.round(value * 100);
    const prefix = points > 0 ? "+" : "";
    return `${prefix}${points} pts vs area`;
  };

  const getPrimaryRouteId = (status: RouteJobStatusResponse): string | null => {
    if (status.routeId) {
      return status.routeId;
    }

    if (!Array.isArray(status.routeOptions) || status.routeOptions.length === 0) {
      return null;
    }

    const mostScenic = status.routeOptions.find((option) => option.profile === "most_scenic");
    return mostScenic?.routeId ?? status.routeOptions[0].routeId;
  };

  const setActiveRoute = (detail: RouteDetailResponse) => {
    routeDetailsRef.current[detail.routeId] = detail;
    setRoute(detail);
  };

  const loadRouteDetail = async (routeId: string) => {
    const cached = routeDetailsRef.current[routeId];
    if (cached) {
      return cached;
    }

    const detail = await getRoute(routeId);
    routeDetailsRef.current[detail.routeId] = detail;
    return detail;
  };

  const routeOptions = Array.isArray(route?.routeOptions) ? route.routeOptions : [];

  useEffect(() => {
    setThemePreference(readStoredThemePreference());
    setThemeReady(true);
  }, []);

  useEffect(() => {
    if (!themeReady || typeof window === "undefined") {
      return;
    }

    const mediaQuery = window.matchMedia("(prefers-color-scheme: dark)");
    const applyTheme = () => {
      const nextTheme: ResolvedTheme = themePreference === "system" ? (mediaQuery.matches ? "dark" : "light") : themePreference;
      setResolvedTheme(nextTheme);
      document.documentElement.dataset.theme = nextTheme;
      document.documentElement.dataset.themePreference = themePreference;
      document.documentElement.style.colorScheme = nextTheme;
      window.localStorage.setItem(THEME_STORAGE_KEY, themePreference);
    };

    applyTheme();
    if (themePreference !== "system") {
      return;
    }

    mediaQuery.addEventListener("change", applyTheme);
    return () => mediaQuery.removeEventListener("change", applyTheme);
  }, [themePreference, themeReady]);

  useEffect(() => {
    if (!route) {
      setSelectedRating(null);
      setRatingSubmitted(false);
      return;
    }

    setSelectedRating(route.userRating);
    setRatingSubmitted(Boolean(route.ratedAt));
  }, [route]);

  useEffect(() => {
    requestBrowserLocation(
      (latitude, longitude) => {
        setLat(latitude);
        setLng(longitude);
        setLocationQuery((current) => current || `Current location (${latitude.toFixed(5)}, ${longitude.toFixed(5)})`);
        setMessage("Location acquired from browser geolocation.");
      },
      (locationMessage) => {
        setMessage(locationMessage);
      }
    );

    return () => {
      if (stopWsRef.current) {
        stopWsRef.current();
      }
      if (pollTimerRef.current) {
        clearInterval(pollTimerRef.current);
      }
    };
  }, []);

  useEffect(() => {
    const query = locationQuery.trim();
    if (!locationDropdownVisible || query.length < 2) {
      setLocationSuggestions([]);
      setLocationLookupPending(false);
      setLocationLookupError(null);
      return;
    }

    const currentSequence = ++locationLookupSequenceRef.current;
    const timer = setTimeout(async () => {
      setLocationLookupPending(true);
      setLocationLookupError(null);
      try {
        const suggestions = await searchLocations(query);
        if (currentSequence !== locationLookupSequenceRef.current) {
          return;
        }
        setLocationSuggestions(suggestions);
      } catch {
        if (currentSequence !== locationLookupSequenceRef.current) {
          return;
        }
        setLocationLookupError("Location lookup unavailable right now.");
        setLocationSuggestions([]);
      } finally {
        if (currentSequence === locationLookupSequenceRef.current) {
          setLocationLookupPending(false);
        }
      }
    }, 320);

    return () => clearTimeout(timer);
  }, [locationQuery, locationDropdownVisible]);

  const toggleVibe = (vibe: Vibe) => {
    setVibes((current) => {
      if (current.includes(vibe)) {
        return current.filter((v) => v !== vibe);
      }
      if (current.length >= 3) {
        return current;
      }
      return [...current, vibe];
    });
  };

  const useCurrentLocation = () => {
    requestBrowserLocation(
      (latitude, longitude) => {
        setLat(latitude);
        setLng(longitude);
        setLocationQuery(`Current location (${latitude.toFixed(5)}, ${longitude.toFixed(5)})`);
        setLocationDropdownVisible(false);
        setLocationSuggestions([]);
        setMessage("Location acquired from browser geolocation.");
      },
      (locationMessage) => {
        setMessage(locationMessage);
      }
    );
  };

  const applyLocationSuggestion = (suggestion: LocationSuggestion) => {
    const nextLat = Number(suggestion.lat.toFixed(5));
    const nextLng = Number(suggestion.lng.toFixed(5));
    setLat(nextLat);
    setLng(nextLng);
    setLocationQuery(suggestion.displayName);
    setLocationDropdownVisible(false);
    setLocationSuggestions([]);
    setLocationLookupError(null);
    setMessage(`Location set to ${suggestion.displayName}.`);
  };

  const refreshScenicRegions = async () => {
    try {
      const response = await getScenicRegions(lat, lng, 50, 12, vibes[0]);
      setScenicRegions(response);
    } catch (error) {
      setMessage(`Scenic region fetch failed: ${(error as Error).message}`);
    }
  };

  const stopAsyncTracking = () => {
    if (stopWsRef.current) {
      stopWsRef.current();
      stopWsRef.current = null;
    }
    if (pollTimerRef.current) {
      clearInterval(pollTimerRef.current);
      pollTimerRef.current = null;
    }
    setPollingEnabled(false);
  };

  const onSocketEvent = async (event: JobSocketEvent) => {
    if (!event.jobId || !submission || event.jobId !== submission.jobId) {
      return;
    }

    if (event.routeId) {
      const detail = await loadRouteDetail(event.routeId);
      setActiveRoute(detail);
      setPhase("completed");
      setMessage("Route is ready.");
      stopAsyncTracking();
      return;
    }

    if (event.reason) {
      setPhase("failed");
      setMessage(`Route generation failed: ${event.reason}`);
      stopAsyncTracking();
    }
  };

  const startPolling = (jobId: string) => {
    setPollingEnabled(true);

    const tick = async () => {
      try {
        const status = await getJobStatus(jobId);
        setJobStatus(status);

        if (status.status === "COMPLETED") {
          const primaryRouteId = getPrimaryRouteId(status);
          if (primaryRouteId) {
            const detail = await loadRouteDetail(primaryRouteId);
            setActiveRoute(detail);
            setPhase("completed");
            setMessage("Route is ready.");
            stopAsyncTracking();
          }
        } else if (["FAILED", "TIMEOUT"].includes(status.status)) {
          setPhase("failed");
          setMessage(`Route job ended with status ${status.status}: ${status.reason ?? "no reason"}`);
          stopAsyncTracking();
        }
      } catch (error) {
        setMessage(`Polling status failed: ${(error as Error).message}`);
      }
    };

    void tick();
    pollTimerRef.current = setInterval(() => void tick(), 2500);
  };

  const submit = async () => {
    setPhase("submitting");
    setMessage("");
    setSubmission(null);
    setJobStatus(null);
    setRoute(null);
    routeDetailsRef.current = {};

    try {
      await refreshScenicRegions();

      const response = await submitRoute({
        userId: crypto.randomUUID(),
        lat,
        lng,
        timeBudgetMinutes,
        routeMode,
        vibes,
        preferenceVector: buildPreferenceVector(vibes)
      });

      setSubmission(response);
      setPhase("tracking");
      setMessage("Route submitted.");

      stopWsRef.current = connectJobChannel(
        response.jobId,
        response.wsChannel,
        (event) => {
          void onSocketEvent(event);
        },
        () => {
          setMessage("Live updates disconnected. Continuing with status checks.");
          if (!pollingEnabled) {
            startPolling(response.jobId);
          }
        }
      );

      startPolling(response.jobId);
    } catch (error) {
      setPhase("failed");
      setMessage(`Route submission failed: ${(error as Error).message}`);
      stopAsyncTracking();
    }
  };

  const regenerateRoute = () => {
    void submit();
  };

  const selectRouteOption = async (option: RouteOptionResponse) => {
    if (route?.routeId === option.routeId) {
      return;
    }

    try {
      const detail = await loadRouteDetail(option.routeId);
      setActiveRoute(detail);
      setMessage(`Showing ${formatRouteProfile(option.profile)} route option.`);
    } catch (error) {
      setMessage(`Failed to load ${formatRouteProfile(option.profile)} option: ${(error as Error).message}`);
    }
  };

  const startDrive = () => {
    if (!route) {
      return;
    }

    const points = route.geometry.geometry.coordinates;
    if (points.length < 2) {
      setMessage("Route geometry is unavailable for navigation handoff.");
      return;
    }

    const sampledPoints = sampleWaypoints(points, 15);
    if (sampledPoints.length < 2) {
      setMessage("Not enough route points to launch navigation.");
      return;
    }

    const isIos = IOS_DEVICE_REGEX.test(navigator.userAgent);
    const activeRouteMode = route.routeMode ?? "drive";
    const navigationUrl = isIos ? buildAppleMapsUrl(sampledPoints, activeRouteMode) : buildGoogleMapsUrl(sampledPoints, activeRouteMode);
    window.open(navigationUrl, "_blank", "noopener,noreferrer");
    setMessage(isIos ? "Opening Apple Maps." : "Opening Google Maps.");
  };

  const exportGpx = () => {
    if (!route) {
      return;
    }

    const optionProfile = routeOptions.find((option) => option.routeId === route.routeId)?.profile;
    const routeName = optionProfile ? `${formatRouteProfile(optionProfile)} Loop` : "MoodRide Scenic Loop";
    exportRouteAsGpx(route, routeName);
    setMessage("GPX export started.");
  };

  const submitRating = async () => {
    if (!route || selectedRating == null) {
      return;
    }

    try {
      const response = await submitRouteRating(route.routeId, selectedRating);
      setRoute({
        ...route,
        userRating: response.rating,
        ratedAt: response.ratedAt
      });
      setRatingSubmitted(true);
      setMessage("Rating saved and user feedback events published.");
    } catch (error) {
      setMessage(`Rating submit failed: ${(error as Error).message}`);
    }
  };

  const activeRouteOption = routeOptions.find((option) => option.routeId === route?.routeId);
  const activeRouteProfile = activeRouteOption?.profile;
  const activeExplanation = activeRouteOption?.explanation;
  const activeProfileLabel = activeRouteProfile ? formatRouteProfile(activeRouteProfile) : "Route";
  const currentRouteVibes = route && Array.isArray(route.vibes) && route.vibes.length > 0 ? route.vibes : vibes;
  const activeRouteStory = route ? buildRouteOptionStory(activeRouteOption, currentRouteVibes) : "";
  const routeModeLabel = route?.routeMode === "walk" ? "Walk" : route?.routeMode === "bike" ? "Ride" : "Drive";
  const submitLabel = phase === "submitting" || phase === "tracking" ? "Generating Route..." : `Generate ${activeMode.label}`;

  return (
    <main className="planner-page">
      <section className="product-hero panel-stagger" style={staggerStyle(0)}>
        <nav className="product-nav" aria-label="MoodRide">
          <span className="brand-mark">MoodRide</span>
          <div className="nav-actions">
            <span className="nav-pill">Canada scenic beta</span>
            <div className="theme-switch" role="group" aria-label="Theme">
              {THEME_OPTIONS.map((option) => (
                <button
                  type="button"
                  key={option.value}
                  className={themePreference === option.value ? "active" : ""}
                  onClick={() => setThemePreference(option.value)}
                  aria-pressed={themePreference === option.value}
                  title={option.title}
                >
                  {option.label}
                </button>
              ))}
            </div>
          </div>
        </nav>
        <div className="hero-layout">
          <div className="hero-copy">
            <span className="planner-eyebrow">Scenic route intelligence</span>
            <h1 className="planner-title">Beautiful loops for the time you actually have.</h1>
            <p className="planner-subtitle">
              Generate scenic routes from a starting point, compare route personalities, then launch navigation or export GPX.
            </p>
          </div>
          <div className="hero-stats" aria-label="MoodRide status">
            <div className="hero-stat">
              <p className="hero-stat-label">Coverage</p>
              <p className="hero-stat-value">Canada</p>
            </div>
            <div className="hero-stat">
              <p className="hero-stat-label">Live Mode</p>
              <p className="hero-stat-value">{activeMode.label}</p>
            </div>
            <div className="hero-stat">
              <p className="hero-stat-label">Route</p>
              <p className="hero-stat-value">{route ? activeProfileLabel : phase}</p>
            </div>
          </div>
        </div>
      </section>
      <div className="grid grid-2">
        <section className="panel panel-stagger" style={staggerStyle(1)}>
          <div className="panel-title-row">
            <h2>Plan A Route</h2>
            <span className="small">{activeMode.status}</span>
          </div>

          <label>Mode</label>
          <div className="mode-selector" role="tablist" aria-label="Route mode">
            {ROUTE_MODES.map((mode) => {
              const active = routeMode === mode.value;
              return (
                <button
                  type="button"
                  key={mode.value}
                  className={`mode-option ${active ? "active" : ""} ${mode.enabled ? "" : "locked"}`}
                  onClick={() => {
                    setRouteMode(mode.value);
                    if (!mode.enabled) {
                      setMessage(`${mode.label} mode is planned as a focused city pilot after Canada driving launch.`);
                    }
                  }}
                  role="tab"
                  aria-selected={active}
                >
                  <span>{mode.label}</span>
                  <small>{mode.status}</small>
                </button>
              );
            })}
          </div>

          <label htmlFor="location-search">Search Place</label>
          <div className="location-search-shell">
            <input
              id="location-search"
              type="text"
              value={locationQuery}
              onChange={(e) => {
                setLocationQuery(e.target.value);
                setLocationDropdownVisible(true);
              }}
              onFocus={() => setLocationDropdownVisible(true)}
              onBlur={() => {
                setTimeout(() => setLocationDropdownVisible(false), 120);
              }}
              onKeyDown={(event) => {
                if (event.key === "Enter" && locationSuggestions.length > 0) {
                  event.preventDefault();
                  applyLocationSuggestion(locationSuggestions[0]);
                }
              }}
              placeholder="Search city, address, or landmark"
              autoComplete="off"
            />
            {locationDropdownVisible && (locationLookupPending || locationSuggestions.length > 0 || locationLookupError) && (
              <div className="location-search-results">
                {locationLookupPending && <p className="small">Searching locations...</p>}
                {!locationLookupPending && locationLookupError && <p className="small error">{locationLookupError}</p>}
                {!locationLookupPending && !locationLookupError && locationSuggestions.length === 0 && (
                  <p className="small">No matches yet. Keep typing.</p>
                )}
                {locationSuggestions.map((suggestion) => (
                  <button
                    key={suggestion.placeId}
                    type="button"
                    className="location-suggestion-btn"
                    onClick={() => applyLocationSuggestion(suggestion)}
                  >
                    {suggestion.displayName}
                  </button>
                ))}
              </div>
            )}
          </div>

          <label htmlFor="lat">Latitude</label>
          <input id="lat" type="number" value={lat} onChange={(e) => setLat(Number(e.target.value))} step="0.00001" />

          <label htmlFor="lng">Longitude</label>
          <input id="lng" type="number" value={lng} onChange={(e) => setLng(Number(e.target.value))} step="0.00001" />

          <button type="button" onClick={useCurrentLocation} className="location-btn">
            Use Current Location
          </button>

          <label htmlFor="budget">Time Budget</label>
          <select id="budget" value={timeBudgetMinutes} onChange={(e) => setTimeBudgetMinutes(Number(e.target.value))}>
            {TIME_BUDGET_OPTIONS.map((value) => (
              <option key={value} value={value}>
                {value} minutes
              </option>
            ))}
          </select>

          <label>Vibes (max 3)</label>
          <div className="vibe-groups" aria-label="Route vibes">
            {VIBE_GROUPS.map((group) => (
              <div className="vibe-group" key={group.title}>
                <div className="vibe-group-copy">
                  <span>{group.title}</span>
                  <small>{group.description}</small>
                </div>
                <div className="tag-list compact">
                  {group.options.map((vibe) => {
                    const active = vibes.includes(vibe);
                    return (
                      <button
                        type="button"
                        className={`tag ${active ? "active" : ""}`}
                        key={vibe}
                        onClick={() => toggleVibe(vibe)}
                        aria-pressed={active}
                      >
                        {formatVibe(vibe)}
                      </button>
                    );
                  })}
                </div>
              </div>
            ))}
          </div>

          <button type="button" onClick={() => void submit()} disabled={!canSubmit}>
            {submitLabel}
          </button>

          {route && phase === "completed" && (
            <div className="actions-row">
              <button type="button" className="secondary-btn" onClick={regenerateRoute}>
                Regenerate
              </button>
              <button type="button" className="primary-drive-btn" onClick={startDrive}>
                Start {routeModeLabel}
              </button>
              <button type="button" className="secondary-btn" onClick={exportGpx}>
                Export GPX
              </button>
            </div>
          )}

          <div className="status-row">
            <span className={`status-pill status-${phase}`}>{phase}</span>
            <span className="small">
              {route ? `${route.geometry.geometry.coordinates.length} geometry points loaded` : `${activeMode.label} mode ready.`}
            </span>
          </div>
          {message && <div className={`message-banner ${phase === "failed" ? "error" : ""}`}>{message}</div>}

          {showDebug && submission && (
            <div className="small">
              <p>Job ID: {submission.jobId}</p>
              <p>Estimated completion: {submission.estimatedCompletionSeconds}s</p>
              <p>WS Channel: {submission.wsChannel}</p>
            </div>
          )}

          {showDebug && jobStatus && (
            <div className="small">
              <p>Backend status: {jobStatus.status}</p>
              <p>
                Retry: {jobStatus.retryCount}/{jobStatus.maxRetries}
              </p>
              {jobStatus.estimatedRemainingSeconds !== null && <p>ETA: {jobStatus.estimatedRemainingSeconds}s</p>}
            </div>
          )}
        </section>

        <section className="grid">
          <div className="panel panel-stagger" style={staggerStyle(2)}>
            <div className="panel-title-row">
              <h2>Route Map</h2>
              {route && <span className="small">{activeProfileLabel}</span>}
            </div>
            <RouteMap route={route} theme={resolvedTheme} />
          </div>

          <div className="panel panel-stagger" style={staggerStyle(3)}>
            <div className="panel-title-row">
              <h2>Route Details</h2>
              {route && <span className="small">{activeProfileLabel}</span>}
            </div>
            {!route && <p className="small">No completed route yet.</p>}
            {route && (
              <div className="route-detail-surface" key={route.routeId}>
                {routeOptions.length > 0 && (
                  <div className="route-options">
                    <p className="small">Choose route option</p>
                    <div className="route-options-list">
                      {routeOptions.map((option) => {
                        const active = option.routeId === route.routeId;
                        return (
                          <button
                            type="button"
                            key={option.routeId}
                            className={`route-option ${active ? "active" : ""}`}
                            onClick={() => void selectRouteOption(option)}
                          >
                            <span className="route-option-title">{formatRouteProfile(option.profile)}</span>
                            <span className="small">
                              {formatNumber(option.totalDistanceKm, 1)} km · {option.estimatedDurationMinutes} min ·
                              score {formatNumber(option.scenicScore, 2)}
                            </span>
                            <span className="route-option-human">
                              {buildRouteOptionStory(option, currentRouteVibes)}
                            </span>
                            {option.explanation?.leadingComponents?.length ? (
                              <span className="route-option-reasons">
                                {option.explanation.leadingComponents.slice(0, 3).map(formatComponent).join(" · ")}
                              </span>
                            ) : null}
                          </button>
                        );
                      })}
                    </div>
                  </div>
                )}
                {activeRouteStory && (
                  <div className="route-story">
                    <p className="detail-metric-label">Route Read</p>
                    <p>{activeRouteStory}</p>
                  </div>
                )}
                <div className="detail-metrics">
                  <div className="detail-metric">
                    <p className="detail-metric-label">Scenic Score</p>
                    <p className="detail-metric-value">{formatNumber(route.scenicScore, 2)}</p>
                  </div>
                  <div className="detail-metric">
                    <p className="detail-metric-label">Distance</p>
                    <p className="detail-metric-value">{formatNumber(route.totalDistanceKm, 1)} km</p>
                  </div>
                  <div className="detail-metric">
                    <p className="detail-metric-label">Duration</p>
                    <p className="detail-metric-value">{route.estimatedDurationMinutes} min</p>
                  </div>
                </div>
                {activeExplanation && (
                  <div className="route-explanation">
                    <div className="route-explanation-header">
                      <p className="detail-metric-label">Why this option</p>
                      <span className="small">
                        {activeExplanation.sampleTileCount} route tiles · {activeExplanation.baselineTileCount} area tiles
                      </span>
                    </div>
                    <p className="route-explanation-summary">{activeExplanation.summary}</p>
                    <div className="component-bars" aria-label="Route weighted component contribution">
                      {COMPONENT_ORDER.map((component) => {
                        const routeAverage = activeExplanation.componentAverages?.[component] ?? 0;
                        const weightedContribution = activeExplanation.weightedContributions?.[component] ?? routeAverage;
                        const lift = activeExplanation.componentLifts?.[component];
                        return (
                          <div className="component-row" key={component}>
                            <span className="component-label">
                              {formatComponent(component)}
                              <small>
                                avg {formatComponentPercent(routeAverage)} · {formatComponentLift(lift)}
                              </small>
                            </span>
                            <div className="component-track" aria-hidden="true">
                              <span
                                className="component-fill"
                                style={{ width: `${Math.max(0, Math.min(100, weightedContribution * 100))}%` }}
                              />
                            </div>
                            <strong>{formatComponentPercent(weightedContribution)}</strong>
                          </div>
                        );
                      })}
                    </div>
                  </div>
                )}
                {showDebug && <p className="small">Algorithm: {route.algorithmVersion}</p>}
              </div>
            )}
          </div>

          <div className="panel panel-stagger" style={staggerStyle(4)}>
            <div className="panel-title-row">
              <h2>Scenic Highlights</h2>
            </div>
            <ScenicHighlightsPanel route={route} />
          </div>

          {route && phase === "completed" && (
            <div className="panel panel-stagger" style={staggerStyle(5)}>
              <div className="panel-title-row">
                <h2>Rate This Route</h2>
              </div>
              <p className="small">How was this route? (1-5 stars)</p>
              <div className="rating-row" role="group" aria-label="Drive rating">
                {[1, 2, 3, 4, 5].map((ratingValue) => (
                  <button
                    type="button"
                    key={ratingValue}
                    className={`rating-star ${selectedRating === ratingValue ? "active" : ""}`}
                    onClick={() => setSelectedRating(ratingValue)}
                  >
                    {ratingValue}
                  </button>
                ))}
              </div>
              <button type="button" onClick={() => void submitRating()} disabled={selectedRating == null || ratingSubmitted}>
                {ratingSubmitted ? "Rating Saved" : "Submit Rating"}
              </button>
            </div>
          )}

          {showDebug && (
            <div className="panel panel-stagger" style={staggerStyle(6)}>
              <div className="panel-title-row">
                <h2>Nearby Scenic Regions</h2>
              </div>
              {!regions.length && <p className="small">No region data loaded yet.</p>}
              {regions.length > 0 && (
                <>
                  <p className="small">
                    Showing {regions.length} of {scenicRegions?.totalRegions ?? regions.length} scenic regions.
                  </p>
                  {scenicRegions?.boundingBox && (
                    <p className="small">
                      Bounds: N {formatNumber(scenicRegions.boundingBox.north, 4)}, S {formatNumber(scenicRegions.boundingBox.south, 4)}, E{" "}
                      {formatNumber(scenicRegions.boundingBox.east, 4)}, W {formatNumber(scenicRegions.boundingBox.west, 4)}
                    </p>
                  )}
                  <ul>
                    {regions.map((region) => (
                      <li key={region.h3Index}>
                        {region.h3Index} - score {formatNumber(region.scenicScore, 2)} [{region.dominantFeature}, confidence{" "}
                        {formatNumber(region.confidence, 2)}] ({formatNumber(region.centerLat, 4)}, {formatNumber(region.centerLng, 4)})
                      </li>
                    ))}
                  </ul>
                </>
              )}
            </div>
          )}

          <button type="button" className="debug-toggle" onClick={() => setShowDebug((current) => !current)}>
            {showDebug ? "Hide Debug" : "Debug"}
          </button>
        </section>
      </div>
    </main>
  );
}


