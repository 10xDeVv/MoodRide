"use client";

import { useState, useCallback, useRef, useEffect } from "react";
import {
  MapPin, Navigation, Bike, Footprints, Car,
  Clock, RefreshCw, ChevronDown, ChevronUp,
  AlertTriangle, Download, Map as MapIcon, ArrowRight, Loader2,
  Waves, Trees, Mountain, Eye, Route,
  Sunset, Camera, Compass, Wind, Coffee, Zap, Moon, Sun,
  type LucideIcon
} from "lucide-react";
import { RouteMap } from "./RouteMap";
import { BottomSheet, type BottomSheetState } from "./BottomSheet";
import { coarseAnalyticsRegionKey, submitRoute, getJobStatus, getRoute, searchLocations, trackAnalyticsEvent } from "@/lib/api";
import { connectJobChannel } from "@/lib/ws";
import type {
  RouteDetailResponse,
  RouteOptionResponse,
  RouteJobStatusResponse,
  RouteMode,
  Vibe,
  LocationSuggestion
} from "@/lib/types";

// ─── Constants ────────────────────────────────────────────────────────────────

const VIBE_CONFIG: Array<{ vibe: Vibe; label: string; Icon: LucideIcon }> = [
  { vibe: "coastal",         label: "Coastal",       Icon: Waves },
  { vibe: "mountain",        label: "Mountain",      Icon: Mountain },
  { vibe: "countryside",     label: "Country",       Icon: Trees },
  { vibe: "riverside",       label: "Riverside",     Icon: Waves },
  { vibe: "forest",          label: "Forest",        Icon: Trees },
  { vibe: "open_roads",      label: "Open Roads",    Icon: Route },
  { vibe: "relaxing",        label: "Relaxing",      Icon: Wind },
  { vibe: "winding_roads",   label: "Winding",       Icon: Route },
  { vibe: "smooth_cruise",   label: "Cruise",        Icon: Car },
  { vibe: "quiet",           label: "Quiet",         Icon: Eye },
  { vibe: "hidden_gems",     label: "Hidden",        Icon: Compass },
  { vibe: "minimal_traffic", label: "Low Traffic",   Icon: Navigation },
  { vibe: "scenic",          label: "Scenic",        Icon: Camera },
  { vibe: "sunset",          label: "Sunset",        Icon: Sunset },
  { vibe: "photo_worthy",    label: "Photo",         Icon: Camera },
  { vibe: "nature_escape",   label: "Nature",        Icon: Trees },
  { vibe: "sunday_cruise",   label: "Sunday",        Icon: Coffee },
  { vibe: "adventure",       label: "Adventure",     Icon: Zap },
];

const VIBE_PREFERENCE_DEFAULTS: Record<string, Record<string, number>> = {
  coastal:         { water: 0.9, greenery: 0.7, elevation: 0.3, solitude: 0.6, curves: 0.45, poi: 0.2 },
  mountain:        { water: 0.2, greenery: 0.55, elevation: 0.9, solitude: 0.7, curves: 0.8, poi: 0.2 },
  countryside:     { water: 0.4, greenery: 0.7, elevation: 0.45, solitude: 0.7, curves: 0.6, poi: 0.3 },
  riverside:       { water: 0.85, greenery: 0.75, elevation: 0.35, solitude: 0.65, curves: 0.45, poi: 0.25 },
  forest:          { water: 0.3, greenery: 0.9, elevation: 0.45, solitude: 0.8, curves: 0.45, poi: 0.2 },
  open_roads:      { water: 0.25, greenery: 0.45, elevation: 0.35, solitude: 0.4, curves: 0.9, poi: 0.25 },
  relaxing:        { water: 0.45, greenery: 0.65, elevation: 0.25, solitude: 0.85, curves: 0.3, poi: 0.25 },
  winding_roads:   { water: 0.35, greenery: 0.45, elevation: 0.65, solitude: 0.55, curves: 0.95, poi: 0.15 },
  smooth_cruise:   { water: 0.35, greenery: 0.5, elevation: 0.25, solitude: 0.6, curves: 0.25, poi: 0.2 },
  quiet:           { water: 0.3, greenery: 0.7, elevation: 0.35, solitude: 0.95, curves: 0.35, poi: 0.1 },
  hidden_gems:     { water: 0.45, greenery: 0.7, elevation: 0.55, solitude: 0.8, curves: 0.65, poi: 0.45 },
  minimal_traffic: { water: 0.25, greenery: 0.6, elevation: 0.3, solitude: 0.95, curves: 0.4, poi: 0.1 },
  scenic:          { water: 0.65, greenery: 0.7, elevation: 0.6, solitude: 0.65, curves: 0.55, poi: 0.3 },
  sunset:          { water: 0.75, greenery: 0.5, elevation: 0.55, solitude: 0.55, curves: 0.35, poi: 0.35 },
  photo_worthy:    { water: 0.75, greenery: 0.65, elevation: 0.75, solitude: 0.55, curves: 0.6, poi: 0.5 },
  nature_escape:   { water: 0.45, greenery: 0.9, elevation: 0.55, solitude: 0.9, curves: 0.45, poi: 0.15 },
  sunday_cruise:   { water: 0.35, greenery: 0.65, elevation: 0.3, solitude: 0.7, curves: 0.45, poi: 0.25 },
  adventure:       { water: 0.4, greenery: 0.55, elevation: 0.9, solitude: 0.7, curves: 0.9, poi: 0.25 }
};

const USER_SIGNAL_ORDER = ["water", "elevation", "solitude", "greenery", "curves"];

const ROUTE_SIGNAL_LABELS: Record<string, string> = {
  water: "Waterfront",
  greenery: "Green cover",
  elevation: "Rolling terrain",
  solitude: "Quiet roads",
  curves: "Curves"
};

const TIME_BUDGET_OPTIONS = [30, 60, 90, 120] as const;

const ROUTE_MODES: Array<{ value: RouteMode; label: string; status: string; enabled: boolean }> = [
  { value: "drive", label: "Drive",  status: "Live",  enabled: true },
  { value: "walk",  label: "Walk",   status: "Soon",  enabled: false },
  { value: "bike",  label: "Bike",   status: "Soon",  enabled: false }
];

const LOADING_STEPS: Array<{ id: string; label: string; Icon: LucideIcon }> = [
  { id: "roads",     label: "Reading nearby roads",       Icon: MapIcon },
  { id: "corridors", label: "Tracing scenic corridors",   Icon: Route },
  { id: "time",      label: "Balancing drive time",       Icon: Clock },
  { id: "options",   label: "Ranking route options",      Icon: Compass }
];

const GOOGLE_TRAVEL_MODES: Record<RouteMode, string> = { drive: "driving", walk: "walking", bike: "bicycling" };
const APPLE_TRAVEL_FLAGS: Partial<Record<RouteMode, string>> = { drive: "d", walk: "w" };

// ─── Helpers ──────────────────────────────────────────────────────────────────

function buildPreferenceVector(vibes: string[]): Record<string, number> {
  const active = vibes.length > 0 ? vibes : ["countryside"];
  const acc = { water: 0, greenery: 0, elevation: 0, solitude: 0, curves: 0, poi: 0 };
  for (const v of active) {
    const d = VIBE_PREFERENCE_DEFAULTS[v] ?? VIBE_PREFERENCE_DEFAULTS.countryside;
    for (const k of Object.keys(acc) as Array<keyof typeof acc>) acc[k] += d[k];
  }
  const n = active.length;
  return Object.fromEntries(Object.entries(acc).map(([k, v]) => [k, Number((v / n).toFixed(4))]));
}

function escapeXml(s: string) {
  return s.replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;").replace(/"/g, "&quot;");
}

function exportGpx(route: RouteDetailResponse, name: string) {
  const pts = route.geometry.geometry.coordinates
    .map(([lng, lat]) => `      <trkpt lat="${lat}" lon="${lng}" />`)
    .join("\n");
  const gpx = `<?xml version="1.0" encoding="UTF-8"?>\n<gpx version="1.1" creator="Wayward" xmlns="http://www.topografix.com/GPX/1/1">\n  <trk>\n    <name>${escapeXml(name)}</name>\n    <trkseg>\n${pts}\n    </trkseg>\n  </trk>\n</gpx>`;
  const blob = new Blob([gpx], { type: "application/gpx+xml" });
  const url = URL.createObjectURL(blob);
  const a = document.createElement("a");
  a.href = url;
  a.download = `${name.replace(/[^a-z0-9-_]/gi, "_")}.gpx`;
  a.click();
  URL.revokeObjectURL(url);
}

function sampleWaypoints(coords: [number, number][], max: number) {
  if (coords.length <= max) return coords.map(([lng, lat]) => ({ lat, lng }));
  const step = (coords.length - 1) / (max - 1);
  return Array.from({ length: max }, (_, i) => {
    const [lng, lat] = coords[Math.min(Math.round(i * step), coords.length - 1)];
    return { lat, lng };
  });
}

