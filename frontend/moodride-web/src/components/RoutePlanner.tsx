"use client";

import { useState, useCallback, useRef, useEffect } from "react";
import {
  MapPin, Navigation, Bike, Footprints, Car,
  Sparkles, RefreshCw, ChevronDown, ChevronUp,
  AlertTriangle, Download, Map as MapIcon, ArrowRight, Loader2,
  Waves, Trees, Mountain, Eye, Route,
  Sunset, Camera, Compass, Wind, Coffee, Zap, Moon, Sun,
  type LucideIcon
} from "lucide-react";
import { RouteMap } from "./RouteMap";
import { ScenicHighlightsPanel } from "./ScenicHighlightsPanel";
import { BottomSheet, type BottomSheetState } from "./BottomSheet";
import { submitRoute, getJobStatus, getRoute, submitRouteRating, searchLocations } from "@/lib/api";
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

const COMPONENT_LABELS: Record<string, string> = {
  water: "Water", greenery: "Greenery", elevation: "Elevation",
  solitude: "Solitude", curves: "Curves", poi: "Stops"
};

const COMPONENT_ORDER = ["water", "greenery", "elevation", "solitude", "curves", "poi"];

const TIME_BUDGET_OPTIONS = [30, 60, 90, 120] as const;

const ROUTE_MODES: Array<{ value: RouteMode; label: string; status: string; enabled: boolean }> = [
  { value: "drive", label: "Drive",  status: "Live",  enabled: true },
  { value: "walk",  label: "Walk",   status: "Soon",  enabled: false },
  { value: "bike",  label: "Bike",   status: "Soon",  enabled: false }
];

const LOADING_STEPS: Array<{ id: string; label: string; Icon: LucideIcon }> = [
  { id: "submit",     label: "Submitting",  Icon: MapPin },
  { id: "candidates", label: "Finding",     Icon: Compass },
  { id: "scoring",    label: "Scoring",     Icon: Sparkles },
  { id: "refining",   label: "Refining",    Icon: Route },
  { id: "finalising", label: "Finalising",  Icon: Zap }
];

