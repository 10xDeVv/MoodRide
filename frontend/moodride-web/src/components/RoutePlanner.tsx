"use client";

import { useState, useCallback, useRef, useEffect, useLayoutEffect } from "react";
import {
  MapPin, Navigation, Bike, Footprints, Car,
  Clock, ChevronDown, ChevronUp,
  Map as MapIcon, Loader2,
  Waves, Trees, Mountain, Route,
  Compass, Wind, Zap, Moon, Sun,
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
  type FailureGuidance
} from "./RoutePlannerResults";
import {
  coarseAnalyticsRegionKey,
  getJobStatus,
  getPrimaryRoute,
  getRoute,
  searchLocations,
  submitRoute,
  trackAnalyticsEvent
} from "@/lib/api";
import { connectJobChannel } from "@/lib/ws";
import type {
  LocationSuggestion,
  PrimaryRouteResponse,
  RouteDetailResponse,
  RouteMode,
  Vibe
} from "@/lib/types";

// ─── Constants ────────────────────────────────────────────────────────────────

const VIBE_CONFIG: Array<{ vibe: Vibe; label: string; Icon: LucideIcon }> = [
  { vibe: "coastal",       label: "Coastal",    Icon: Waves },
  { vibe: "mountain",      label: "Mountain",   Icon: Mountain },
  { vibe: "countryside",   label: "Country",    Icon: Trees },
  { vibe: "riverside",     label: "Riverside",  Icon: Waves },
  { vibe: "nature_escape", label: "Nature",     Icon: Trees },
  { vibe: "open_roads",    label: "Open Road",  Icon: Route },
  { vibe: "adventure",     label: "Adventure",  Icon: Zap },
  { vibe: "relaxing",      label: "Relaxing",   Icon: Wind },
  { vibe: "winding_roads", label: "Winding",    Icon: Route },
];

const VIBE_PREFERENCE_DEFAULTS: Record<string, Record<string, number>> = {
  coastal:       { water: 0.9, greenery: 0.7, elevation: 0.3, solitude: 0.6, curves: 0.45, poi: 0.2 },
  mountain:      { water: 0.2, greenery: 0.55, elevation: 0.9, solitude: 0.7, curves: 0.8, poi: 0.2 },
  countryside:   { water: 0.4, greenery: 0.7, elevation: 0.45, solitude: 0.7, curves: 0.6, poi: 0.3 },
  riverside:     { water: 0.85, greenery: 0.75, elevation: 0.35, solitude: 0.65, curves: 0.45, poi: 0.25 },
  nature_escape: { water: 0.45, greenery: 0.9, elevation: 0.55, solitude: 0.9, curves: 0.45, poi: 0.15 },
  open_roads:    { water: 0.25, greenery: 0.45, elevation: 0.35, solitude: 0.85, curves: 0.35, poi: 0.1 },
  adventure:     { water: 0.4, greenery: 0.55, elevation: 0.9, solitude: 0.4, curves: 0.9, poi: 0.25 },
  relaxing:      { water: 0.35, greenery: 0.7, elevation: 0.25, solitude: 0.95, curves: 0.25, poi: 0.1 },
  winding_roads: { water: 0.35, greenery: 0.45, elevation: 0.65, solitude: 0.55, curves: 0.95, poi: 0.15 },
};

const TIME_BUDGET_OPTIONS = [30, 60, 90, 120] as const;
const JOB_STATUS_POLL_INTERVAL_MS = 1500;
const WS_FIRST_GRACE_MS = 2500;

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

type LifecycleSource = "ws" | "poll";
type RouteResponseKind = "primary" | "detail";

type LifecycleUpdate = {
  jobId: string;
  status?: string | null;
  routeId?: string | null;
  reason?: string | null;
  failureCode?: string | null;
  userMessage?: string | null;
  suggestedVibes?: string[];
  suggestedActions?: string[];
  stateRevision?: number;
  optionRevision?: number;
  optionCount?: number;
  optionsComplete?: boolean;
};

type LifecycleCursor = {
  stateRevision: number;
  statusRank: number;
  status: string;
  optionRevision: number;
  routeId: string | null;
};

type DisplayedRouteRevision = {
  epoch: number;
  jobId: string;
  routeId: string;
  stateRevision: number;
  optionRevision: number;
  optionsComplete: boolean;
  rich: boolean;
  source: LifecycleSource;
};

function routePaintRevisionKey(revision: DisplayedRouteRevision): string {
  return `${revision.epoch}:${revision.jobId}:${revision.routeId}:${revision.optionRevision}`;
}

function normalizeLifecycleStatus(status: string | null | undefined): string {
  const normalized = status?.trim().toLowerCase() ?? "";
  return normalized === "success" ? "completed" : normalized;
}

function lifecycleStatusRank(status: string): number {
  switch (status) {
    case "queued":
      return 0;
    case "processing":
      return 1;
    case "primary_ready":
      return 2;
    case "completed":
    case "failed":
    case "timeout":
      return 3;
    default:
      return 0;
  }
}

function isTerminalLifecycleStatus(status: string): boolean {
  return status === "completed" || status === "failed" || status === "timeout";
}

function isAbortError(error: unknown): boolean {
  return error instanceof DOMException
    ? error.name === "AbortError"
    : error instanceof Error && error.name === "AbortError";
}

function performanceNow(): number {
  return typeof performance !== "undefined" ? performance.now() : Date.now();
}

function adaptPrimaryRoute(primary: PrimaryRouteResponse): RouteDetailResponse {
  const routeOptions = primary.routeOptions.some((option) => option.routeId === primary.routeId)
    ? primary.routeOptions
    : [{
        profile: primary.profile,
        routeId: primary.routeId,
        routeUrl: primary.routeUrl,
        scenicScore: primary.scenicScore,
        totalDistanceKm: primary.totalDistanceKm,
        estimatedDurationMinutes: primary.estimatedDurationMinutes
      }, ...primary.routeOptions];

  return {
    routeId: primary.routeId,
    jobId: primary.jobId,
    routeUrl: primary.routeUrl,
    scenicScore: primary.scenicScore,
    scoreBreakdown: {},
    qualityTier: "",
    totalDistanceKm: primary.totalDistanceKm,
    estimatedDurationMinutes: primary.estimatedDurationMinutes,
    timeBudgetMinutes: primary.timeBudgetMinutes,
    routeMode: primary.routeMode,
    startLat: primary.startLat,
    startLng: primary.startLng,
    vibes: primary.vibes,
    geometry: primary.geometry,
    scenicHighlights: [],
    routeOptions: routeOptions.map((option) => ({
      ...option,
      scoreBreakdown: {},
      explanation: null
    })),
    optionRevision: primary.optionRevision,
    optionCount: primary.optionCount,
    optionsComplete: primary.optionsComplete,
    algorithmVersion: primary.algorithmVersion,
    beamCandidates: null,
    computationTimeMs: primary.computationTimeMs,
    userRating: null,
    ratedAt: null,
    createdAt: primary.createdAt,
    expiresAt: primary.expiresAt
  };
}