function buildGoogleMapsUrl(coords: [number, number][], mode: RouteMode) {
  const pts = sampleWaypoints(coords, 10);
  const url = new URL("https://www.google.com/maps/dir/");
  url.searchParams.set("api", "1");
  url.searchParams.set("origin", `${pts[0].lat},${pts[0].lng}`);
  url.searchParams.set("destination", `${pts[pts.length - 1].lat},${pts[pts.length - 1].lng}`);
  const wps = pts.slice(1, -1).map((p) => `${p.lat},${p.lng}`).join("|");
  if (wps) url.searchParams.set("waypoints", wps);
  url.searchParams.set("travelmode", GOOGLE_TRAVEL_MODES[mode]);
  return url.toString();
}

function buildAppleMapsUrl(coords: [number, number][], mode: RouteMode) {
  const pts = sampleWaypoints(coords, 5);
  const url = new URL("https://maps.apple.com/");
  url.searchParams.set("saddr", `${pts[0].lat},${pts[0].lng}`);
  url.searchParams.set("daddr", pts.slice(1).map((p) => `${p.lat},${p.lng}`).join("+to:"));
  const flag = APPLE_TRAVEL_FLAGS[mode];
  if (flag) url.searchParams.set("dirflg", flag);
  return url.toString();
}

function formatDistFromKm(km: number) {
  return km >= 10 ? `${Math.round(km)}` : km.toFixed(1);
}

function formatDur(minutes: number) {
  if (minutes < 60) return `${Math.round(minutes)}m`;
  const h = Math.floor(minutes / 60);
  const rem = Math.round(minutes % 60);
  return rem > 0 ? `${h}h${rem}m` : `${h}h`;
}

// ─── Types ────────────────────────────────────────────────────────────────────
type Phase = "idle" | "submitting" | "tracking" | "completed" | "failed";
type AppTheme = "day" | "night";
type RouteSessionState = "planning" | "generating" | "resultsOpen" | "resultsMinimized" | "planningNewRoute";

const PHONE_MAX_WIDTH = 767;
const TABLET_MAX_WIDTH = 1440;

type FailureGuidance = {
  failureCode: string | null;
  suggestedVibes: string[];
  suggestedActions: string[];
};

// ─── Header ───────────────────────────────────────────────────────────────────
function AppHeader({ theme, onThemeToggle }: { theme: AppTheme; onThemeToggle: () => void }) {
  const ToggleIcon = theme === "day" ? Moon : Sun;
  const nextThemeLabel = theme === "day" ? "Switch to dark mode" : "Switch to day mode";

  return (
    <header className="app-header">
      <svg className="header-glass-filter" aria-hidden="true" focusable="false" width="0" height="0">
        <defs>
          <filter id="wayward-glass-distortion" x="0%" y="0%" width="100%" height="100%">
            <feTurbulence
              type="fractalNoise"
              baseFrequency="0.0085 0.0085"
              numOctaves="2"
              seed="92"
              result="noise"
            />
            <feGaussianBlur in="noise" stdDeviation="2" result="blurred" />
            <feDisplacementMap
              in="SourceGraphic"
              in2="blurred"
              scale="118"
              xChannelSelector="R"
              yChannelSelector="G"
            />
          </filter>
        </defs>
      </svg>
      <span className="header-glass-refraction" aria-hidden="true" />
      <div className="header-brand-block">
        <h1 className="wayward-wordmark header-logo">Wayward</h1>
      </div>
      <button
        className="theme-toggle-btn"
        type="button"
        aria-label={nextThemeLabel}
        title={nextThemeLabel}
        onClick={onThemeToggle}
      >
        <ToggleIcon size={18} strokeWidth={2.3} />
      </button>
    </header>
  );
}

// ─── Planner Panel ────────────────────────────────────────────────────────────
interface PlannerPanelProps {
  lat: number;
  lng: number;
  locationQuery: string;
  locationSuggestions: LocationSuggestion[];
  locationPending: boolean;
  locationError: string | null;
  showDropdown: boolean;
  routeMode: RouteMode;
  timeBudget: number;
  vibes: string[];
  phase: Phase;
  statusMessage: string;
  compactVibeList?: boolean;
  onLatChange: (v: number) => void;
  onLngChange: (v: number) => void;
  onLocationQueryChange: (v: string) => void;
  onSuggestionSelect: (s: LocationSuggestion) => void;
  onGeolocate: () => void;
  onModeChange: (m: RouteMode) => void;
  onTimeBudgetChange: (t: number) => void;
  onVibeToggle: (v: string) => void;
  onGenerate: () => void;
}

function PlannerPanel({
  // eslint-disable-next-line @typescript-eslint/no-unused-vars
  lat, lng, locationQuery, locationSuggestions, locationPending, locationError, showDropdown,
  routeMode, timeBudget, vibes, phase, statusMessage, compactVibeList = false,
  // eslint-disable-next-line @typescript-eslint/no-unused-vars
  onLatChange, onLngChange, onLocationQueryChange, onSuggestionSelect, onGeolocate,
  onModeChange, onTimeBudgetChange, onVibeToggle, onGenerate
}: PlannerPanelProps) {
  const [showAllVibes, setShowAllVibes] = useState(false);
  const canGenerate = routeMode === "drive" && vibes.length > 0 && phase === "idle";
  const isGenerating = phase === "submitting" || phase === "tracking";
  const visibleVibes = compactVibeList && !showAllVibes
    ? VIBE_CONFIG.slice(0, 9)
    : VIBE_CONFIG;
  const hiddenVibeCount = Math.max(VIBE_CONFIG.length - 9, 0);

  return (
    <aside className="planner-panel">
      {/* Panel Header */}
      <div className="panel-header">
        <h2 className="panel-title">ROUTE PLANNER</h2>
        <p className="panel-subtitle">Scenic route generation</p>
      </div>

      <div className="panel-body">

        {/* ── Starting Point ── */}
        <div className="form-section">
          <label className="form-label">Starting Point</label>
          <div className="location-input-wrap">
            <MapPin className="location-input-icon" size={16} />
            <input
              className="location-input"
              type="text"
              placeholder="ENTER LOCATION"
              value={locationQuery}
              onChange={(e) => onLocationQueryChange(e.target.value)}
              autoComplete="off"
              aria-label="Starting location search"
            />
            {showDropdown && (
              <div className="location-suggestions">
                {locationPending && (
                  <div className="suggestion-item" style={{ opacity: 0.6 }}>Searching…</div>
                )}
                {locationError && (
                  <div className="suggestion-item" style={{ color: "#ba1a1a" }}>{locationError}</div>
                )}
                {!locationPending && locationSuggestions.map((s, i) => (
                  <button
                    key={i}
                    className="suggestion-item"
                    onClick={() => onSuggestionSelect(s)}
                    type="button"
                  >
                    {s.displayName}
                  </button>
                ))}
                {!locationPending && !locationError && locationSuggestions.length === 0 && (
                  <div className="suggestion-item" style={{ opacity: 0.6 }}>No results found</div>
                )}
              </div>
            )}
          </div>
          <button className="use-location-btn" onClick={onGeolocate} type="button">
            <Navigation size={14} />
            USE MY LOCATION
          </button>


        </div>

        {/* ── Travel Mode ── */}
        <div className="form-section">
          <label className="form-label">Travel Mode</label>
          <div className="mode-selector">
            {ROUTE_MODES.map(({ value, label, status, enabled }) => {
              const Icon = value === "drive" ? Car : value === "walk" ? Footprints : Bike;
              return (
                <button
                  key={value}
                  className={`mode-option${routeMode === value ? " active" : ""}${!enabled ? " disabled" : ""}`}
                  onClick={() => enabled && onModeChange(value)}
                  disabled={!enabled}
                  aria-pressed={routeMode === value}
                  type="button"
                >
                  <Icon size={18} />
                  <span className="mode-option-label">{label}</span>
                  <span className="mode-option-status">{status}</span>
                </button>
              );
            })}
          </div>
        </div>

        {/* ── Time Budget ── */}
        <div className="form-section">
          <label className="form-label">Time Budget</label>
          <div className="time-budget-options">
            {TIME_BUDGET_OPTIONS.map((mins) => {
              const h = mins / 60;
              const label = h < 1 ? `${mins}` : `${h}`;
              const unit = h < 1 ? "min" : h === 1 ? "hr" : "hrs";
              return (
                <button
                  key={mins}
                  className={`time-option${timeBudget === mins ? " active" : ""}`}
                  onClick={() => onTimeBudgetChange(mins)}
                  aria-pressed={timeBudget === mins}
                  type="button"
                >
                  <span className="time-option-value">{label}</span>
                  <span className="time-option-unit">{unit}</span>
                </button>
              );
            })}
          </div>
        </div>

        {/* ── Vibe Engine ── */}
        <div className="form-section">
          <div className="vibe-header">
            <label className="form-label">Vibe Engine</label>
            <span className="vibe-count">{vibes.length}/3</span>
          </div>
          <div className="vibe-grid">
            {visibleVibes.map(({ vibe, label, Icon }) => {
              const isActive = vibes.includes(vibe);
              const atLimit = vibes.length >= 3 && !isActive;
              return (
                <button
                  key={vibe}
                  className={`vibe-tile${isActive ? " active" : ""}${atLimit ? " locked" : ""}`}
                  onClick={() => !atLimit && onVibeToggle(vibe)}
                  disabled={atLimit}
                  aria-pressed={isActive}
                  type="button"
                  title={label}
                >
                  <Icon size={18} />
                  <span className="vibe-tile-label">{label}</span>
                </button>
              );
            })}
          </div>
          {compactVibeList && hiddenVibeCount > 0 && (
            <button
              className="vibe-expand-btn"
              type="button"
              aria-expanded={showAllVibes}
              onClick={() => setShowAllVibes((value) => !value)}
            >
              {showAllVibes ? (
                <>
                  <ChevronUp size={14} />
                  Show fewer vibes
                </>
              ) : (
                <>
                  <ChevronDown size={14} />
                  {hiddenVibeCount} more vibes
                </>
              )}
            </button>
          )}
        </div>

      </div>

      {/* Panel Footer — sticky Generate button */}
      <div className="panel-footer">
        {statusMessage && (phase === "idle" || phase === "failed") && (
          <div className={`message-banner${phase === "failed" ? " error" : ""}`}>
            {statusMessage}
          </div>
        )}

        <button
          className="btn-generate"
          onClick={onGenerate}
          disabled={!canGenerate || isGenerating}
          type="button"
          aria-label="Generate scenic loop route"
        >
          {isGenerating
            ? <><Loader2 size={20} style={{ animation: "spin 1s linear infinite" }} /><span className="command-text">GENERATING…</span></>
            : <span className="command-text">GENERATE ROUTE</span>
          }
        </button>
      </div>
    </aside>
  );
}