const FEEDBACK_TAGS = [
  { id: "more_like_this", label: "More Like This" },
  { id: "loved_quiet", label: "Loved Quiet" },
  { id: "loved_curves", label: "Loved Curves" },
  { id: "loved_water", label: "Loved Water" },
  { id: "loved_greenery", label: "Loved Green" },
  { id: "loved_stops", label: "Loved Stops" },
  { id: "too_urban", label: "Too Urban" },
  { id: "too_long", label: "Too Long" },
  { id: "too_short", label: "Too Short" },
  { id: "too_boring", label: "Too Boring" },
  { id: "not_scenic", label: "Not Scenic" }
] as const;

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
  const gpx = `<?xml version="1.0" encoding="UTF-8"?>\n<gpx version="1.1" creator="MoodRide" xmlns="http://www.topografix.com/GPX/1/1">\n  <trk>\n    <name>${escapeXml(name)}</name>\n    <trkseg>\n${pts}\n    </trkseg>\n  </trk>\n</gpx>`;
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
          <filter id="moodride-glass-distortion" x="0%" y="0%" width="100%" height="100%">
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
        <h1 className="header-logo">MOODRIDE</h1>
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
  routeMode, timeBudget, vibes, phase, statusMessage,
  // eslint-disable-next-line @typescript-eslint/no-unused-vars
  onLatChange, onLngChange, onLocationQueryChange, onSuggestionSelect, onGeolocate,
  onModeChange, onTimeBudgetChange, onVibeToggle, onGenerate
}: PlannerPanelProps) {
  const canGenerate = routeMode === "drive" && vibes.length > 0 && phase === "idle";
  const isGenerating = phase === "submitting" || phase === "tracking";

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
            {VIBE_CONFIG.map(({ vibe, label, Icon }) => {
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
    <div className="loading-overlay" role="status" aria-live="polite" aria-label="Generating your scenic route">

      {/* Background decorative blobs */}
      <div style={{
        position: "absolute", top: "-80px", left: "-80px",
        width: "500px", height: "500px",
        background: "rgba(26,61,26,0.06)",
        borderRadius: "50%", filter: "blur(60px)", pointerEvents: "none"
      }} />
      <div style={{
        position: "absolute", top: "40%", right: "-120px",
        width: "600px", height: "600px",
        background: "rgba(226,231,106,0.12)",
        borderRadius: "50%", filter: "blur(80px)", pointerEvents: "none"
      }} />

      {/* Central content */}
      <div className="loading-main">
        {/* Brand above animation */}
        <div className="loading-brand"><span className="display-squash">MoodRide</span></div>

        {/* Compass animation */}
        <div className="loading-compass">
          <div className="loading-compass-ring" />
          <div className="loading-compass-inner" />

          {/* SVG path animation */}
          <svg
            style={{ width: "100%", height: "100%", position: "relative", zIndex: 1 }}
            viewBox="0 0 200 200"
            fill="none"
            aria-hidden="true"
          >
            {/* Compass rose (decorative) */}
            <g opacity="0.08" transform="translate(100,100)">
              <circle r="80" stroke="var(--loading-graphic, #032707)" strokeWidth="2" fill="none" />
              <path d="M0 -90 L8 0 L0 90 L-8 0 Z" fill="var(--loading-graphic, #032707)" />
              <path d="M-90 0 L0 -8 L90 0 L0 8 Z" fill="var(--loading-graphic, #032707)" />
            </g>
            {/* Topo rings */}
            <circle cx="100" cy="100" r="70" stroke="var(--loading-graphic-soft, rgba(3,39,7,0.07))" strokeWidth="1" />
            <circle cx="100" cy="100" r="50" stroke="var(--loading-graphic-soft, rgba(3,39,7,0.07))" strokeWidth="1" />
            <circle cx="100" cy="100" r="30" stroke="var(--loading-graphic-soft, rgba(3,39,7,0.07))" strokeWidth="1" />
            {/* Animated route path */}
            <path
              d="M40,160 C60,140 100,180 140,120 S180,40 100,60 S40,20 160,40"
              stroke="var(--loading-graphic, #032707)"
              strokeWidth="5"
              strokeLinecap="round"
              fill="none"
              style={{
                strokeDasharray: 1000,
                strokeDashoffset: 1000,
                animation: "drawPath 8s linear infinite"
              }}
            />
            {/* Moving dot */}
            <circle fill="var(--loading-graphic, #1a3020)" r="9">
              <animateMotion
                dur="8s"
                path="M40,160 C60,140 100,180 140,120 S180,40 100,60 S40,20 160,40"
                repeatCount="indefinite"
              />
            </circle>
          </svg>

          {/* Glassmorphic center */}
          <div style={{
            position: "absolute", inset: "20px",
            background: "rgba(255,255,255,0.2)",
            backdropFilter: "blur(12px)",
            borderRadius: "50%",
            zIndex: 0,
            border: "1px solid rgba(255,255,255,0.4)",
            boxShadow: "0 20px 40px rgba(3,39,7,0.1)"
          }} />
        </div>

        {/* Title + subtitle */}
        <div>
          <h2 className="loading-title"><span className="display-squash">Crafting your journey…</span></h2>
          <p className="loading-subtitle">
            Aligning peaks, valleys, and vibes to match your current frequency.
          </p>
        </div>

        {/* Progress steps */}
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
              {isDone
                ? <Icon size={20} className="loading-step-icon" />
                : isActive
                  ? <Loader2 size={20} className="loading-step-icon" style={{ animation: "spin 1s linear infinite" }} />
                  : <Icon size={20} className="loading-step-icon" />
              }
              <span className="loading-step-label">{step.label}</span>
            </div>
          );
        })}
        </div>
      </div>

      {/* Footer */}
      <div className="loading-footer">
        <div className="loading-footer-badge">
          <Zap size={14} />
          Secure Adventure Engine Active
        </div>
        <div className="loading-footer-copy">
          &copy; 2024 MoodRide Discovery. All rights reserved.
        </div>
      </div>

      {/* Keyframe injection */}
      <style>{`
        @keyframes drawPath {
          0% { stroke-dashoffset: 1000; opacity: 0; }
          10% { opacity: 1; }
          100% { stroke-dashoffset: 0; }
        }
        @keyframes spin { to { transform: rotate(360deg); } }
      `}</style>
    </div>
  );
}

