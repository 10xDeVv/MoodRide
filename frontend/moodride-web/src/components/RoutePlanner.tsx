"use client";

import { useState, useCallback, useRef, useEffect } from "react";
import {
  MapPin, Navigation, Bike, Footprints, Car,
  Clock, ChevronDown, ChevronUp,
  Map as MapIcon, Loader2,
  Waves, Trees, Mountain, Eye, Route,
  Sunset, Camera, Compass, Wind, Coffee, Zap, Moon, Sun,
  type LucideIcon
} from "lucide-react";
import { RouteMap } from "./RouteMap";
import { BottomSheet, type BottomSheetState } from "./BottomSheet";
import {
  FailedCard,
  HandoffModal,
  MobileResultsPanel,
  MobileRouteDock,
  ResultsPanel,
  RouteSessionDock,
  SelectedRouteChip,
  getSelectedRouteOption,
  guidanceFromStatus,
  type FailureGuidance
} from "./RoutePlannerResults";
import { coarseAnalyticsRegionKey, submitRoute, getJobStatus, getRoute, searchLocations, trackAnalyticsEvent } from "@/lib/api";
import { connectJobChannel } from "@/lib/ws";
import type {
  RouteDetailResponse,
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

// ─── Types ────────────────────────────────────────────────────────────────────
type Phase = "idle" | "submitting" | "tracking" | "completed" | "failed";
type AppTheme = "day" | "night";
type RouteSessionState = "planning" | "generating" | "resultsOpen" | "resultsMinimized" | "planningNewRoute";

const PHONE_MAX_WIDTH = 767;
const TABLET_MAX_WIDTH = 1440;

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