// ─── Loading Overlay ──────────────────────────────────────────────────────────
function LoadingOverlay({ phase, progressStep }: { phase: Phase; progressStep: number }) {
  if (phase !== "submitting" && phase !== "tracking") return null;

  return (
    <div className="loading-overlay" role="status" aria-live="polite" aria-label="Generating your route">
      <div className="loading-main">
        <div className="wayward-wordmark loading-brand">Wayward</div>

        <div className="loading-route-mark loading-blob-stage" aria-hidden="true">
          <div className="loading-blob-loader" />
        </div>

        <div className="loading-copy">
          <h2 className="loading-title">Mapping your scenic loop</h2>
          <p className="loading-subtitle">
            Comparing road shape, water, terrain, and your time budget.
          </p>
        </div>

        <div className="loading-steps">
          {LOADING_STEPS.map((step, i) => {
            const { Icon } = step;
            const isDone = i < progressStep;
            const isActive = i === progressStep;
            return (
              <div
                key={step.id}
                className={`loading-step${isActive ? " active" : ""}${isDone ? " done" : ""}`}
              >
                {isActive
                  ? <Loader2 size={18} className="loading-step-icon" style={{ animation: "spin 1s linear infinite" }} />
                  : <Icon size={18} className="loading-step-icon" />
                }
                <span className="loading-step-label">{step.label}</span>
              </div>
            );
          })}
        </div>
      </div>
    </div>
  );
}

function formatProfileName(profile?: string | null) {
  return profile?.split("_").map((w) => w[0].toUpperCase() + w.slice(1)).join(" ") ?? "Route";
}

function getSelectedRouteOption(route: RouteDetailResponse, selectedOptionId: string) {
  return route.routeOptions?.find((o) => o.routeId === selectedOptionId)
    ?? route.routeOptions?.[0];
}

function scenicFitLabel(score?: number | null) {
  if (!Number.isFinite(score ?? NaN)) return "Scenic match";
  const score10 = (score ?? 0) > 10 ? (score ?? 0) / 10 : (score ?? 0);
  if (score10 >= 7) return "Best scenic match";
  if (score10 >= 4) return "Good scenic match";
  return "Some scenic match";
}

function scenicFitBand(score?: number | null) {
  if (!Number.isFinite(score ?? NaN)) return "Scenic";
  const score10 = (score ?? 0) > 10 ? (score ?? 0) / 10 : (score ?? 0);
  if (score10 >= 7) return "Best";
  if (score10 >= 4) return "Good";
  return "Some";
}

type RouteSignal = {
  key: string;
  label: string;
  pct: number;
};

function getRouteSignals(option?: RouteOptionResponse | null): RouteSignal[] {
  const averages = option?.explanation?.componentAverages ?? {};
  const leading = option?.explanation?.leadingComponents ?? [];
  const orderedKeys = [...leading, ...USER_SIGNAL_ORDER];
  const seen = new Set<string>();

  return orderedKeys
    .filter((key) => {
      if (seen.has(key) || key === "poi" || !ROUTE_SIGNAL_LABELS[key]) return false;
      seen.add(key);
      return Number.isFinite(averages[key]);
    })
    .map((key) => ({
      key,
      label: ROUTE_SIGNAL_LABELS[key],
      pct: Math.round(Math.max(0, Math.min(1, averages[key] ?? 0)) * 100)
    }));
}

function getRouteBestFor(option?: RouteOptionResponse | null, limit = 3) {
  return getRouteSignals(option)
    .slice(0, limit)
    .map((signal) => signal.label);
}

function getRouteFeaturePhrases(option?: RouteOptionResponse | null, limit = 3) {
  const phraseByKey: Record<string, string> = {
    water: "waterfront views",
    greenery: "green cover",
    elevation: "rolling terrain",
    solitude: "quieter roads",
    curves: "curvier roads"
  };

  return getRouteSignals(option)
    .slice(0, limit)
    .map((signal) => phraseByKey[signal.key] ?? signal.label.toLowerCase());
}

function joinHumanList(items: string[]) {
  if (items.length <= 1) return items[0] ?? "scenic roads";
  if (items.length === 2) return `${items[0]} and ${items[1]}`;
  return `${items.slice(0, -1).join(", ")}, and ${items[items.length - 1]}`;
}

function buildHumanRouteReason(route: RouteDetailResponse, option?: RouteOptionResponse | null) {
  const backendSummary = option?.explanation?.summary?.trim();
  if (backendSummary) return backendSummary;

  const features = getRouteFeaturePhrases(option);
  const featureText = features.length > 0 ? ` It is best for ${joinHumanList(features)}.` : "";
  const duration = option?.estimatedDurationMinutes ?? route.estimatedDurationMinutes ?? 0;
  const shortestDuration = Math.min(
    ...((route.routeOptions ?? [])
      .map((routeOption) => routeOption.estimatedDurationMinutes)
      .filter((value): value is number => Number.isFinite(value)))
  );
  const isLonger = Number.isFinite(shortestDuration) && duration > shortestDuration + 5;
  const tradeoffText = isLonger ? " It is longer than the other routes." : "";
  const profile = option?.profile ?? route.routeOptions?.[0]?.profile ?? "";

  if (profile === "most_scenic") {
    return `This is the most scenic option nearby.${featureText}${tradeoffText}`;
  }

  if (profile === "balanced") {
    return `This route balances scenery with drive time.${featureText}`;
  }

  if (profile === "shorter") {
    return `This is the shortest scenic option nearby.${featureText} It keeps the drive tighter than the other routes.`;
  }

  return `This route gives you a scenic loop nearby.${featureText}`;
}

function SelectedRouteSummary({ route, option }: { route: RouteDetailResponse; option?: RouteOptionResponse | null }) {
  if (!option) return null;
  const distance = formatDistFromKm(option.totalDistanceKm ?? route.totalDistanceKm ?? 0);
  const duration = formatDur(option.estimatedDurationMinutes ?? route.estimatedDurationMinutes ?? 0);

  return (
    <div className="selected-route-summary">
      <div className="section-heading">Selected Route</div>
      <div className="selected-route-line">
        {formatProfileName(option.profile)} <span aria-hidden="true">·</span> {distance} km <span aria-hidden="true">·</span> {duration}
      </div>
    </div>
  );
}

function SelectedRouteChip({
  route,
  selectedOptionId,
  sheetState
}: {
  route: RouteDetailResponse;
  selectedOptionId: string;
  sheetState: BottomSheetState;
}) {
  const selectedOption = getSelectedRouteOption(route, selectedOptionId);
  if (!selectedOption || sheetState === "full" || sheetState === "peek") return null;

  return (
    <div className="mobile-route-chip" aria-live="polite">
      <span>{formatProfileName(selectedOption.profile)}</span>
      <span>{formatDistFromKm(selectedOption.totalDistanceKm ?? route.totalDistanceKm ?? 0)} km</span>
      <span>{formatDur(selectedOption.estimatedDurationMinutes ?? route.estimatedDurationMinutes ?? 0)}</span>
    </div>
  );
}

function MobileRouteDock({
  route,
  selectedOptionId,
  sheetState,
  onStartDrive,
  onViewRoutes,
  onPlanNewRoute
}: {
  route: RouteDetailResponse;
  selectedOptionId: string;
  sheetState: BottomSheetState;
  onStartDrive: () => void;
  onViewRoutes: () => void;
  onPlanNewRoute: () => void;
}) {
  const selectedOption = getSelectedRouteOption(route, selectedOptionId);
  if (!selectedOption || sheetState !== "peek") return null;

  return (
    <div className="mobile-route-dock" aria-live="polite">
      <button className="mobile-route-dock-summary" type="button" onClick={onViewRoutes}>
        <span>{formatProfileName(selectedOption.profile)}</span>
        <span>{formatDistFromKm(selectedOption.totalDistanceKm ?? route.totalDistanceKm ?? 0)} km</span>
        <span>{formatDur(selectedOption.estimatedDurationMinutes ?? route.estimatedDurationMinutes ?? 0)}</span>
      </button>
      <div className="mobile-route-dock-actions">
        <button className="mobile-route-dock-start" type="button" onClick={onStartDrive}>
          Start
        </button>
        <button className="mobile-route-dock-link" type="button" onClick={onViewRoutes}>
          View Routes
        </button>
        <button className="mobile-route-dock-icon" type="button" aria-label="Plan new route" onClick={onPlanNewRoute}>
          <RefreshCw size={13} />
        </button>
      </div>
    </div>
  );
}