// ─── Results Panel ────────────────────────────────────────────────────────────
interface ResultsPanelProps {
  route: RouteDetailResponse;
  selectedOptionId: string;
  userRating: number | null;
  ratingSubmitted: boolean;
  showDebug: boolean;
  onOptionSelect: (id: string) => void;
  onRatingSelect: (rating: number, feedbackTags: string[]) => void;
  onStartDrive: () => void;
  onReset: () => void;
  onToggleDebug: () => void;
}

function ResultsPanel({
  route, selectedOptionId, userRating, ratingSubmitted, showDebug,
  onOptionSelect, onRatingSelect, onStartDrive, onReset, onToggleDebug
}: ResultsPanelProps) {
  const [pendingRating, setPendingRating] = useState<number | null>(userRating);
  const [feedbackTags, setFeedbackTags] = useState<string[]>([]);
  const selectedOption = route.routeOptions?.find((o) => o.routeId === selectedOptionId)
    ?? route.routeOptions?.[0];

  const components = COMPONENT_ORDER
    .map((key) => {
      const avg = selectedOption?.explanation?.componentAverages?.[key];
      const lift = selectedOption?.explanation?.componentLifts?.[key];
      if (avg === undefined) return null;
      return { key, label: COMPONENT_LABELS[key] ?? key, pct: avg, lift: lift ?? 0 };
    })
    .filter(Boolean) as { key: string; label: string; pct: number; lift: number }[];

  return (
    <aside className="results-panel">
      <div className="results-header">
        <h2 className="results-title">ROUTE FOUND</h2>
        <p className="results-subtitle">
          {route.routeOptions?.length ?? 1} option{(route.routeOptions?.length ?? 1) !== 1 ? "s" : ""} generated
        </p>
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
                    {opt.scenicScore != null && (
                      <div className="route-option-score">
                        <span className="score-value">{opt.scenicScore.toFixed(1)}</span>
                        <span className="score-badge">Scenic</span>
                      </div>
                    )}
                  </div>
                  {opt.explanation?.leadingComponents && opt.explanation.leadingComponents.length > 0 && (
                    <div className="route-metrics" style={{ marginTop: "var(--space-3)" }}>
                      {opt.explanation.leadingComponents.slice(0, 3).map((r, i) => (
                        <div key={i} className="metric-item">
                          <span className="metric-label">{COMPONENT_LABELS[r] ?? r}</span>
                        </div>
                      ))}
                    </div>
                  )}
                </button>
              ))}
            </div>
          </div>
        )}

        {/* ── Key Metrics ── */}
        <div>
          <div className="section-heading">Journey Stats</div>
          <div className="handoff-stats" style={{ marginTop: "12px" }}>
            {[
              { label: "Distance", value: `${formatDistFromKm(route.totalDistanceKm ?? 0)} km` },
              { label: "Duration", value: formatDur(route.estimatedDurationMinutes ?? 0) },
              { label: "Scenic", value: `${(route.scenicScore ?? 0).toFixed(1)}/10` },
            ].map(({ label, value }) => (
              <div key={label} className="handoff-stat">
                <span className="handoff-stat-label">{label}</span>
                <span className="handoff-stat-value">{value}</span>
              </div>
            ))}
          </div>
        </div>

        {/* ── Scenic Breakdown ── */}
        {components.length > 0 && (
          <div>
            <div className="section-heading">Scenic Breakdown</div>
            <div className="score-breakdown" style={{ marginTop: "var(--space-3)" }}>
              {components.map(({ key, label, pct }) => {
                const pctVal = Math.round(pct * 100);
                return (
                  <div key={key} className="score-bar-row">
                    <span className="score-bar-label">{label}</span>
                    <div className="score-bar-track">
                      <div className="score-bar-fill" style={{ width: `${pctVal}%` }} />
                    </div>
                    <span className="score-bar-pct">{pctVal}%</span>
                  </div>
                );
              })}
            </div>
          </div>
        )}

        {/* ── Route Summary ── */}
        {selectedOption?.explanation?.summary && (
          <div className="route-summary-card">
            <div className="section-heading route-summary-heading">Why this route?</div>
            <p className="route-summary-text">
              &ldquo;{selectedOption.explanation.summary}&rdquo;
            </p>
          </div>
        )}

        {/* ── Scenic Highlights ── */}
        {selectedOption && (
          <div>
            <div className="section-heading">Scenic Highlights</div>
            <div style={{ marginTop: "var(--space-3)" }}>
              <ScenicHighlightsPanel route={route} selectedOptionId={selectedOption.routeId} />
            </div>
          </div>
        )}

        {/* ── Rate this route ── */}
        <div className="rating-card">
          <div className="section-heading rating-heading">Rate this route</div>
          <p className="rating-helper">
            {ratingSubmitted ? "Thanks for your feedback. Future routes will learn from it." : "Help tune future route suggestions"}
          </p>
          <div className="rating-buttons">
            {[1, 2, 3, 4, 5].map((n) => (
              <button
                key={n}
                onClick={() => setPendingRating(n)}
                disabled={ratingSubmitted}
                aria-label={`Rate ${n} star${n !== 1 ? "s" : ""}`}
                type="button"
                className={`rating-button${pendingRating !== null && n <= pendingRating ? " active" : ""}`}
              >
                {n}
              </button>
            ))}
          </div>
          {!ratingSubmitted && (
            <>
              <div className="feedback-tags" aria-label="Route feedback reasons">
                {FEEDBACK_TAGS.map((tag) => {
                  const active = feedbackTags.includes(tag.id);
                  return (
                    <button
                      key={tag.id}
                      type="button"
                      className={`feedback-tag${active ? " active" : ""}`}
                      aria-pressed={active}
                      onClick={() => setFeedbackTags((current) =>
                        active
                          ? current.filter((value) => value !== tag.id)
                          : current.length < 4
                            ? [...current, tag.id]
                            : current
                      )}
                    >
                      {tag.label}
                    </button>
                  );
                })}
              </div>
              <button
                type="button"
                className="feedback-submit"
                disabled={pendingRating == null}
                onClick={() => {
                  if (pendingRating == null) return;
                  onRatingSelect(pendingRating, feedbackTags);
                }}
              >
                Save Feedback
              </button>
            </>
          )}
        </div>

        {/* ── System details ── */}
        <div>
          <button
            onClick={onToggleDebug}
            type="button"
            className="debug-toggle"
          >
            {showDebug ? <ChevronUp size={13} /> : <ChevronDown size={13} />}
            System details
          </button>
          {showDebug && (
            <div style={{
              background: "var(--clr-bg-alt)",
              border: "2px solid var(--clr-border)",
              padding: "12px", marginTop: "8px"
            }}>
              {[
                ["Job ID",     route.jobId ?? "—"],
                ["Route ID",   route.routeId ?? "—"],
                ["Start",      `${route.startLat?.toFixed(4)}, ${route.startLng?.toFixed(4)}`],
                ["Profile",    selectedOption?.profile ?? "—"],
                ["Waypoints",  String(route.geometry?.geometry?.coordinates?.length ?? 0)]
              ].map(([k, v]) => (
                <div key={k} style={{
                  display: "flex", justifyContent: "space-between",
                  fontFamily: "var(--font-body)", fontSize: "11px",
                  padding: "3px 0", borderBottom: "1px solid var(--clr-border)"
                }}>
                  <span style={{ fontWeight: 700, color: "var(--clr-text-muted)", textTransform: "uppercase", letterSpacing: "0.06em" }}>{k}</span>
                  <span style={{ color: "var(--clr-primary)", fontFamily: "monospace" }}>{v}</span>
                </div>
              ))}
            </div>
          )}
        </div>

      </div>

      {/* Results Footer */}
      <div className="results-footer">
        <button className="btn-generate" onClick={onStartDrive} type="button">
          <ArrowRight size={20} />
          <span className="command-text">Start Drive</span>
        </button>
        <button
          onClick={onReset}
          type="button"
          className="btn-new-route"
        >
          <RefreshCw size={13} />
          New Route
        </button>
      </div>
    </aside>
  );
}