function mergeAcceptedRouteLifecycle(
  accepted: RouteDetailResponse,
  latest: RouteDetailResponse,
  optionCount?: number,
  optionsComplete?: boolean
): RouteDetailResponse {
  const acceptedOptions = new Map(accepted.routeOptions.map((option) => [option.routeId, option]));
  const mergedOptions = latest.routeOptions.map((option) => {
    const acceptedOption = acceptedOptions.get(option.routeId);
    acceptedOptions.delete(option.routeId);
    return acceptedOption
      ? {
          ...option,
          scoreBreakdown: acceptedOption.scoreBreakdown,
          explanation: acceptedOption.explanation
        }
      : option;
  });

  return {
    ...accepted,
    routeOptions: [...mergedOptions, ...acceptedOptions.values()],
    optionRevision: latest.optionRevision,
    optionCount: Math.max(accepted.optionCount, latest.optionCount, optionCount ?? 0),
    optionsComplete: Boolean(accepted.optionsComplete || optionsComplete || latest.optionsComplete)
  };
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
  locationResolving: boolean;
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
  onLocationSubmit: () => void;
  onSuggestionSelect: (s: LocationSuggestion) => void;
  onGeolocate: () => void;
  onModeChange: (m: RouteMode) => void;
  onTimeBudgetChange: (t: number) => void;
  onVibeToggle: (v: string) => void;
  onGenerate: () => void;
}