function RouteSessionDock({
  route,
  selectedOptionId,
  mode,
  onStartDrive,
  onViewRoutes,
  onPlanNewRoute
}: {
  route: RouteDetailResponse;
  selectedOptionId: string;
  mode: "resultsMinimized" | "planningNewRoute";
  onStartDrive: () => void;
  onViewRoutes: () => void;
  onPlanNewRoute: () => void;
}) {
  const selectedOption = getSelectedRouteOption(route, selectedOptionId);
  if (!selectedOption) return null;

  return (
    <div className={`route-session-dock route-session-dock-${mode}`} aria-live="polite">
      <button className="route-session-dock-summary" type="button" onClick={onViewRoutes}>
        <span className="route-session-dock-kicker">
          {mode === "planningNewRoute" ? "Current Route" : "Selected Route"}
        </span>
        <span className="route-session-dock-route">
          {formatProfileName(selectedOption.profile)}
          <span aria-hidden="true"> · </span>
          {formatDistFromKm(selectedOption.totalDistanceKm ?? route.totalDistanceKm ?? 0)} km
          <span aria-hidden="true"> · </span>
          {formatDur(selectedOption.estimatedDurationMinutes ?? route.estimatedDurationMinutes ?? 0)}
        </span>
      </button>
      <div className="route-session-dock-actions">
        <button className="route-session-dock-start" type="button" onClick={onStartDrive}>
          Start
        </button>
        <button className="route-session-dock-secondary" type="button" onClick={onViewRoutes}>
          View Routes
        </button>
        <button className="route-session-dock-icon" type="button" aria-label="Plan new route" onClick={onPlanNewRoute}>
          <RefreshCw size={14} />
        </button>
      </div>
    </div>
  );
}

function RouteDetailsSignals({ option }: { option?: RouteOptionResponse | null }) {
  const [showDetails, setShowDetails] = useState(false);
  const [showAllSignals, setShowAllSignals] = useState(false);
  const signals = getRouteSignals(option).sort((a, b) => b.pct - a.pct);
  const visibleSignals = showAllSignals ? signals : signals.slice(0, 3);

  if (signals.length === 0) return null;

  if (!showDetails) {
    return (
      <button
        className="route-details-toggle route-details-toggle-primary"
        type="button"
        aria-expanded={false}
        onClick={() => setShowDetails(true)}
      >
        Show route details
      </button>
    );
  }

  return (
    <div>
      <div className="section-heading">Route Details</div>
      <div className="route-detail-signals">
        {visibleSignals.map((signal) => (
          <div key={signal.key} className="route-detail-row">
            <span className="route-detail-label">{signal.label}</span>
            <div className="route-detail-track" aria-hidden="true">
              <span className="route-detail-fill" style={{ width: `${signal.pct}%` }} />
            </div>
            <span className="route-detail-value">{signal.pct}%</span>
          </div>
        ))}
      </div>
      <div className="route-detail-actions">
        {signals.length > 3 && (
          <button
            className="route-details-toggle"
            type="button"
            aria-expanded={showAllSignals}
            onClick={() => setShowAllSignals((value) => !value)}
          >
            {showAllSignals ? "Show fewer signals" : "Show all signals"}
          </button>
        )}
        <button
          className="route-details-toggle"
          type="button"
          aria-expanded={showDetails}
          onClick={() => {
            setShowDetails(false);
            setShowAllSignals(false);
          }}
        >
          Hide route details
        </button>
      </div>
    </div>
  );
}

interface MobileResultsPanelProps {
  route: RouteDetailResponse;
  selectedOptionId: string;
  sheetState: BottomSheetState;
  onOptionSelect: (id: string) => void;
  onStartDrive: () => void;
  onPlanNewRoute: () => void;
  onExpand: () => void;
  onMinimize: () => void;
}

function MobileResultsPanel({
  route, selectedOptionId, sheetState,
  onOptionSelect, onStartDrive, onPlanNewRoute, onExpand, onMinimize
}: MobileResultsPanelProps) {
  const selectedOption = getSelectedRouteOption(route, selectedOptionId);
  const routeOptions = route.routeOptions?.length ? route.routeOptions : selectedOption ? [selectedOption] : [];
  const isPeek = sheetState === "peek";
  const isFull = sheetState === "full";
  const bestForTags = getRouteBestFor(selectedOption);
  const routeReason = buildHumanRouteReason(route, selectedOption);

  if (isPeek) {
    return (
      <div className="mobile-results-peek" onClick={onExpand} role="button" tabIndex={0} onKeyDown={(event) => {
        if (event.key === "Enter" || event.key === " ") onExpand();
      }}>
        <div className="mobile-peek-hint">
          {routeOptions.length} Scenic Route{routeOptions.length !== 1 ? "s" : ""} - Tap to explore
        </div>
        <div className="mobile-peek-routes">
          {routeOptions.map((opt) => (
            <button
              key={opt.routeId}
              className={`mobile-peek-route${selectedOptionId === opt.routeId ? " active" : ""}`}
              type="button"
              onClick={(event) => {
                event.stopPropagation();
                onOptionSelect(opt.routeId);
              }}
            >
              <span className="mobile-peek-name">{formatProfileName(opt.profile)}</span>
              <span className="mobile-peek-fit">
                {formatDistFromKm(opt.totalDistanceKm ?? 0)} km · {formatDur(opt.estimatedDurationMinutes ?? 0)}
              </span>
            </button>
          ))}
        </div>
      </div>
    );
  }

  return (
    <aside className={`results-panel mobile-results-panel mobile-results-${sheetState}`}>
      <div className="results-header">
        <div>
          <h2 className="results-title">ROUTE FOUND</h2>
          <p className="results-subtitle">
            {routeOptions.length} option{routeOptions.length !== 1 ? "s" : ""} generated
          </p>
        </div>
        <button
          className="results-minimize-btn"
          type="button"
          aria-label="Minimize route results"
          title="Minimize route results"
          onClick={onMinimize}
        >
          <ChevronDown size={18} />
        </button>
      </div>

      <div className="results-body">
        {routeOptions.length > 0 && (
          <div>
            <div className="section-heading">Route Options</div>
            <div className="route-options mobile-route-options">
              {routeOptions.map((opt: RouteOptionResponse) => (
                <button
                  key={opt.routeId}
                  className={`route-option-card${selectedOptionId === opt.routeId ? " active" : ""}`}
                  onClick={() => onOptionSelect(opt.routeId)}
                  type="button"
                >
                  <div className="route-option-header">
                    <div>
                      <div className="route-option-name">{formatProfileName(opt.profile)}</div>
                      <div className="route-option-meta">
                        {formatDistFromKm(opt.totalDistanceKm ?? 0)} km &bull; {formatDur(opt.estimatedDurationMinutes ?? 0)}
                      </div>
                    </div>
                  </div>
                </button>
              ))}
            </div>
          </div>
        )}

        <SelectedRouteSummary route={route} option={selectedOption} />

        {!isFull && (
          <button className="mobile-sheet-more" type="button" onClick={onExpand}>
            <ChevronUp size={13} />
            Swipe up - route details
          </button>
        )}

        {isFull && (
          <>
            <div className="route-summary-card">
              <div className="section-heading route-summary-heading">Why this route?</div>
              <p className="route-summary-text">{routeReason}</p>
            </div>

            {bestForTags.length > 0 && (
              <div>
                <div className="section-heading">Best for</div>
                <div className="route-highlights">
                  {bestForTags.map((tag) => (
                    <span key={tag} className="route-highlight-pill">{tag}</span>
                  ))}
                </div>
              </div>
            )}

            <RouteDetailsSignals option={selectedOption} />
          </>
        )}
      </div>

      <div className="results-footer">
        <button className="btn-generate" onClick={onStartDrive} type="button">
          <ArrowRight size={20} />
          <span className="command-text">Start Drive</span>
        </button>
        <button onClick={onPlanNewRoute} type="button" className="btn-new-route">
          <RefreshCw size={13} />
          Plan New Route
        </button>
      </div>
    </aside>
  );
}

// ─── Results Panel ────────────────────────────────────────────────────────────
interface ResultsPanelProps {
  route: RouteDetailResponse;
  selectedOptionId: string;
  onOptionSelect: (id: string) => void;
  onStartDrive: () => void;
  onPlanNewRoute: () => void;
  onMinimize?: () => void;
}