// ─── Handoff Modal ────────────────────────────────────────────────────────────
function HandoffModal({
  route,
  routeMode,
  onClose
}: {
  route: RouteDetailResponse;
  routeMode: RouteMode;
  onClose: () => void;
}) {
  const coords = route.geometry?.geometry?.coordinates ?? [];
  const gmapsUrl = buildGoogleMapsUrl(coords, routeMode);
  const appleMapsUrl = buildAppleMapsUrl(coords, routeMode);
  const routeName = route.routeOptions?.[0]?.profile
    ? route.routeOptions[0].profile.split("_").map((w) => w[0].toUpperCase() + w.slice(1)).join(" ")
    : "MoodRide Route";

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
            <div className="handoff-meta">
              {formatDistFromKm(route.totalDistanceKm ?? 0)} km &bull; {formatDur(route.estimatedDurationMinutes ?? 0)} &bull; Scenic {(route.scenicScore ?? 0).toFixed(1)}/10
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
              <span className="handoff-stat-label">Scenic Score</span>
              <span className="handoff-stat-value">{(route.scenicScore ?? 0).toFixed(1)}<span style={{ fontSize: "12px", fontWeight: 400 }}>/10</span></span>
            </div>
          </div>

          {/* Navigation options */}
          <div className="handoff-nav-options">
            <a
              href={gmapsUrl}
              target="_blank"
              rel="noopener noreferrer"
              className="nav-option-btn"
            >
              <Navigation size={20} />
              Open in Google Maps
            </a>
            <a
              href={appleMapsUrl}
              target="_blank"
              rel="noopener noreferrer"
              className="nav-option-btn"
            >
              <MapIcon size={20} />
              Open in Apple Maps
            </a>
            <button
              className="nav-option-btn"
              onClick={() => exportGpx(route, routeName)}
              type="button"
            >
              <Download size={20} />
              Export GPX Route Data
            </button>
          </div>

          {/* Summary quote */}
          {route.routeOptions?.[0]?.explanation?.summary && (
            <p style={{
              fontFamily: "var(--font-body)", fontSize: "12px",
              color: "var(--clr-text-muted)", lineHeight: 1.6,
              fontStyle: "italic",
              borderLeft: "3px solid var(--clr-lime)",
              paddingLeft: "16px"
            }}>
              &ldquo;{route.routeOptions[0].explanation!.summary}&rdquo;
            </p>
          )}
        </div>

        {/* Footer */}
        <div className="handoff-footer">
          <button className="btn-start-drive" type="button" onClick={() => window.open(gmapsUrl, "_blank")}>
            <Navigation size={20} />
            Sync Navigation
          </button>
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
  const [userRating, setUserRating] = useState<number | null>(null);
  const [ratingSubmitted, setRatingSubmitted] = useState(false);
  const [showHandoff, setShowHandoff] = useState(false);
  const [showDebug, setShowDebug] = useState(false);
  const [progressStep, setProgressStep] = useState(0);
  const [sheetState, setSheetState] = useState<BottomSheetState>('minimized');
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

  useEffect(() => {
    try {
      const storedTheme = window.localStorage.getItem("moodride-theme");
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
      window.localStorage.setItem("moodride-theme", theme);
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
  }, []);

  const handleGeolocate = useCallback(() => {
    if (!navigator.geolocation) {
      setStatusMessage("Geolocation not supported.");
      return;
    }
    navigator.geolocation.getCurrentPosition(
      (pos) => {
        setLat(Number(pos.coords.latitude.toFixed(5)));
        setLng(Number(pos.coords.longitude.toFixed(5)));
        setLocationQuery(`${pos.coords.latitude.toFixed(4)}, ${pos.coords.longitude.toFixed(4)}`);
      },
      () => setStatusMessage("Could not detect location. Enter coordinates manually.")
    );
  }, []);

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
    setPhase("submitting");
    setStatusMessage("");
    setFailureGuidance(null);
    setProgressStep(0);
    routeDetailsRef.current = {};
    setRoute(null);
    setRatingSubmitted(false);
    setUserRating(null);

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
    setUserRating(null);
    setRatingSubmitted(false);
    setShowHandoff(false);
    setProgressStep(0);
  }, []);

  const handleTryVibe = useCallback((vibe: string) => {
    if (stopWsRef.current) { stopWsRef.current(); stopWsRef.current = null; }
    if (pollTimerRef.current) { clearInterval(pollTimerRef.current); pollTimerRef.current = null; }
    setVibes([vibe]);
    setPhase("idle");
    setStatusMessage("");
    setFailureGuidance(null);
    setRoute(null);
    setSelectedOptionId("");
  }, []);

  const handleOptionSelect = useCallback(async (id: string) => {
    setSelectedOptionId(id);
    try {
      const detail = await resolveRouteDetail(id);
      setRoute(detail);
    } catch (e) {
      const msg = e instanceof Error ? e.message : "Failed to load route detail.";
      setStatusMessage(msg);
    }
  }, [resolveRouteDetail]);

  const handleRatingSelect = useCallback(async (rating: number, feedbackTags: string[]) => {
    if (ratingSubmitted || !route) return;
    setUserRating(rating);
    try {
      await submitRouteRating(route.routeId, rating, feedbackTags);
      setRatingSubmitted(true);
    } catch { /* ignore rating errors */ }
  }, [ratingSubmitted, route]);

  const handleThemeToggle = useCallback(() => {
    setTheme((current) => current === "day" ? "night" : "day");
  }, []);

  // Detect responsive viewport mode: phone sheet, tablet hybrid panel, desktop split panels.
  useEffect(() => {
    const checkViewportMode = () => {
      const width = window.innerWidth;
      if (width < 768) {
        setViewportMode('mobile');
      } else if (width <= 1366) {
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
    if (!isMobile) return;
    if (phase === 'completed' && route) {
      setSheetState('halfway');
    } else if (phase === 'submitting' || phase === 'tracking') {
      setSheetState('expanded');
    } else if (phase === 'failed') {
      setSheetState('halfway');
    } else if (phase === 'idle') {
      setSheetState('minimized');
    }
  }, [phase, route, isMobile]);

  return (
    <div className={`app-shell viewport-${viewportMode} theme-${theme}`}>
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
        {isDesktop && phase === "completed" && route && (
          <ResultsPanel
            route={route}
            selectedOptionId={selectedOptionId}
            userRating={userRating}
            ratingSubmitted={ratingSubmitted}
            showDebug={showDebug}
            onOptionSelect={handleOptionSelect}
            onRatingSelect={handleRatingSelect}
            onStartDrive={() => setShowHandoff(true)}
            onReset={handleReset}
            onToggleDebug={() => setShowDebug((v) => !v)}
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
        {isTablet && (
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
                  userRating={userRating}
                  ratingSubmitted={ratingSubmitted}
                  showDebug={showDebug}
                  onOptionSelect={handleOptionSelect}
                  onRatingSelect={handleRatingSelect}
                  onStartDrive={() => setShowHandoff(true)}
                  onReset={handleReset}
                  onToggleDebug={() => setShowDebug((v) => !v)}
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
      </div>

      {/* ── Mobile: Bottom Sheet ── */}
      {isMobile && (
        <BottomSheet
          state={sheetState}
          onStateChange={setSheetState}
          theme={phase === "completed" ? "results" : "planner"}
        >
          {/* Planner panel (when not completed) */}
          {phase !== "completed" && (
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

          {/* Results panel (when completed) */}
          {phase === "completed" && route && (
            <ResultsPanel
              route={route}
              selectedOptionId={selectedOptionId}
              userRating={userRating}
              ratingSubmitted={ratingSubmitted}
              showDebug={showDebug}
              onOptionSelect={handleOptionSelect}
              onRatingSelect={handleRatingSelect}
              onStartDrive={() => setShowHandoff(true)}
              onReset={() => {
                handleReset();
                setSheetState('minimized');
              }}
              onToggleDebug={() => setShowDebug((v) => !v)}
            />
          )}

          {/* Failed card (when failed) */}
          {phase === "failed" && (
            <FailedCard
              message={statusMessage}
              guidance={failureGuidance}
              onReset={() => {
                handleReset();
                setSheetState('minimized');
              }}
              onTryVibe={(vibe) => {
                handleTryVibe(vibe);
                setSheetState('expanded');
              }}
            />
          )}
        </BottomSheet>
      )}

      {/* ── Loading overlay — full screen ── */}
      <LoadingOverlay phase={phase} progressStep={progressStep} />

      {/* ── Handoff modal ── */}
      {showHandoff && route && (
        <HandoffModal
          route={route}
          routeMode={routeMode}
          onClose={() => setShowHandoff(false)}
        />
      )}

    </div>
  );
}