function PlannerPanel({
  // eslint-disable-next-line @typescript-eslint/no-unused-vars
  lat, lng, locationQuery, locationSuggestions, locationPending, locationResolving, locationError, showDropdown,
  routeMode, timeBudget, vibes, phase, statusMessage, compactVibeList = false,
  // eslint-disable-next-line @typescript-eslint/no-unused-vars
  onLatChange, onLngChange, onLocationQueryChange, onLocationSubmit, onSuggestionSelect, onGeolocate,
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
              placeholder="Search city, address, park, or paste coordinates"
              value={locationQuery}
              onChange={(e) => onLocationQueryChange(e.target.value)}
              onKeyDown={(event) => {
                if (event.key === "Enter") {
                  event.preventDefault();
                  onLocationSubmit();
                }
              }}
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
          <p className="location-helper">
            Press Enter to use the top result or paste coordinates like 49.2827, -123.1207.
          </p>
          <button className="use-location-btn" onClick={onGeolocate} type="button" disabled={locationResolving}>
            {locationResolving ? <Loader2 size={14} className="spin" /> : <Navigation size={14} />}
            {locationResolving ? "FINDING LOCATION…" : "USE MY LOCATION"}
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

function parseCoordinateInput(value: string): { lat: number; lng: number } | null {
  const match = value.trim().match(/^\s*(-?\d+(?:\.\d+)?)\s*[, ]\s*(-?\d+(?:\.\d+)?)\s*$/);
  if (!match) return null;
  const parsedLat = Number(match[1]);
  const parsedLng = Number(match[2]);
  if (!Number.isFinite(parsedLat) || !Number.isFinite(parsedLng)) return null;
  if (Math.abs(parsedLat) > 90 || Math.abs(parsedLng) > 180) return null;
  return { lat: parsedLat, lng: parsedLng };
}

function formatCoordinateLabel(nextLat: number, nextLng: number): string {
  return `${nextLat.toFixed(4)}, ${nextLng.toFixed(4)}`;
}

// ─── Main Orchestrator ────────────────────────────────────────────────────────
export function RoutePlanner() {
  const [lat, setLat] = useState(49.2827);
  const [lng, setLng] = useState(-123.1207);
  const [locationQuery, setLocationQuery] = useState("");
  const [locationSuggestions, setLocationSuggestions] = useState<LocationSuggestion[]>([]);
  const [locationPending, setLocationPending] = useState(false);
  const [locationResolving, setLocationResolving] = useState(false);
  const [locationError, setLocationError] = useState<string | null>(null);
  const [showDropdown, setShowDropdown] = useState(false);
  const [routeMode, setRouteMode] = useState<RouteMode>("drive");
  const [timeBudget, setTimeBudget] = useState(60);
  const [vibes, setVibes] = useState<string[]>(["countryside"]);
  const [phase, setPhase] = useState<Phase>("idle");
  const [statusMessage, setStatusMessage] = useState("");
  const [failureGuidance, setFailureGuidance] = useState<FailureGuidance | null>(null);
  const [route, setRoute] = useState<RouteDetailResponse | null>(null);
  const [displayedRevision, setDisplayedRevision] = useState<DisplayedRouteRevision | null>(null);
  const [selectedOptionId, setSelectedOptionId] = useState("");
  const [pendingOptionId, setPendingOptionId] = useState("");
  const [detailRetryOptionId, setDetailRetryOptionId] = useState("");
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

  const geocodeTimerRef = useRef<number | null>(null);
  const stopWsRef = useRef<null | (() => void)>(null);
  const pollTimerRef = useRef<number | null>(null);
  const wsGraceTimerRef = useRef<number | null>(null);
  const progressTimerRef = useRef<number | null>(null);
  const activeAbortRef = useRef<AbortController | null>(null);
  const activeEpochRef = useRef(0);
  const activeJobIdRef = useRef<string | null>(null);
  const terminalAcceptedRef = useRef(false);
  const lifecycleCursorRef = useRef<LifecycleCursor>({
    stateRevision: -1,
    statusRank: -1,
    status: "",
    optionRevision: -1,
    routeId: null
  });
  const displayedRevisionRef = useRef<DisplayedRouteRevision | null>(null);
  const routeRef = useRef<RouteDetailResponse | null>(null);
  const selectedOptionIdRef = useRef("");
  const selectionRequestRef = useRef(0);
  const pendingOptionIdRef = useRef("");
  const routeResponseCacheRef = useRef<Map<string, RouteDetailResponse>>(new Map());
  const routeRequestFlightsRef = useRef<Map<string, Promise<RouteDetailResponse>>>(new Map());
  const primaryFallbacksRef = useRef<Map<string, RouteDetailResponse>>(new Map());
  const richDetailsRef = useRef<Map<string, RouteDetailResponse>>(new Map());
  const milestoneKeysRef = useRef<Set<string>>(new Set());
  const paintedRouteRevisionKeysRef = useRef<Set<string>>(new Set());
  const routePaintWaitersRef = useRef<Map<string, Set<(painted: boolean) => void>>>(new Map());
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
    clearTimeout(geocodeTimerRef.current ?? undefined);
    if (!locationQuery || locationQuery.trim().length < 2) {
      setLocationSuggestions([]);
      setShowDropdown(false);
      setLocationPending(false);
      return;
    }
    setLocationPending(true);
    setLocationError(null);
    setShowDropdown(true);
    geocodeTimerRef.current = window.setTimeout(async () => {
      const seq = ++seqRef.current;
      try {
        const results = await searchLocations(locationQuery);
        if (seq !== seqRef.current) return;
        setLocationSuggestions(results);
        setLocationPending(false);
      } catch {
        if (seq !== seqRef.current) return;
        setLocationError("Search failed. Try coordinates or a nearby landmark.");
        setLocationPending(false);
      }
    }, 300);
    return () => { clearTimeout(geocodeTimerRef.current ?? undefined); };
  }, [locationQuery]);

  const handleLocationQueryChange = useCallback((value: string) => {
    setLocationQuery(value);
    setLocationError(null);
    setStatusMessage("");
  }, []);

  const applyResolvedLocation = useCallback((nextLat: number, nextLng: number, label: string, source: "search" | "coordinates" | "geolocation") => {
    setLat(Number(nextLat.toFixed(5)));
    setLng(Number(nextLng.toFixed(5)));
    setLocationQuery(label);
    setLocationSuggestions([]);
    setShowDropdown(false);
    setLocationPending(false);
    setLocationResolving(false);
    setLocationError(null);
    setStatusMessage(source === "geolocation" ? "Location set from your device." : "");
    trackAnalyticsEvent({
      eventName: "location_selected",
      routeMode,
      vibes,
      timeBudgetMinutes: timeBudget,
      regionKey: coarseAnalyticsRegionKey(nextLat, nextLng),
      metadata: { source }
    });
  }, [routeMode, vibes, timeBudget]);

  const handleSuggestionSelect = useCallback((s: LocationSuggestion) => {
    applyResolvedLocation(s.lat, s.lng, s.displayName, "search");
  }, [applyResolvedLocation]);

  const handleLocationSubmit = useCallback(async () => {
    const trimmed = locationQuery.trim();
    if (!trimmed) {
      setLocationError("Enter a city, address, landmark, or coordinates.");
      setShowDropdown(true);
      return;
    }

    const coordinates = parseCoordinateInput(trimmed);
    if (coordinates) {
      applyResolvedLocation(coordinates.lat, coordinates.lng, formatCoordinateLabel(coordinates.lat, coordinates.lng), "coordinates");
      return;
    }

    if (locationSuggestions.length > 0) {
      handleSuggestionSelect(locationSuggestions[0]);
      return;
    }

    setLocationPending(true);
    setShowDropdown(true);
    setLocationError(null);
    try {
      const [firstResult] = await searchLocations(trimmed, 1);
      if (!firstResult) {
        setLocationError("No matching place found. Try a nearby city, landmark, or coordinates.");
        return;
      }
      applyResolvedLocation(firstResult.lat, firstResult.lng, firstResult.displayName, "search");
    } catch {
      setLocationError("Search failed. Try coordinates or a nearby landmark.");
    } finally {
      setLocationPending(false);
    }
  }, [applyResolvedLocation, handleSuggestionSelect, locationQuery, locationSuggestions]);

  const handleGeolocate = useCallback(() => {
    if (!navigator.geolocation) {
      setLocationError("Geolocation is not supported by this browser.");
      setShowDropdown(true);
      return;
    }
    setLocationResolving(true);
    setLocationPending(false);
    setLocationError(null);
    setStatusMessage("Detecting your location…");
    trackAnalyticsEvent({
      eventName: "geolocate_clicked",
      routeMode,
      vibes,
      timeBudgetMinutes: timeBudget,
      regionKey: coarseAnalyticsRegionKey(lat, lng)
    });
    navigator.geolocation.getCurrentPosition(
      (pos) => {
        const nextLat = Number(pos.coords.latitude.toFixed(5));
        const nextLng = Number(pos.coords.longitude.toFixed(5));
        applyResolvedLocation(nextLat, nextLng, formatCoordinateLabel(nextLat, nextLng), "geolocation");
      },
      () => {
        setLocationResolving(false);
        setLocationError("Could not detect your location. Allow browser location access or enter a place manually.");
        setShowDropdown(true);
        setStatusMessage("");
      },
      { enableHighAccuracy: true, timeout: 10000, maximumAge: 60000 }
    );
  }, [applyResolvedLocation, lat, lng, routeMode, vibes, timeBudget]);

  const handleVibeToggle = useCallback((vibe: string) => {
    setVibes((prev) =>
      prev.includes(vibe)
        ? prev.filter((v) => v !== vibe)
        : prev.length < 3
          ? [...prev, vibe]
          : prev
    );
  }, []);

  const closeActiveChannels = useCallback(() => {
    if (stopWsRef.current) {
      stopWsRef.current();
      stopWsRef.current = null;
    }
    if (pollTimerRef.current !== null) {
      window.clearTimeout(pollTimerRef.current);
      pollTimerRef.current = null;
    }
    if (wsGraceTimerRef.current !== null) {
      window.clearTimeout(wsGraceTimerRef.current);
      wsGraceTimerRef.current = null;
    }
  }, []);

  const stopProgress = useCallback(() => {
    if (progressTimerRef.current !== null) {
      window.clearInterval(progressTimerRef.current);
      progressTimerRef.current = null;
    }
  }, []);

  const invalidateActiveJob = useCallback(() => {
    activeEpochRef.current += 1;
    selectionRequestRef.current += 1;
    pendingOptionIdRef.current = "";
    primaryFallbacksRef.current.clear();
    activeAbortRef.current?.abort();
    for (const waiters of routePaintWaitersRef.current.values()) {
      for (const settle of waiters) settle(false);
    }
    routePaintWaitersRef.current.clear();
    paintedRouteRevisionKeysRef.current.clear();
    activeAbortRef.current = null;
    activeJobIdRef.current = null;
    terminalAcceptedRef.current = false;
    lifecycleCursorRef.current = {
      stateRevision: -1,
      statusRank: -1,
      status: "",
      optionRevision: -1,
      routeId: null
    };
    closeActiveChannels();
    stopProgress();
    return activeEpochRef.current;
  }, [closeActiveChannels, stopProgress]);

  const setSelectedRouteId = useCallback((routeId: string) => {
    selectedOptionIdRef.current = routeId;
    setSelectedOptionId(routeId);
  }, []);

  const resolveRouteResponse = useCallback((
    jobId: string,
    routeId: string,
    optionRevision: number,
    kind: RouteResponseKind,
    requireComplete: boolean,
    signal: AbortSignal
  ) => {
    const requestEpoch = activeEpochRef.current;
    const completenessKey = requireComplete ? "complete" : "partial";
    const requestKey = kind === "primary"
      ? `${requestEpoch}:${jobId}:${routeId}:primary`
      : `${requestEpoch}:${jobId}:${routeId}:${optionRevision}:${completenessKey}`;

    const validateResponse = (detail: RouteDetailResponse) => {
      if (detail.jobId !== jobId || detail.routeId !== routeId) {
        throw new Error("Route response did not match the active job.");
      }
      if (detail.optionRevision < optionRevision) {
        throw new Error("Route response was older than the accepted lifecycle revision.");
      }
      if (requireComplete && !detail.optionsComplete) {
        throw new Error("Complete route details are not available yet.");
      }
      return detail;
    };

    const cached = routeResponseCacheRef.current.get(requestKey);
    if (cached) {
      if (
        cached.jobId === jobId
        && cached.routeId === routeId
        && cached.optionRevision >= optionRevision
        && (!requireComplete || cached.optionsComplete)
      ) {
        return Promise.resolve(cached);
      }
      routeResponseCacheRef.current.delete(requestKey);
    }

    const inFlight = routeRequestFlightsRef.current.get(requestKey);
    if (inFlight) return inFlight.then(validateResponse);

    const request = (kind === "primary"
      ? getPrimaryRoute(routeId, signal).then(adaptPrimaryRoute)
      : getRoute(routeId, signal)
    ).then((detail) => {
      if (detail.jobId !== jobId || detail.routeId !== routeId) {
        throw new Error("Route response did not match the active job.");
      }

      if (
        requestEpoch === activeEpochRef.current
        && jobId === activeJobIdRef.current
      ) {
        if (kind === "primary") {
          const previousPrimary = primaryFallbacksRef.current.get(requestKey);
          if (!previousPrimary || detail.optionRevision >= previousPrimary.optionRevision) {
            primaryFallbacksRef.current.set(requestKey, detail);
          }
        }
        if (
          kind === "primary"
          || (
            detail.optionRevision >= optionRevision
            && (!requireComplete || detail.optionsComplete)
          )
        ) {
          routeResponseCacheRef.current.set(requestKey, detail);
        }
        if (kind === "detail") {
          const previous = richDetailsRef.current.get(routeId);
          if (
            !previous
            || detail.optionRevision > previous.optionRevision
            || (detail.optionRevision === previous.optionRevision && detail.optionsComplete && !previous.optionsComplete)
          ) {
            richDetailsRef.current.set(routeId, detail);
          }
        }
      }
      return detail;
    });


    const trackedRequest = request.finally(() => {
      if (routeRequestFlightsRef.current.get(requestKey) === trackedRequest) {
        routeRequestFlightsRef.current.delete(requestKey);
      }
    });
    routeRequestFlightsRef.current.set(requestKey, trackedRequest);
    return trackedRequest.then(validateResponse);
  }, []);

  const applyDisplayedRoute = useCallback((
    detail: RouteDetailResponse,
    candidate: DisplayedRouteRevision,
    acceptedPendingOptionId?: string
  ) => {
    if (
      candidate.epoch !== activeEpochRef.current
      || candidate.jobId !== activeJobIdRef.current
      || detail.jobId !== candidate.jobId
      || detail.routeId !== candidate.routeId
      || detail.optionRevision < candidate.optionRevision
      || (
        acceptedPendingOptionId !== undefined
        && pendingOptionIdRef.current !== acceptedPendingOptionId
      )
    ) {
      return false;
    }

    const current = displayedRevisionRef.current;
    if (current && current.jobId === candidate.jobId) {
      if (candidate.stateRevision < current.stateRevision) return false;
      if (candidate.optionRevision < current.optionRevision) return false;
      if (current.optionsComplete && !candidate.optionsComplete) return false;
      if (current.rich && !candidate.rich) return false;
      if (
        acceptedPendingOptionId === undefined
        && candidate.stateRevision === current.stateRevision
        && candidate.optionRevision === current.optionRevision
        && candidate.routeId === current.routeId
        && candidate.optionsComplete === current.optionsComplete
        && candidate.rich === current.rich
      ) {
        return false;
      }
      if (
        candidate.stateRevision === current.stateRevision
        && candidate.optionRevision === current.optionRevision
        && ((current.optionsComplete && !candidate.optionsComplete) || (current.rich && !candidate.rich))
      ) {
        return false;
      }
    }

    routeRef.current = detail;
    displayedRevisionRef.current = candidate;
    selectedOptionIdRef.current = candidate.routeId;
    const nextPaintKey = routePaintRevisionKey(candidate);
    for (const [paintKey, waiters] of routePaintWaitersRef.current) {
      if (paintKey === nextPaintKey) continue;
      routePaintWaitersRef.current.delete(paintKey);
      for (const settle of waiters) settle(false);
    }
    setRoute(detail);
    setDisplayedRevision(candidate);
    setSelectedOptionId(candidate.routeId);
    if (acceptedPendingOptionId !== undefined) {
      pendingOptionIdRef.current = "";
      setPendingOptionId("");
    }
    return true;
  }, []);

  const waitForRouteMapPainted = useCallback((
    revision: DisplayedRouteRevision,
    signal: AbortSignal
  ): Promise<boolean> => {
    if (signal.aborted) {
      return Promise.reject(signal.reason ?? new DOMException("Route request was aborted.", "AbortError"));
    }

    const paintKey = routePaintRevisionKey(revision);
    const currentRevision = displayedRevisionRef.current;
    if (
      revision.epoch !== activeEpochRef.current
      || revision.jobId !== activeJobIdRef.current
      || !currentRevision
      || routePaintRevisionKey(currentRevision) !== paintKey
    ) {
      return Promise.resolve(false);
    }
    if (paintedRouteRevisionKeysRef.current.has(paintKey)) {
      return Promise.resolve(true);
    }

    return new Promise<boolean>((resolve, reject) => {
      const waiters = routePaintWaitersRef.current.get(paintKey) ?? new Set<(painted: boolean) => void>();
      let settled = false;
      const cleanup = () => {
        signal.removeEventListener("abort", handleAbort);
        waiters.delete(settle);
        if (waiters.size === 0 && routePaintWaitersRef.current.get(paintKey) === waiters) {
          routePaintWaitersRef.current.delete(paintKey);
        }
      };
      const settle = (painted: boolean) => {
        if (settled) return;
        settled = true;
        cleanup();
        resolve(painted);
      };
      const handleAbort = () => {
        if (settled) return;
        settled = true;
        cleanup();
        reject(signal.reason ?? new DOMException("Route request was aborted.", "AbortError"));
      };

      waiters.add(settle);
      routePaintWaitersRef.current.set(paintKey, waiters);
      signal.addEventListener("abort", handleAbort, { once: true });
      if (paintedRouteRevisionKeysRef.current.has(paintKey)) settle(true);
    });
  }, []);

  const emitMilestone = useCallback((
    eventName: "route_generation_primary_ready" | "route_results_committed" | "route_map_painted",
    detail: RouteDetailResponse,
    revision: DisplayedRouteRevision
  ) => {
    const key = `${revision.jobId}:${revision.optionRevision}:${eventName}`;
    if (milestoneKeysRef.current.has(key)) return;
    milestoneKeysRef.current.add(key);

    const selectedOption = getSelectedRouteOption(detail, revision.routeId);
    const startedAt = generationStartedAtRef.current;
    trackAnalyticsEvent({
      eventName,
      jobId: revision.jobId,
      routeId: revision.routeId,
      routeProfile: selectedOption?.profile,
      routeMode: detail.routeMode,
      vibes: detail.vibes,
      timeBudgetMinutes: detail.timeBudgetMinutes,
      regionKey: coarseAnalyticsRegionKey(detail.startLat, detail.startLng),
      routeCount: detail.optionCount,
      status: revision.optionsComplete ? "completed" : "primary_ready",
      durationMs: startedAt === null ? null : Math.max(0, performanceNow() - startedAt),
      scenicScore: selectedOption?.scenicScore ?? detail.scenicScore,
      metadata: {
        source: revision.source,
        stateRevision: revision.stateRevision,
        optionRevision: revision.optionRevision,
        optionCount: detail.optionCount,
        optionsComplete: revision.optionsComplete
      }
    });
  }, []);

  useLayoutEffect(() => {
    if (
      !route
      || !displayedRevision
      || route.routeId !== displayedRevision.routeId
      || displayedRevisionRef.current !== displayedRevision
    ) {
      return;
    }
    emitMilestone("route_results_committed", route, displayedRevision);
  }, [displayedRevision, emitMilestone, route]);

  const handleRouteMapPainted = useCallback((jobId: string, routeId: string, optionRevision: number) => {
    const detail = routeRef.current;
    const revision = displayedRevisionRef.current;
    if (
      !detail
      || !revision
      || revision.epoch !== activeEpochRef.current
      || revision.jobId !== activeJobIdRef.current
      || detail.jobId !== jobId
      || revision.jobId !== jobId
      || detail.routeId !== routeId
      || revision.routeId !== routeId
      || revision.optionRevision !== optionRevision
    ) {
      return false;
    }
    const paintKey = routePaintRevisionKey(revision);
    paintedRouteRevisionKeysRef.current.add(paintKey);
    const waiters = routePaintWaitersRef.current.get(paintKey);
    if (waiters) {
      routePaintWaitersRef.current.delete(paintKey);
      for (const settle of waiters) settle(true);
    }
    emitMilestone("route_map_painted", detail, revision);
    return true;
  }, [emitMilestone]);

  useEffect(() => {
    return () => {
      invalidateActiveJob();
    };
  }, [invalidateActiveJob]);

  const handleGenerate = useCallback(async () => {
    if (routeMode !== "drive" || vibes.length === 0) return;

    const epoch = invalidateActiveJob();
    setPendingOptionId("");
    setDetailRetryOptionId("");
    const controller = new AbortController();
    activeAbortRef.current = controller;
    generationStartedAtRef.current = performanceNow();
    routeResponseCacheRef.current.clear();
    routeRequestFlightsRef.current.clear();
    richDetailsRef.current.clear();
    routeRef.current = null;
    displayedRevisionRef.current = null;
    setRoute(null);
    setDisplayedRevision(null);
    setSelectedRouteId("");

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
    setLargeResultsOpen(true);

    progressTimerRef.current = window.setInterval(() => {
      setProgressStep((current) => Math.min(current + 1, LOADING_STEPS.length - 1));
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
      }, controller.signal);
      if (epoch !== activeEpochRef.current || controller.signal.aborted) return;

      activeJobIdRef.current = submission.jobId;
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

      let pollingStarted = false;
      let pollInFlight = false;
      let finalWsTerminalStatusRequested = false;

      const isActiveJob = () =>
        epoch === activeEpochRef.current
        && submission.jobId === activeJobIdRef.current
        && !controller.signal.aborted;

      const renderPrimary = async (
        update: LifecycleUpdate,
        source: LifecycleSource,
        stateRevision: number,
        statusRank: number,
        optionRevision: number
      ) => {
        if (!update.routeId) return null;

        const acceptedRoute = routeRef.current;
        const acceptedRevision = displayedRevisionRef.current;
        const committedRouteId = selectedOptionIdRef.current;
        const alternativeSelected = Boolean(
          acceptedRoute
          && acceptedRevision
          && committedRouteId
          && acceptedRoute.routeId === committedRouteId
          && committedRouteId !== update.routeId
        );
        const responseRouteId = alternativeSelected ? committedRouteId : update.routeId;
        const detail = await resolveRouteResponse(
          submission.jobId,
          responseRouteId,
          optionRevision,
          alternativeSelected ? "detail" : "primary",
          false,
          controller.signal
        );
        const cursor = lifecycleCursorRef.current;
        if (
          !isActiveJob()
          || cursor.stateRevision !== stateRevision
          || cursor.statusRank !== statusRank
          || cursor.optionRevision !== optionRevision
          || detail.optionRevision < optionRevision
        ) {
          return null;
        }

        const currentRoute = routeRef.current;
        const currentRevision = displayedRevisionRef.current;
        const currentSelectedId = selectedOptionIdRef.current;
        if (
          currentRoute
          && currentRevision
          && currentRoute.jobId === submission.jobId
          && currentRoute.routeId === currentSelectedId
          && (
            alternativeSelected
            || (currentRevision.rich && currentRoute.routeId === update.routeId)
          )
        ) {
          if (alternativeSelected && currentSelectedId !== responseRouteId) return null;
          const mergedRoute = mergeAcceptedRouteLifecycle(
            currentRoute,
            detail,
            update.optionCount,
            update.optionsComplete
          );
          const mergedRevision: DisplayedRouteRevision = {
            epoch,
            jobId: submission.jobId,
            routeId: currentRoute.routeId,
            stateRevision,
            optionRevision: mergedRoute.optionRevision,
            optionsComplete: mergedRoute.optionsComplete,
            rich: currentRevision.rich,
            source
          };
          if (!applyDisplayedRoute(mergedRoute, mergedRevision)) return null;

          if (!pendingOptionIdRef.current) setStatusMessage("");
          setDetailRetryOptionId((retryId) => retryId === currentRoute.routeId ? "" : retryId);
          setPhase("completed");
          emitMilestone("route_generation_primary_ready", mergedRoute, mergedRevision);
          return mergedRoute;
        }

        if (currentSelectedId !== "" && currentSelectedId !== update.routeId) return null;
        const revision: DisplayedRouteRevision = {
          epoch,
          jobId: submission.jobId,
          routeId: detail.routeId,
          stateRevision,
          optionRevision: detail.optionRevision,
          optionsComplete: detail.optionsComplete,
          rich: false,
          source
        };
        if (!applyDisplayedRoute(detail, revision)) return null;

        stopProgress();
        setProgressStep(LOADING_STEPS.length);
        if (!pendingOptionIdRef.current) setStatusMessage("");
        setPhase("completed");
        emitMilestone("route_generation_primary_ready", detail, revision);
        return detail;
      };

      const handleLifecycle = async (update: LifecycleUpdate, source: LifecycleSource) => {
        if (
          !isActiveJob()
          || update.jobId !== submission.jobId
          || terminalAcceptedRef.current
        ) {
          return;
        }

        let lifecycleUpdate = update;
        const incomingStatus = normalizeLifecycleStatus(update.status);
        if (
          source === "ws"
          && (incomingStatus === "failed" || incomingStatus === "timeout")
        ) {
          const incomingStateRevision = Number.isFinite(update.stateRevision) ? update.stateRevision! : 0;
          const incomingOptionRevision = Number.isFinite(update.optionRevision) ? update.optionRevision! : 0;
          const currentCursor = lifecycleCursorRef.current;
          if (
            incomingStateRevision < currentCursor.stateRevision
            || incomingOptionRevision < currentCursor.optionRevision
            || (
              incomingStateRevision === currentCursor.stateRevision
              && lifecycleStatusRank(incomingStatus) < currentCursor.statusRank
            )
          ) {
            return;
          }
          if (finalWsTerminalStatusRequested) return;
          finalWsTerminalStatusRequested = true;
          terminalAcceptedRef.current = true;
          closeActiveChannels();
          stopProgress();
          setProgressStep(LOADING_STEPS.length);
          try {
            const finalStatus = await getJobStatus(submission.jobId, controller.signal);
            if (!isActiveJob() || finalStatus.jobId !== submission.jobId) return;
            const finalLifecycleStatus = normalizeLifecycleStatus(finalStatus.status);
            if (
              isTerminalLifecycleStatus(finalLifecycleStatus)
              && finalStatus.stateRevision >= incomingStateRevision
              && finalStatus.optionRevision >= incomingOptionRevision
            ) {
              lifecycleUpdate = finalStatus;
            }
          } catch (error) {
            if (!isActiveJob() || isAbortError(error)) return;
            // Retain the terminal websocket reason when final status guidance is unavailable.
          }
        }

        const status = normalizeLifecycleStatus(lifecycleUpdate.status);
        const stateRevision = Number.isFinite(lifecycleUpdate.stateRevision)
          ? lifecycleUpdate.stateRevision!
          : 0;
        const optionRevision = Number.isFinite(lifecycleUpdate.optionRevision)
          ? lifecycleUpdate.optionRevision!
          : 0;
        const statusRank = lifecycleStatusRank(status);
        if (source === "ws" && !isTerminalLifecycleStatus(status) && !pollingStarted) {
          rearmWsGrace();
        }

        const cursor = lifecycleCursorRef.current;
        const displayed = displayedRevisionRef.current;
        const retryingUnrenderedPrimary = (
          status === "primary_ready"
          && cursor.status === "primary_ready"
          && stateRevision === cursor.stateRevision
          && statusRank === cursor.statusRank
          && optionRevision === cursor.optionRevision
          && (lifecycleUpdate.routeId ?? null) === cursor.routeId
          && !(
            displayed
            && displayed.epoch === epoch
            && displayed.jobId === submission.jobId
            && displayed.stateRevision === stateRevision
            && displayed.optionRevision >= optionRevision
          )
        );
        const newerOptionsAtSameLifecycle = (
          status === cursor.status
          && stateRevision === cursor.stateRevision
          && statusRank === cursor.statusRank
          && optionRevision > cursor.optionRevision
        );
        if (
          optionRevision < cursor.optionRevision
          || stateRevision < cursor.stateRevision
          || (
            stateRevision === cursor.stateRevision
            && statusRank <= cursor.statusRank
            && !retryingUnrenderedPrimary
            && !newerOptionsAtSameLifecycle
          )
        ) {
          return;
        }
        lifecycleCursorRef.current = {
          stateRevision,
          statusRank,
          status,
          optionRevision,
          routeId: lifecycleUpdate.routeId ?? null
        };

        const terminal = isTerminalLifecycleStatus(status);
        if (terminal) {
          terminalAcceptedRef.current = true;
          closeActiveChannels();
          stopProgress();
          setProgressStep(LOADING_STEPS.length);
        }

        if (status === "primary_ready") {
          if (!lifecycleUpdate.routeId) return;
          try {
            await renderPrimary(lifecycleUpdate, source, stateRevision, statusRank, optionRevision);
          } catch (error) {
            if (isActiveJob() && !isAbortError(error)) {
              const retainedRoute = routeRef.current;
              if (retainedRoute?.jobId === submission.jobId) {
                setDetailRetryOptionId(selectedOptionIdRef.current || retainedRoute.routeId);
                setStatusMessage("Your route is still ready, but newer option details could not be loaded. Retry when you are ready.");
                setPhase("completed");
              } else {
                setStatusMessage(error instanceof Error ? error.message : "Failed to load the primary route.");
              }
            }
          }
          return;
        }

        if (status === "completed") {
          const targetRouteId = selectedOptionIdRef.current || lifecycleUpdate.routeId;
          const selectionRequest = selectionRequestRef.current;
          const requiredCompletionRevision = Math.max(
            optionRevision,
            displayedRevisionRef.current?.optionRevision ?? optionRevision
          );
          const richFlight = targetRouteId
            ? resolveRouteResponse(
                submission.jobId,
                targetRouteId,
                requiredCompletionRevision,
                "detail",
                true,
                controller.signal
              ).then(
                (detail) => ({ ok: true as const, detail }),
                (error: unknown) => ({ ok: false as const, error })
              )
            : null;

          const startedAt = generationStartedAtRef.current;
          const currentRoute = routeRef.current;
          const selectedOption = currentRoute
            ? getSelectedRouteOption(currentRoute, selectedOptionIdRef.current)
            : null;
          trackAnalyticsEvent({
            eventName: "route_generation_completed",
            jobId: submission.jobId,
            routeId: lifecycleUpdate.routeId,
            routeProfile: selectedOption?.profile,
            routeMode,
            vibes: currentRoute?.vibes?.length ? currentRoute.vibes : vibes,
            timeBudgetMinutes: timeBudget,
            regionKey: coarseAnalyticsRegionKey(lat, lng),
            routeCount: lifecycleUpdate.optionCount ?? currentRoute?.optionCount ?? null,
            status: "completed",
            durationMs: startedAt === null ? null : Math.max(0, performanceNow() - startedAt),
            scenicScore: selectedOption?.scenicScore ?? currentRoute?.scenicScore,
            metadata: {
              source,
              stateRevision,
              optionRevision,
              optionCount: lifecycleUpdate.optionCount,
              optionsComplete: lifecycleUpdate.optionsComplete
            }
          });

          let canonicalRoute = routeRef.current?.jobId === submission.jobId
            ? routeRef.current
            : null;
          if (!canonicalRoute && lifecycleUpdate.routeId) {
            try {
              canonicalRoute = await renderPrimary(
                lifecycleUpdate,
                source,
                stateRevision,
                statusRank,
                optionRevision
              );
            } catch (error) {
              if (!isAbortError(error)) {
                const fallbackKey = `${epoch}:${submission.jobId}:${lifecycleUpdate.routeId}:primary`;
                const lowerRevisionPrimary = primaryFallbacksRef.current.get(fallbackKey);
                if (
                  lowerRevisionPrimary
                  && lowerRevisionPrimary.jobId === submission.jobId
                  && lowerRevisionPrimary.routeId === lifecycleUpdate.routeId
                  && lowerRevisionPrimary.optionRevision <= optionRevision
                ) {
                  const nonCompletePrimary: RouteDetailResponse = {
                    ...lowerRevisionPrimary,
                    optionsComplete: false
                  };
                  const fallbackRevision: DisplayedRouteRevision = {
                    epoch,
                    jobId: submission.jobId,
                    routeId: nonCompletePrimary.routeId,
                    stateRevision,
                    optionRevision: nonCompletePrimary.optionRevision,
                    optionsComplete: false,
                    rich: false,
                    source
                  };
                  if (applyDisplayedRoute(nonCompletePrimary, fallbackRevision)) {
                    canonicalRoute = nonCompletePrimary;
                    setStatusMessage("The primary route is ready while complete route details finish loading.");
                    setPhase("completed");
                    emitMilestone("route_generation_primary_ready", nonCompletePrimary, fallbackRevision);
                  }
                } else {
                  setStatusMessage(error instanceof Error ? error.message : "Failed to load the primary route.");
                }
              }
            }
          }
          if (!isActiveJob()) return;
          if (canonicalRoute) setPhase("completed");
          const primaryPaintRevision = canonicalRoute
            && displayedRevisionRef.current?.epoch === epoch
            && displayedRevisionRef.current.jobId === submission.jobId
            && displayedRevisionRef.current.routeId === canonicalRoute.routeId
              ? displayedRevisionRef.current
              : null;
          if (
            !primaryPaintRevision
            || (canonicalRoute?.geometry?.geometry?.coordinates?.length ?? 0) < 2
          ) {
            setFailureGuidance(null);
            setStatusMessage("Route generation completed without a readable primary route.");
            setPhase("failed");
            return;
          }

          if (!targetRouteId || !richFlight) {
            if (!canonicalRoute) {
              setFailureGuidance(null);
              setStatusMessage("Route generation completed without a readable route.");
              setPhase("failed");
            }
            return;
          }

          try {
            const richOutcome = await richFlight;
            if (!richOutcome.ok) throw richOutcome.error;
            const detail = richOutcome.detail;
            const latestCursor = lifecycleCursorRef.current;
            if (
              !isActiveJob()
              || latestCursor.stateRevision !== stateRevision
              || latestCursor.status !== "completed"
              || latestCursor.optionRevision !== optionRevision
              || selectionRequest !== selectionRequestRef.current
              || selectedOptionIdRef.current !== targetRouteId
            ) {
              return;
            }

            if (
              !primaryPaintRevision
              || !await waitForRouteMapPainted(primaryPaintRevision, controller.signal)
            ) {
              return;
            }
            const postPaintCursor = lifecycleCursorRef.current;
            if (
              !isActiveJob()
              || postPaintCursor.stateRevision !== stateRevision
              || postPaintCursor.status !== "completed"
              || postPaintCursor.optionRevision !== optionRevision
              || selectionRequest !== selectionRequestRef.current
              || selectedOptionIdRef.current !== targetRouteId
            ) {
              return;
            }

            const revision: DisplayedRouteRevision = {
              epoch,
              jobId: submission.jobId,
              routeId: detail.routeId,
              stateRevision,
              optionRevision: detail.optionRevision,
              optionsComplete: detail.optionsComplete,
              rich: true,
              source
            };
            if (applyDisplayedRoute(detail, revision)) {
              setDetailRetryOptionId("");
              if (!pendingOptionIdRef.current) setStatusMessage("");
              setPhase("completed");
            }
          } catch (error) {
            if (
              !isActiveJob()
              || selectionRequest !== selectionRequestRef.current
              || isAbortError(error)
            ) {
              return;
            }
            const retainedRoute = routeRef.current?.jobId === submission.jobId
              ? routeRef.current
              : null;
            if (retainedRoute) {
              setDetailRetryOptionId(targetRouteId);
              setStatusMessage("Your route is ready. More route details are temporarily unavailable. Retry to load them.");
              setPhase("completed");
            } else {
              setFailureGuidance(null);
              setStatusMessage(error instanceof Error ? error.message : "Failed to load the completed route.");
              setPhase("failed");
            }
          }
          return;
        }

        if (status === "failed" || status === "timeout") {
          selectionRequestRef.current += 1;
          pendingOptionIdRef.current = "";
          setPendingOptionId("");
          setDetailRetryOptionId("");
          const suggestedVibes = lifecycleUpdate.suggestedVibes ?? [];
          const suggestedActions = lifecycleUpdate.suggestedActions ?? [];
          setFailureGuidance(
            lifecycleUpdate.failureCode || suggestedVibes.length > 0 || suggestedActions.length > 0
              ? {
                  failureCode: lifecycleUpdate.failureCode ?? null,
                  suggestedVibes,
                  suggestedActions
                }
              : null
          );
          setStatusMessage(
            lifecycleUpdate.userMessage
            ?? lifecycleUpdate.reason
            ?? (status === "timeout" ? "Route generation timed out." : "Route generation failed.")
          );
          setPhase("failed");

          const startedAt = generationStartedAtRef.current;
          trackAnalyticsEvent({
            eventName: "route_generation_failed",
            jobId: submission.jobId,
            routeMode,
            vibes,
            timeBudgetMinutes: timeBudget,
            regionKey: coarseAnalyticsRegionKey(lat, lng),
            status: lifecycleUpdate.failureCode ?? status,
            durationMs: startedAt === null ? null : Math.max(0, performanceNow() - startedAt),
            metadata: {
              source,
              reason: lifecycleUpdate.reason,
              failureCode: lifecycleUpdate.failureCode,
              stateRevision,
              optionRevision
            }
          });
          if (lifecycleUpdate.failureCode === "vibe_unavailable") {
            trackAnalyticsEvent({
              eventName: "vibe_unavailable",
              jobId: submission.jobId,
              routeMode,
              vibes,
              timeBudgetMinutes: timeBudget,
              regionKey: coarseAnalyticsRegionKey(lat, lng),
              status: lifecycleUpdate.failureCode,
              metadata: {
                suggestedVibes: lifecycleUpdate.suggestedVibes,
                suggestedActions: lifecycleUpdate.suggestedActions
              }
            });
          }
          return;
        }

        setStatusMessage("Processing…");
      };

      const pollOnce = async () => {
        if (!isActiveJob() || terminalAcceptedRef.current || pollInFlight) return;
        pollInFlight = true;
        try {
          const status = await getJobStatus(submission.jobId, controller.signal);
          await handleLifecycle(status, "poll");
        } catch (error) {
          if (!isAbortError(error) && isActiveJob()) {
            console.warn("Status polling error:", error);
          }
        } finally {
          pollInFlight = false;
          if (isActiveJob() && !terminalAcceptedRef.current) {
            pollTimerRef.current = window.setTimeout(() => {
              pollTimerRef.current = null;
              void pollOnce();
            }, JOB_STATUS_POLL_INTERVAL_MS);
          }
        }
      };

      const startPolling = (delayMs = 0) => {
        if (pollingStarted || !isActiveJob() || terminalAcceptedRef.current) return;
        pollingStarted = true;
        if (wsGraceTimerRef.current !== null) {
          window.clearTimeout(wsGraceTimerRef.current);
          wsGraceTimerRef.current = null;
        }
        pollTimerRef.current = window.setTimeout(() => {
          pollTimerRef.current = null;
          void pollOnce();
        }, delayMs);
      };

      function rearmWsGrace() {
        if (pollingStarted || !isActiveJob() || terminalAcceptedRef.current) return;
        if (wsGraceTimerRef.current !== null) window.clearTimeout(wsGraceTimerRef.current);
        wsGraceTimerRef.current = window.setTimeout(() => {
          wsGraceTimerRef.current = null;
          startPolling();
        }, WS_FIRST_GRACE_MS);
      }

      const stopWs = connectJobChannel(
        submission.jobId,
        submission.wsChannel,
        (event) => {
          void handleLifecycle(event, "ws");
        },
        (errorMessage) => {
          if (!isActiveJob() || terminalAcceptedRef.current) return;
          console.warn("WS error:", errorMessage);
          startPolling();
        }
      );
      if (terminalAcceptedRef.current || !isActiveJob()) {
        stopWs();
      } else {
        stopWsRef.current = stopWs;
        rearmWsGrace();
      }
    } catch (error) {
      if (epoch !== activeEpochRef.current || controller.signal.aborted || isAbortError(error)) return;
      stopProgress();
      const message = error instanceof Error ? error.message : "An unexpected error occurred.";
      setStatusMessage(message);
      setFailureGuidance(null);
      setPhase("failed");
      const startedAt = generationStartedAtRef.current;
      trackAnalyticsEvent({
        eventName: "route_generation_failed",
        routeMode,
        vibes,
        timeBudgetMinutes: timeBudget,
        regionKey: coarseAnalyticsRegionKey(lat, lng),
        status: "submit_failed",
        durationMs: startedAt === null ? null : Math.max(0, performanceNow() - startedAt),
        metadata: { message: message.slice(0, 240) }
      });
    }
  }, [
    applyDisplayedRoute,
    closeActiveChannels,
    emitMilestone,
    invalidateActiveJob,
    lat,
    lng,
    resolveRouteResponse,
    routeMode,
    waitForRouteMapPainted,
    setSelectedRouteId,
    stopProgress,
    timeBudget,
    vibes
  ]);

  const handleReset = useCallback(() => {
    invalidateActiveJob();
    setPendingOptionId("");
    setDetailRetryOptionId("");
    routeResponseCacheRef.current.clear();
    routeRequestFlightsRef.current.clear();
    richDetailsRef.current.clear();
    routeRef.current = null;
    displayedRevisionRef.current = null;
    setPhase("idle");
    setRoute(null);
    setDisplayedRevision(null);
    setStatusMessage("");
    setFailureGuidance(null);
    setSelectedRouteId("");
    setShowHandoff(false);
    setProgressStep(0);
    setLargeResultsOpen(true);
  }, [invalidateActiveJob, setSelectedRouteId]);

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
    invalidateActiveJob();
    setPendingOptionId("");
    setDetailRetryOptionId("");
    setPhase("idle");
    setStatusMessage("");
    setFailureGuidance(null);
    setShowHandoff(false);
    setProgressStep(0);
    setSheetState('mid');
    setLargeResultsOpen(true);
  }, [invalidateActiveJob, lat, lng, route, selectedOptionId, routeMode, vibes, timeBudget]);

  const handleTryVibe = useCallback((vibe: string) => {
    invalidateActiveJob();
    setPendingOptionId("");
    setDetailRetryOptionId("");
    routeResponseCacheRef.current.clear();
    routeRequestFlightsRef.current.clear();
    richDetailsRef.current.clear();
    routeRef.current = null;
    displayedRevisionRef.current = null;
    setVibes([vibe]);
    setPhase("idle");
    setStatusMessage("");
    setFailureGuidance(null);
    setRoute(null);
    setDisplayedRevision(null);
    setSelectedRouteId("");
    setLargeResultsOpen(true);
  }, [invalidateActiveJob, setSelectedRouteId]);

  const handleOptionSelect = useCallback(async (id: string, forceRetry = false) => {
    const currentRoute = routeRef.current;
    const currentRevision = displayedRevisionRef.current;
    const jobId = currentRoute?.jobId;
    const requestController = activeAbortRef.current;
    if (
      !currentRoute
      || !currentRevision
      || !jobId
      || currentRevision.epoch !== activeEpochRef.current
      || jobId !== activeJobIdRef.current
      || !requestController
      || pendingOptionIdRef.current === id
      || (!forceRetry && selectedOptionIdRef.current === id)
    ) {
      return;
    }

    const selectionRequest = ++selectionRequestRef.current;
    pendingOptionIdRef.current = id;
    setPendingOptionId(id);
    setDetailRetryOptionId("");
    setStatusMessage("Loading the selected route details…");

    try {
      let detail: RouteDetailResponse;
      let acceptedCursor: LifecycleCursor;
      for (;;) {
        if (
          currentRevision.epoch !== activeEpochRef.current
          || jobId !== activeJobIdRef.current
          || selectionRequest !== selectionRequestRef.current
          || pendingOptionIdRef.current !== id
        ) {
          return;
        }

        const requestCursor = lifecycleCursorRef.current;
        const requiredOptionRevision = Math.max(
          requestCursor.optionRevision,
          displayedRevisionRef.current?.optionRevision ?? requestCursor.optionRevision
        );
        const requireComplete = requestCursor.status === "completed";
        const cachedDetail = richDetailsRef.current.get(id);
        detail = cachedDetail
          && cachedDetail.jobId === jobId
          && cachedDetail.routeId === id
          && cachedDetail.optionRevision >= requiredOptionRevision
          && (!requireComplete || cachedDetail.optionsComplete)
            ? cachedDetail
            : await resolveRouteResponse(
                jobId,
                id,
                requiredOptionRevision,
                "detail",
                requireComplete,
                requestController.signal
              );

        acceptedCursor = lifecycleCursorRef.current;
        const latestDisplayedOptionRevision = displayedRevisionRef.current?.optionRevision ?? -1;
        if (
          detail.optionRevision < acceptedCursor.optionRevision
          || detail.optionRevision < latestDisplayedOptionRevision
          || (acceptedCursor.status === "completed" && !detail.optionsComplete)
        ) {
          continue;
        }
        break;
      }

      if (
        currentRevision.epoch !== activeEpochRef.current
        || jobId !== activeJobIdRef.current
        || selectionRequest !== selectionRequestRef.current
        || pendingOptionIdRef.current !== id
      ) {
        return;
      }

      const latestDisplayedRevision = displayedRevisionRef.current;
      const revision: DisplayedRouteRevision = {
        epoch: currentRevision.epoch,
        jobId,
        routeId: detail.routeId,
        stateRevision: acceptedCursor.stateRevision,
        optionRevision: detail.optionRevision,
        optionsComplete: detail.optionsComplete,
        rich: true,
        source: latestDisplayedRevision?.source ?? currentRevision.source
      };
      if (!applyDisplayedRoute(detail, revision, id)) {
        if (
          currentRevision.epoch === activeEpochRef.current
          && jobId === activeJobIdRef.current
          && selectionRequest === selectionRequestRef.current
          && pendingOptionIdRef.current === id
        ) {
          pendingOptionIdRef.current = "";
          setPendingOptionId("");
          setDetailRetryOptionId(id);
          setStatusMessage("That route changed before its details were ready. Your current route is unchanged. Retry when ready.");
        }
        return;
      }

      setDetailRetryOptionId("");
      setStatusMessage("");
      const selectedOption = getSelectedRouteOption(detail, id);
      trackAnalyticsEvent({
        eventName: "route_option_selected",
        jobId,
        routeId: id,
        routeProfile: selectedOption?.profile,
        routeMode: detail.routeMode,
        vibes: detail.vibes,
        timeBudgetMinutes: detail.timeBudgetMinutes,
        regionKey: coarseAnalyticsRegionKey(detail.startLat, detail.startLng),
        routeCount: detail.optionCount,
        scenicScore: selectedOption?.scenicScore ?? detail.scenicScore,
        metadata: {
          stateRevision: revision.stateRevision,
          optionRevision: revision.optionRevision,
          optionsComplete: revision.optionsComplete
        }
      });
    } catch (error) {
      if (
        currentRevision.epoch !== activeEpochRef.current
        || jobId !== activeJobIdRef.current
        || selectionRequest !== selectionRequestRef.current
        || pendingOptionIdRef.current !== id
        || isAbortError(error)
      ) {
        return;
      }
      pendingOptionIdRef.current = "";
      setPendingOptionId("");
      setDetailRetryOptionId(id);
      setStatusMessage("Could not load that route's details. Your current route is unchanged. Retry when ready.");
    }
  }, [applyDisplayedRoute, resolveRouteResponse]);

  const handleRetryDetails = useCallback(() => {
    if (detailRetryOptionId) {
      void handleOptionSelect(detailRetryOptionId, true);
    }
  }, [detailRetryOptionId, handleOptionSelect]);

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
            locationResolving={locationResolving}
            locationError={locationError}
            showDropdown={showDropdown}
            routeMode={routeMode}
            timeBudget={timeBudget}
            vibes={vibes}
            phase={phase}
            statusMessage={phase === "idle" || phase === "failed" ? statusMessage : ""}
            onLatChange={setLat}
            onLngChange={setLng}
            onLocationQueryChange={handleLocationQueryChange}
            onLocationSubmit={handleLocationSubmit}
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
          <RouteMap
            route={route}
            selectedRouteId={selectedOptionId}
            centerLat={lat}
            centerLng={lng}
            theme={theme}
            onRouteSelect={handleOptionSelect}
            onRouteMapPainted={handleRouteMapPainted}
          />
        </div>

        {/* ── Desktop: Results panel on right ── */}
        {isDesktop && phase === "completed" && route && largeResultsOpen && (
          <ResultsPanel
            route={route}
            selectedOptionId={selectedOptionId}
            pendingOptionId={pendingOptionId}
            notice={statusMessage || undefined}
            onRetryDetails={detailRetryOptionId ? handleRetryDetails : undefined}
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
                  locationResolving={locationResolving}
                  locationError={locationError}
                  showDropdown={showDropdown}
                  routeMode={routeMode}
                  timeBudget={timeBudget}
                  vibes={vibes}
                  phase={phase}
                  statusMessage={phase === "idle" ? statusMessage : ""}
                  onLatChange={setLat}
                  onLngChange={setLng}
                  onLocationQueryChange={handleLocationQueryChange}
                  onLocationSubmit={handleLocationSubmit}
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
                  pendingOptionId={pendingOptionId}
                  notice={statusMessage || undefined}
                  onRetryDetails={detailRetryOptionId ? handleRetryDetails : undefined}
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
                locationResolving={locationResolving}
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
                onLocationQueryChange={handleLocationQueryChange}
                onLocationSubmit={handleLocationSubmit}
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
            pendingOptionId={pendingOptionId}
            notice={statusMessage || undefined}
            onRetryDetails={detailRetryOptionId ? handleRetryDetails : undefined}
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
          selectedOptionId={selectedOptionId}
          routeMode={routeMode}
          onClose={() => setShowHandoff(false)}
          onNavigationOpen={handleNavigationOpen}
          onGpxExport={handleGpxExport}
        />
      )}

    </div>
  );
}