function ResultsPanel({
  route, selectedOptionId,
  onOptionSelect, onStartDrive, onPlanNewRoute, onMinimize
}: ResultsPanelProps) {
  const selectedOption = route.routeOptions?.find((o) => o.routeId === selectedOptionId)
    ?? route.routeOptions?.[0];
  const bestForTags = getRouteBestFor(selectedOption);
  const routeReason = buildHumanRouteReason(route, selectedOption);

  return (
    <aside className="results-panel">
      <div className="results-header">
        <div>
          <h2 className="results-title">ROUTE FOUND</h2>
          <p className="results-subtitle">
            {route.routeOptions?.length ?? 1} option{(route.routeOptions?.length ?? 1) !== 1 ? "s" : ""} generated
          </p>
        </div>
        {onMinimize && (
          <button
            className="results-minimize-btn"
            type="button"
            aria-label="Minimize route results"
            title="Minimize route results"
            onClick={onMinimize}
          >
            <ChevronDown size={18} />
          </button>
        )}
      </div>

      <div className="results-body">

        {/* ── Route Options ── */}
        {route.routeOptions && route.routeOptions.length > 0 && (
          <div>
            <div className="section-heading">Route Options</div>
            <div className="route-options" style={{ marginTop: "var(--space-3)" }}>
              {route.routeOptions.map((opt: RouteOptionResponse) => (
                <button
                  key={opt.routeId}
                  className={`route-option-card${selectedOptionId === opt.routeId ? " active" : ""}`}
                  onClick={() => onOptionSelect(opt.routeId)}
                  type="button"
                >
                  <div className="route-option-header">
                    <div>
                      <div className="route-option-name">
                        {opt.profile?.split("_").map((w) => w[0].toUpperCase() + w.slice(1)).join(" ") ?? "Route"}
                      </div>
                      <div className="route-option-meta">
                        {formatDistFromKm(opt.totalDistanceKm ?? 0)} km &bull; {formatDur(opt.estimatedDurationMinutes ?? 0)}
                      </div>
                    </div>
                  </div>
                </button>
              ))}
            </div>
          </div>
        )}

        <SelectedRouteSummary route={route} option={selectedOption} />

        {/* ── Route Summary ── */}
        <div className="route-summary-card">
          <div className="section-heading route-summary-heading">Why this route?</div>
          <p className="route-summary-text">{routeReason}</p>
        </div>

        {bestForTags.length > 0 && (
          <div>
            <div className="section-heading">Best for</div>
            <div className="route-highlights">
              {bestForTags.map((tag) => (
                <span key={tag} className="route-highlight-pill">{tag}</span>
              ))}
            </div>
          </div>
        )}

        <RouteDetailsSignals option={selectedOption} />

      </div>

      {/* Results Footer */}
      <div className="results-footer">
        <button className="btn-generate" onClick={onStartDrive} type="button">
          <ArrowRight size={20} />
          <span className="command-text">Start Drive</span>
        </button>
        <button
          onClick={onPlanNewRoute}
          type="button"
          className="btn-new-route"
        >
          <RefreshCw size={13} />
          Plan New Route
        </button>
      </div>
    </aside>
  );
}

// ─── Handoff Modal ────────────────────────────────────────────────────────────
function HandoffModal({
  route,
  routeMode,
  onClose,
  onNavigationOpen,
  onGpxExport
}: {
  route: RouteDetailResponse;
  routeMode: RouteMode;
  onClose: () => void;
  onNavigationOpen: (provider: "google" | "apple") => void;
  onGpxExport: () => void;
}) {
  const coords = route.geometry?.geometry?.coordinates ?? [];
  const gmapsUrl = buildGoogleMapsUrl(coords, routeMode);
  const appleMapsUrl = buildAppleMapsUrl(coords, routeMode);
  const routeName = route.routeOptions?.[0]?.profile
    ? route.routeOptions[0].profile.split("_").map((w) => w[0].toUpperCase() + w.slice(1)).join(" ")
    : "Wayward Route";
  const handoffOption = route.routeOptions?.[0];

  return (
    <div
      className="handoff-overlay"
      role="dialog"
      aria-modal="true"
      aria-label="Start Drive"
      onClick={(e) => { if (e.target === e.currentTarget) onClose(); }}
    >
      <div className="handoff-modal">

        {/* Header */}
        <div className="handoff-header">
          <div className="handoff-subtitle">Ready for the road?</div>
          <h2 className="handoff-title"><span className="display-squash">START YOUR DRIVE</span></h2>
        </div>

        {/* Body */}
        <div className="handoff-body">

          {/* Route name + meta */}
          <div>
            <div className="handoff-route-name">{routeName.toUpperCase()}</div>
            <div className="handoff-route-meta">
              {formatDistFromKm(route.totalDistanceKm ?? 0)} km &bull; {formatDur(route.estimatedDurationMinutes ?? 0)} &bull; {scenicFitLabel(handoffOption?.scenicScore ?? route.scenicScore)}
            </div>
          </div>

          {/* Stats row */}
          <div className="handoff-stats">
            <div className="handoff-stat">
              <span className="handoff-stat-label">Distance</span>
              <span className="handoff-stat-value">{formatDistFromKm(route.totalDistanceKm ?? 0)}<span style={{ fontSize: "12px", fontWeight: 400 }}> km</span></span>
            </div>
            <div className="handoff-stat">
              <span className="handoff-stat-label">Duration</span>
              <span className="handoff-stat-value">{formatDur(route.estimatedDurationMinutes ?? 0)}</span>
            </div>
            <div className="handoff-stat">
              <span className="handoff-stat-label">Scenic Match</span>
              <span className="handoff-stat-value fit-label">{scenicFitBand(handoffOption?.scenicScore ?? route.scenicScore)}</span>
            </div>
          </div>

          {/* Navigation options */}
          <div className="handoff-nav-options">
            <a
              href={gmapsUrl}
              target="_blank"
              rel="noopener noreferrer"
              className="nav-option-btn"
              onClick={() => onNavigationOpen("google")}
            >
              <Navigation size={20} />
              Open in Google Maps
            </a>
            <a
              href={appleMapsUrl}
              target="_blank"
              rel="noopener noreferrer"
              className="nav-option-btn"
              onClick={() => onNavigationOpen("apple")}
            >
              <MapIcon size={20} />
              Open in Apple Maps
            </a>
            <button
              className="nav-option-btn"
              onClick={() => {
                onGpxExport();
                exportGpx(route, routeName);
              }}
              type="button"
            >
              <Download size={20} />
              Export GPX Route Data
            </button>
          </div>
        </div>

        {/* Footer */}
        <div className="handoff-footer">
          <button className="btn-cancel" onClick={onClose} type="button">
            Back to route
          </button>
        </div>
      </div>
    </div>
  );
}

// ─── Failed Card ──────────────────────────────────────────────────────────────
function FailedCard({
  message,
  guidance,
  onReset,
  onTryVibe,
}: {
  message: string;
  guidance: FailureGuidance | null;
  onReset: () => void;
  onTryVibe: (vibe: string) => void;
}) {
  const fallbackVibes = guidance?.suggestedVibes?.slice(0, 3) ?? [];
  const suggestedActions = guidance?.suggestedActions?.slice(0, 4) ?? [];
  return (
    <div className="failed-card" role="alert">
      <div className="failed-icon">
        <AlertTriangle size={24} />
      </div>
      <h3 className="failed-title">
        {guidance?.failureCode === "vibe_unavailable" ? "Vibe Unavailable" : "Generation Failed"}
      </h3>
      <p className="failed-message">
        {message || "Route generation failed. Try a different starting point or vibe selection."}
      </p>
      {fallbackVibes.length > 0 && (
        <div className="failed-suggestions" aria-label="Suggested vibes">
          {fallbackVibes.map((vibe) => (
            <button key={vibe} className="failed-chip" type="button" onClick={() => onTryVibe(vibe)}>
              {vibeLabel(vibe)}
            </button>
          ))}
        </div>
      )}
      {suggestedActions.length > 0 && (
        <div className="failed-action-list">
          {suggestedActions.map((action) => (
            <span key={action} className="failed-action-item">{action}</span>
          ))}
        </div>
      )}
      <button className="btn-generate" onClick={onReset} type="button">
        <RefreshCw size={18} />
        <span className="command-text">Try Again</span>
      </button>
    </div>
  );
}

function vibeLabel(vibe: string): string {
  return VIBE_CONFIG.find((item) => item.vibe === vibe)?.label ?? vibe.replace(/_/g, " ");
}

function guidanceFromStatus(status: RouteJobStatusResponse): FailureGuidance | null {
  if (!status.failureCode && (!status.suggestedVibes || status.suggestedVibes.length === 0)) {
    return null;
  }
  return {
    failureCode: status.failureCode,
    suggestedVibes: status.suggestedVibes ?? [],
    suggestedActions: status.suggestedActions ?? [],
  };
}

// ─── Main Orchestrator ────────────────────────────────────────────────────────
export function RoutePlanner() {
  const [lat, setLat] = useState(49.2827);
  const [lng, setLng] = useState(-123.1207);
  const [locationQuery, setLocationQuery] = useState("");
  const [locationSuggestions, setLocationSuggestions] = useState<LocationSuggestion[]>([]);
  const [locationPending, setLocationPending] = useState(false);
  const [locationError, setLocationError] = useState<string | null>(null);
  const [showDropdown, setShowDropdown] = useState(false);
  const [routeMode, setRouteMode] = useState<RouteMode>("drive");
  const [timeBudget, setTimeBudget] = useState(60);
  const [vibes, setVibes] = useState<string[]>(["countryside"]);
  const [phase, setPhase] = useState<Phase>("idle");
  const [statusMessage, setStatusMessage] = useState("");
  const [failureGuidance, setFailureGuidance] = useState<FailureGuidance | null>(null);
  const [route, setRoute] = useState<RouteDetailResponse | null>(null);
  const [selectedOptionId, setSelectedOptionId] = useState("");
  const [showHandoff, setShowHandoff] = useState(false);
  const [progressStep, setProgressStep] = useState(0);
  const [sheetState, setSheetState] = useState<BottomSheetState>('mid');
  const [largeResultsOpen, setLargeResultsOpen] = useState(true);
  const [viewportMode, setViewportMode] = useState<'mobile' | 'tablet' | 'desktop'>('desktop');
  const [theme, setTheme] = useState<AppTheme>("day");
  const [themePreferenceReady, setThemePreferenceReady] = useState(false);
  const isMobile = viewportMode === 'mobile';
  const isTablet = viewportMode === 'tablet';
  const isDesktop = viewportMode === 'desktop';

  const geocodeTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null);
  const stopWsRef = useRef<null | (() => void)>(null);
  const pollTimerRef = useRef<ReturnType<typeof setInterval> | null>(null);
  const routeDetailsRef = useRef<Record<string, RouteDetailResponse>>({});
  const seqRef = useRef(0);
  const previousPhaseRef = useRef<Phase>("idle");
  const generationStartedAtRef = useRef<number | null>(null);

  useEffect(() => {
    try {
      const storedTheme = window.localStorage.getItem("wayward-theme");
      if (storedTheme === "day" || storedTheme === "night") {
        setTheme(storedTheme);
      }
    } catch {
      // localStorage can be unavailable in privacy-restricted contexts.
    } finally {
      setThemePreferenceReady(true);
    }
  }, []);

  useEffect(() => {
    if (!themePreferenceReady) return;
    try {
      window.localStorage.setItem("wayward-theme", theme);
    } catch {
      // Ignore storage failures; the toggle should still work for this session.
    }
  }, [theme, themePreferenceReady]);

  // Geocode as user types
  useEffect(() => {
    if (geocodeTimerRef.current) clearTimeout(geocodeTimerRef.current);
    if (!locationQuery || locationQuery.length < 3) {
      setLocationSuggestions([]);
      setShowDropdown(false);
      return;
    }
    setLocationPending(true);
    setLocationError(null);
    setShowDropdown(true);
    geocodeTimerRef.current = setTimeout(async () => {
      const seq = ++seqRef.current;
      try {
        const results = await searchLocations(locationQuery);
        if (seq !== seqRef.current) return;
        setLocationSuggestions(results);
        setLocationPending(false);
      } catch {
        if (seq !== seqRef.current) return;
        setLocationError("Search failed. Enter coordinates directly.");
        setLocationPending(false);
      }
    }, 400);
    return () => { if (geocodeTimerRef.current) clearTimeout(geocodeTimerRef.current); };
  }, [locationQuery]);

  const handleSuggestionSelect = useCallback((s: LocationSuggestion) => {
    setLat(s.lat);
    setLng(s.lng);
    setLocationQuery(s.displayName);
    setLocationSuggestions([]);
    setShowDropdown(false);
    trackAnalyticsEvent({
      eventName: "location_selected",
      routeMode,
      vibes,
      timeBudgetMinutes: timeBudget,
      regionKey: coarseAnalyticsRegionKey(s.lat, s.lng),
      metadata: { source: "search" }
    });
  }, [routeMode, vibes, timeBudget]);

  const handleGeolocate = useCallback(() => {
    if (!navigator.geolocation) {
      setStatusMessage("Geolocation not supported.");
      return;
    }
    trackAnalyticsEvent({
      eventName: "geolocate_clicked",
      routeMode,
      vibes,
      timeBudgetMinutes: timeBudget,
      regionKey: coarseAnalyticsRegionKey(lat, lng)
    });
    navigator.geolocation.getCurrentPosition(
      (pos) => {
        setLat(Number(pos.coords.latitude.toFixed(5)));
        setLng(Number(pos.coords.longitude.toFixed(5)));
        setLocationQuery(`${pos.coords.latitude.toFixed(4)}, ${pos.coords.longitude.toFixed(4)}`);
      },
      () => setStatusMessage("Could not detect location. Enter coordinates manually.")
    );
  }, [lat, lng, routeMode, vibes, timeBudget]);

  const handleVibeToggle = useCallback((vibe: string) => {
    setVibes((prev) =>
      prev.includes(vibe)
        ? prev.filter((v) => v !== vibe)
        : prev.length < 3
          ? [...prev, vibe]
          : prev
    );
  }, []);

  const resolveRouteDetail = useCallback(async (routeId: string): Promise<RouteDetailResponse> => {
    if (routeDetailsRef.current[routeId]) return routeDetailsRef.current[routeId];
    const detail = await getRoute(routeId);
    routeDetailsRef.current[routeId] = detail;
    return detail;
  }, []);

  const handleGenerate = useCallback(async () => {
    if (routeMode !== "drive" || vibes.length === 0) return;
    generationStartedAtRef.current = Date.now();
    trackAnalyticsEvent({
      eventName: "route_generate_clicked",
      routeMode,
      vibes,
      timeBudgetMinutes: timeBudget,
      regionKey: coarseAnalyticsRegionKey(lat, lng)
    });
    setPhase("submitting");
    setStatusMessage("");
    setFailureGuidance(null);
    setProgressStep(0);
    routeDetailsRef.current = {};
    setRoute(null);
    setLargeResultsOpen(true);

    const stepTimer = setInterval(() => {
      setProgressStep((p) => Math.min(p + 1, LOADING_STEPS.length - 1));
    }, 2200);

    try {
      const preferences = buildPreferenceVector(vibes);
      const submission = await submitRoute({
        userId: "00000000-0000-0000-0000-000000000000",
        lat,
        lng,
        routeMode,
        vibes: vibes as Vibe[],
        preferenceVector: preferences,
        timeBudgetMinutes: timeBudget
      });

      trackAnalyticsEvent({
        eventName: "route_generate_submitted",
        jobId: submission.jobId,
        routeMode,
        vibes,
        timeBudgetMinutes: timeBudget,
        regionKey: coarseAnalyticsRegionKey(lat, lng),
        status: submission.status
      });

      setPhase("tracking");

      let resolved = false;

      const stopWs = connectJobChannel(
        submission.jobId,
        submission.wsChannel,
        async (event) => {
          if (resolved) return;
          if (event.routeId) {
            resolved = true;
            stopWs();
            clearInterval(stepTimer);
            clearInterval(pollTimerRef.current!);
            setProgressStep(LOADING_STEPS.length);
            try {
              const detail = await resolveRouteDetail(event.routeId);
              setRoute(detail);
              setSelectedOptionId(event.routeId);
              setPhase("completed");
              const selectedOption = getSelectedRouteOption(detail, event.routeId);
              trackAnalyticsEvent({
                eventName: "route_generation_completed",
                jobId: submission.jobId,
                routeId: event.routeId,
                routeProfile: selectedOption?.profile,
                routeMode,
                vibes: detail.vibes?.length ? detail.vibes : vibes,
                timeBudgetMinutes: timeBudget,
                regionKey: coarseAnalyticsRegionKey(lat, lng),
                routeCount: detail.routeOptions?.length ?? 1,
                status: "completed",
                durationMs: generationStartedAtRef.current ? Date.now() - generationStartedAtRef.current : null,
                scenicScore: selectedOption?.scenicScore ?? detail.scenicScore
              });
            } catch (e) {
              const msg = e instanceof Error ? e.message : "Failed to load route detail.";
              setStatusMessage(msg);
              setPhase("failed");
            }
          }
        },
        (errMsg) => {
          console.warn("WS error:", errMsg);
        }
      );
      stopWsRef.current = stopWs;

      // Polling fallback
      const pollTimer = setInterval(async () => {
        if (resolved) { clearInterval(pollTimer); return; }
        try {
          const status = await getJobStatus(submission.jobId);
          const normalizedStatus = status.status?.toLowerCase();
          if (normalizedStatus === "completed") {
            clearInterval(pollTimer);
            if (!resolved) {
              resolved = true;
              stopWs();
              clearInterval(stepTimer);
              setProgressStep(LOADING_STEPS.length);
              const routeId = status.routeId ?? status.routeOptions?.[0]?.routeId;
              if (routeId) {
                const detail = await resolveRouteDetail(routeId);
                setRoute(detail);
                setSelectedOptionId(routeId);
                setPhase("completed");
                const selectedOption = getSelectedRouteOption(detail, routeId);
                trackAnalyticsEvent({
                  eventName: "route_generation_completed",
                  jobId: submission.jobId,
                  routeId,
                  routeProfile: selectedOption?.profile,
                  routeMode,
                  vibes: detail.vibes?.length ? detail.vibes : vibes,
                  timeBudgetMinutes: timeBudget,
                  regionKey: coarseAnalyticsRegionKey(lat, lng),
                  routeCount: detail.routeOptions?.length ?? 1,
                  status: "completed",
                  durationMs: generationStartedAtRef.current ? Date.now() - generationStartedAtRef.current : null,
                  scenicScore: selectedOption?.scenicScore ?? detail.scenicScore
                });
              }
            }
          } else if (normalizedStatus === "failed") {
            clearInterval(pollTimer);
            if (!resolved) {
              resolved = true;
              stopWs();
              clearInterval(stepTimer);
              setFailureGuidance(guidanceFromStatus(status));
              setStatusMessage(status.userMessage ?? status.reason ?? "Route generation failed.");
              setPhase("failed");
              trackAnalyticsEvent({
                eventName: "route_generation_failed",
                jobId: submission.jobId,
                routeMode,
                vibes,
                timeBudgetMinutes: timeBudget,
                regionKey: coarseAnalyticsRegionKey(lat, lng),
                status: status.failureCode ?? status.status,
                durationMs: generationStartedAtRef.current ? Date.now() - generationStartedAtRef.current : null,
                metadata: { reason: status.reason, failureCode: status.failureCode }
              });
              if (status.failureCode === "vibe_unavailable") {
                trackAnalyticsEvent({
                  eventName: "vibe_unavailable",
                  jobId: submission.jobId,
                  routeMode,
                  vibes,
                  timeBudgetMinutes: timeBudget,
                  regionKey: coarseAnalyticsRegionKey(lat, lng),
                  status: status.failureCode,
                  metadata: {
                    suggestedVibes: status.suggestedVibes,
                    suggestedActions: status.suggestedActions
                  }
                });
              }
            }
          } else {
            setStatusMessage("Processing…");
          }
        } catch { /* ignore poll errors */ }
      }, 3000);
      pollTimerRef.current = pollTimer;

    } catch (err: unknown) {
      clearInterval(stepTimer);
      const msg = err instanceof Error ? err.message : "An unexpected error occurred.";
      setStatusMessage(msg);
      setFailureGuidance(null);
      setPhase("failed");
      trackAnalyticsEvent({
        eventName: "route_generation_failed",
        routeMode,
        vibes,
        timeBudgetMinutes: timeBudget,
        regionKey: coarseAnalyticsRegionKey(lat, lng),
        status: "submit_failed",
        durationMs: generationStartedAtRef.current ? Date.now() - generationStartedAtRef.current : null,
        metadata: { message: msg.slice(0, 240) }
      });
    }
  }, [lat, lng, routeMode, vibes, timeBudget, resolveRouteDetail]);

  const handleReset = useCallback(() => {
    if (stopWsRef.current) { stopWsRef.current(); stopWsRef.current = null; }
    if (pollTimerRef.current) { clearInterval(pollTimerRef.current); pollTimerRef.current = null; }
    setPhase("idle");
    routeDetailsRef.current = {};
    setRoute(null);
    setStatusMessage("");
    setFailureGuidance(null);
    setSelectedOptionId("");
    setShowHandoff(false);
    setProgressStep(0);
    setLargeResultsOpen(true);
  }, []);

  const handlePlanNewRoute = useCallback(() => {
    const selectedOption = route ? getSelectedRouteOption(route, selectedOptionId) : null;
    trackAnalyticsEvent({
      eventName: "plan_new_route_clicked",
      jobId: route?.jobId,
      routeId: selectedOptionId || route?.routeId,
      routeProfile: selectedOption?.profile,
      routeMode,
      vibes,
      timeBudgetMinutes: timeBudget,
      regionKey: coarseAnalyticsRegionKey(lat, lng)
    });
    if (stopWsRef.current) { stopWsRef.current(); stopWsRef.current = null; }
    if (pollTimerRef.current) { clearInterval(pollTimerRef.current); pollTimerRef.current = null; }
    setPhase("idle");
    setStatusMessage("");
    setFailureGuidance(null);
    setShowHandoff(false);
    setProgressStep(0);
    setSheetState('mid');
    setLargeResultsOpen(true);
  }, [lat, lng, route, selectedOptionId, routeMode, vibes, timeBudget]);

  const handleTryVibe = useCallback((vibe: string) => {
    if (stopWsRef.current) { stopWsRef.current(); stopWsRef.current = null; }
    if (pollTimerRef.current) { clearInterval(pollTimerRef.current); pollTimerRef.current = null; }
    setVibes([vibe]);
    setPhase("idle");
    setStatusMessage("");
    setFailureGuidance(null);
    setRoute(null);
    setSelectedOptionId("");
    setLargeResultsOpen(true);
  }, []);

  const handleOptionSelect = useCallback(async (id: string) => {
    setSelectedOptionId(id);
    try {
      const detail = await resolveRouteDetail(id);
      setRoute(detail);
      const selectedOption = getSelectedRouteOption(detail, id);
      trackAnalyticsEvent({
        eventName: "route_option_selected",
        jobId: detail.jobId,
        routeId: id,
        routeProfile: selectedOption?.profile,
        routeMode: detail.routeMode,
        vibes: detail.vibes,
        timeBudgetMinutes: detail.timeBudgetMinutes,
        regionKey: coarseAnalyticsRegionKey(lat, lng),
        routeCount: detail.routeOptions?.length ?? 1,
        scenicScore: selectedOption?.scenicScore ?? detail.scenicScore
      });
    } catch (e) {
      const msg = e instanceof Error ? e.message : "Failed to load route detail.";
      setStatusMessage(msg);
    }
  }, [lat, lng, resolveRouteDetail]);

  const handleViewRoutes = useCallback(() => {
    if (!route) return;
    setPhase("completed");
    setLargeResultsOpen(true);
    setSheetState('mid');
    trackAnalyticsEvent({
      eventName: "route_results_viewed",
      jobId: route.jobId,
      routeId: selectedOptionId || route.routeId,
      routeProfile: getSelectedRouteOption(route, selectedOptionId)?.profile,
      routeMode,
      vibes: route.vibes,
      timeBudgetMinutes: route.timeBudgetMinutes,
      regionKey: coarseAnalyticsRegionKey(lat, lng)
    });
  }, [lat, lng, route, selectedOptionId, routeMode]);

  const handleThemeToggle = useCallback(() => {
    setTheme((current) => current === "day" ? "night" : "day");
    trackAnalyticsEvent({
      eventName: "theme_toggled",
      metadata: { from: theme, to: theme === "day" ? "night" : "day" }
    });
  }, [theme]);

  const handleStartDrive = useCallback(() => {
    if (route) {
      const selectedOption = getSelectedRouteOption(route, selectedOptionId);
      trackAnalyticsEvent({
        eventName: "start_drive_clicked",
        jobId: route.jobId,
        routeId: selectedOptionId || route.routeId,
        routeProfile: selectedOption?.profile,
        routeMode,
        vibes: route.vibes,
        timeBudgetMinutes: route.timeBudgetMinutes,
        regionKey: coarseAnalyticsRegionKey(lat, lng),
        routeCount: route.routeOptions?.length ?? 1,
        scenicScore: selectedOption?.scenicScore ?? route.scenicScore
      });
    }
    setShowHandoff(true);
  }, [lat, lng, route, selectedOptionId, routeMode]);

  const handleNavigationOpen = useCallback((provider: "google" | "apple") => {
    if (!route) return;
    const selectedOption = getSelectedRouteOption(route, selectedOptionId);
    trackAnalyticsEvent({
      eventName: "navigation_opened",
      jobId: route.jobId,
      routeId: selectedOptionId || route.routeId,
      routeProfile: selectedOption?.profile,
      routeMode,
      vibes: route.vibes,
      timeBudgetMinutes: route.timeBudgetMinutes,
      regionKey: coarseAnalyticsRegionKey(lat, lng),
      scenicScore: selectedOption?.scenicScore ?? route.scenicScore,
      metadata: { provider }
    });
  }, [lat, lng, route, selectedOptionId, routeMode]);

  const handleGpxExport = useCallback(() => {
    if (!route) return;
    const selectedOption = getSelectedRouteOption(route, selectedOptionId);
    trackAnalyticsEvent({
      eventName: "gpx_exported",
      jobId: route.jobId,
      routeId: selectedOptionId || route.routeId,
      routeProfile: selectedOption?.profile,
      routeMode,
      vibes: route.vibes,
      timeBudgetMinutes: route.timeBudgetMinutes,
      regionKey: coarseAnalyticsRegionKey(lat, lng),
      scenicScore: selectedOption?.scenicScore ?? route.scenicScore
    });
  }, [lat, lng, route, selectedOptionId, routeMode]);

  const handleResultsMinimize = useCallback(() => {
    if (route) {
      const selectedOption = getSelectedRouteOption(route, selectedOptionId);
      trackAnalyticsEvent({
        eventName: "route_results_minimized",
        jobId: route.jobId,
        routeId: selectedOptionId || route.routeId,
        routeProfile: selectedOption?.profile,
        routeMode,
        vibes: route.vibes,
        timeBudgetMinutes: route.timeBudgetMinutes,
        regionKey: coarseAnalyticsRegionKey(lat, lng)
      });
    }
    if (isMobile) {
      setSheetState('peek');
    } else {
      setLargeResultsOpen(false);
    }
  }, [lat, lng, route, selectedOptionId, routeMode, isMobile]);

  // Detect responsive viewport mode: phone sheet, tablet hybrid panel, desktop split panels.
  useEffect(() => {
    const checkViewportMode = () => {
      const width = window.innerWidth;
      if (width <= PHONE_MAX_WIDTH) {
        setViewportMode('mobile');
      } else if (width <= TABLET_MAX_WIDTH) {
        setViewportMode('tablet');
      } else {
        setViewportMode('desktop');
      }
    };
    checkViewportMode();
    window.addEventListener('resize', checkViewportMode);
    return () => window.removeEventListener('resize', checkViewportMode);
  }, []);

  // Auto-adjust sheet state based on phase for phone bottom sheets.
  useEffect(() => {
    const previousPhase = previousPhaseRef.current;
    if (phase === 'completed' && previousPhase !== 'completed') {
      setLargeResultsOpen(true);
    }

    if (!isMobile) {
      previousPhaseRef.current = phase;
      return;
    }

    if (phase === 'completed' && previousPhase !== 'completed') {
      setSheetState('peek');
    } else if (phase === 'failed') {
      setSheetState('mid');
    } else if (phase === 'idle') {
      setSheetState('mid');
    }

    previousPhaseRef.current = phase;
  }, [phase, isMobile]);

  const routeSessionState: RouteSessionState =
    phase === "submitting" || phase === "tracking"
      ? "generating"
      : phase === "completed"
        ? (isMobile && sheetState === "peek") || (!isMobile && !largeResultsOpen)
          ? "resultsMinimized"
          : "resultsOpen"
        : phase === "idle" && route
          ? "planningNewRoute"
          : "planning";

  return (
    <div className={`app-shell viewport-${viewportMode} theme-${theme} route-session-${routeSessionState}`}>
      {/* ── Top bar ── */}
      <AppHeader theme={theme} onThemeToggle={handleThemeToggle} />

      {/* ── Main layout ── */}
      <div className="app-main">
        {/* ── Desktop: Planner panel on left ── */}
        {isDesktop && (
          <PlannerPanel
            lat={lat}
            lng={lng}
            locationQuery={locationQuery}
            locationSuggestions={locationSuggestions}
            locationPending={locationPending}
            locationError={locationError}
            showDropdown={showDropdown}
            routeMode={routeMode}
            timeBudget={timeBudget}
            vibes={vibes}
            phase={phase}
            statusMessage={phase === "idle" || phase === "failed" ? statusMessage : ""}
            onLatChange={setLat}
            onLngChange={setLng}
            onLocationQueryChange={setLocationQuery}
            onSuggestionSelect={handleSuggestionSelect}
            onGeolocate={handleGeolocate}
            onModeChange={setRouteMode}
            onTimeBudgetChange={setTimeBudget}
            onVibeToggle={handleVibeToggle}
            onGenerate={handleGenerate}
          />
        )}

        {/* ── Map canvas — always rendered ── */}
        <div className="map-canvas">
          <RouteMap route={route} selectedRouteId={selectedOptionId} centerLat={lat} centerLng={lng} theme={theme} onRouteSelect={handleOptionSelect} />
        </div>

        {/* ── Desktop: Results panel on right ── */}
        {isDesktop && phase === "completed" && route && largeResultsOpen && (
          <ResultsPanel
            route={route}
            selectedOptionId={selectedOptionId}
            onOptionSelect={handleOptionSelect}
            onStartDrive={handleStartDrive}
            onPlanNewRoute={handlePlanNewRoute}
            onMinimize={handleResultsMinimize}
          />
        )}

        {/* ── Desktop: Failed card on right ── */}
        {isDesktop && phase === "failed" && (
          <div style={{
            width: "var(--sidebar-width)", flexShrink: 0,
            background: "var(--clr-bg)",
            borderLeft: "4px solid var(--clr-primary)",
            display: "flex", flexDirection: "column"
          }}>
            <FailedCard
              message={statusMessage}
              guidance={failureGuidance}
              onReset={handleReset}
              onTryVibe={handleTryVibe}
            />
          </div>
        )}

        {/* ── Tablet: Google Maps-inspired left sliding panel ── */}
        {isTablet && !(phase === "completed" && route && !largeResultsOpen) && (
          <aside className={`tablet-adaptive-panel tablet-panel-${phase === "completed" ? "results" : "planner"}`}>
            <div className="tablet-panel-handle" />
            <div className="tablet-panel-content">
              {phase !== "completed" && phase !== "failed" && (
                <PlannerPanel
                  lat={lat}
                  lng={lng}
                  locationQuery={locationQuery}
                  locationSuggestions={locationSuggestions}
                  locationPending={locationPending}
                  locationError={locationError}
                  showDropdown={showDropdown}
                  routeMode={routeMode}
                  timeBudget={timeBudget}
                  vibes={vibes}
                  phase={phase}
                  statusMessage={phase === "idle" ? statusMessage : ""}
                  onLatChange={setLat}
                  onLngChange={setLng}
                  onLocationQueryChange={setLocationQuery}
                  onSuggestionSelect={handleSuggestionSelect}
                  onGeolocate={handleGeolocate}
                  onModeChange={setRouteMode}
                  onTimeBudgetChange={setTimeBudget}
                  onVibeToggle={handleVibeToggle}
                  onGenerate={handleGenerate}
                />
              )}

              {phase === "completed" && route && (
                <ResultsPanel
                  route={route}
                  selectedOptionId={selectedOptionId}
                  onOptionSelect={handleOptionSelect}
                  onStartDrive={handleStartDrive}
                  onPlanNewRoute={handlePlanNewRoute}
                  onMinimize={handleResultsMinimize}
                />
              )}

              {phase === "failed" && (
                <FailedCard
                  message={statusMessage}
                  guidance={failureGuidance}
                  onReset={handleReset}
                  onTryVibe={handleTryVibe}
                />
              )}
            </div>
          </aside>
        )}

        {isMobile && phase !== "completed" && (
          <div className={`mobile-planner-surface mobile-planner-${phase}`}>
            {phase !== "failed" ? (
              <PlannerPanel
                lat={lat}
                lng={lng}
                locationQuery={locationQuery}
                locationSuggestions={locationSuggestions}
                locationPending={locationPending}
                locationError={locationError}
                showDropdown={showDropdown}
                routeMode={routeMode}
                timeBudget={timeBudget}
                vibes={vibes}
                phase={phase}
                statusMessage={phase === "idle" ? statusMessage : ""}
                compactVibeList
                onLatChange={setLat}
                onLngChange={setLng}
                onLocationQueryChange={setLocationQuery}
                onSuggestionSelect={handleSuggestionSelect}
                onGeolocate={handleGeolocate}
                onModeChange={setRouteMode}
                onTimeBudgetChange={setTimeBudget}
                onVibeToggle={handleVibeToggle}
                onGenerate={handleGenerate}
              />
            ) : (
              <FailedCard
                message={statusMessage}
                guidance={failureGuidance}
                onReset={handleReset}
                onTryVibe={handleTryVibe}
              />
            )}
          </div>
        )}
      </div>

      {!isMobile && route && (phase === "idle" || (phase === "completed" && !largeResultsOpen)) && (
        <RouteSessionDock
          route={route}
          selectedOptionId={selectedOptionId}
          mode={phase === "idle" ? "planningNewRoute" : "resultsMinimized"}
          onStartDrive={handleStartDrive}
          onViewRoutes={handleViewRoutes}
          onPlanNewRoute={handlePlanNewRoute}
        />
      )}

      {/* ── Mobile: Results bottom sheet ── */}
      {isMobile && phase === "completed" && route && (
        <>
          <SelectedRouteChip
            route={route}
            selectedOptionId={selectedOptionId}
            sheetState={sheetState}
          />
          <MobileRouteDock
            route={route}
            selectedOptionId={selectedOptionId}
            sheetState={sheetState}
            onStartDrive={handleStartDrive}
            onViewRoutes={handleViewRoutes}
            onPlanNewRoute={handlePlanNewRoute}
          />
        <BottomSheet
          state={sheetState}
          onStateChange={setSheetState}
          theme="results"
        >
          <MobileResultsPanel
            route={route}
            selectedOptionId={selectedOptionId}
            sheetState={sheetState}
            onOptionSelect={(id) => {
              void handleOptionSelect(id);
            }}
            onStartDrive={handleStartDrive}
            onPlanNewRoute={handlePlanNewRoute}
            onExpand={() => setSheetState((current) => current === "peek" ? "mid" : "full")}
            onMinimize={handleResultsMinimize}
          />
        </BottomSheet>
        </>
      )}

      {/* ── Loading overlay — full screen ── */}
      <LoadingOverlay phase={phase} progressStep={progressStep} />

      {/* ── Handoff modal ── */}
      {showHandoff && route && (
        <HandoffModal
          route={route}
          routeMode={routeMode}
          onClose={() => setShowHandoff(false)}
          onNavigationOpen={handleNavigationOpen}
          onGpxExport={handleGpxExport}
        />
      )}

    </div>
  );
}
