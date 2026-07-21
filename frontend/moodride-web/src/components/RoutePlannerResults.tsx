import { useState } from "react";
import {
  AlertTriangle, ArrowRight, ChevronDown, ChevronUp,
  Download, Map as MapIcon, Navigation, RefreshCw
} from "lucide-react";
import type { BottomSheetState } from "./BottomSheet";
import type {
  RouteDetailResponse,
  RouteJobStatusResponse,
  RouteMode,
  RouteOptionResponse
} from "@/lib/types";

const USER_SIGNAL_ORDER = ["water", "elevation", "solitude", "greenery", "curves"];

const ROUTE_SIGNAL_LABELS: Record<string, string> = {
  water: "Waterfront",
  greenery: "Green cover",
  elevation: "Rolling terrain",
  solitude: "Quiet roads",
  curves: "Curves"
};

const VIBE_LABELS: Record<string, string> = {
  coastal: "Waterside",
  mountain: "Mountain",
  countryside: "Country",
  riverside: "Riverside",
  nature_escape: "Nature",
  open_roads: "Open Road",
  adventure: "Adventure",
  relaxing: "Relaxing",
  winding_roads: "Winding"
};

const GOOGLE_TRAVEL_MODES: Record<RouteMode, string> = { drive: "driving", walk: "walking", bike: "bicycling" };
const APPLE_TRAVEL_FLAGS: Partial<Record<RouteMode, string>> = { drive: "d", walk: "w" };

export type FailureGuidance = {
  failureCode: string | null;
  suggestedVibes: string[];
  suggestedActions: string[];
};
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

function formatProfileName(profile?: string | null) {
  return profile?.split("_").map((w) => w[0].toUpperCase() + w.slice(1)).join(" ") ?? "Route";
}

export function getSelectedRouteOption(route: RouteDetailResponse, selectedOptionId: string) {
  return route.routeOptions?.find((o) => o.routeId === selectedOptionId)
    ?? route.routeOptions?.[0];
}

function scenicFitLabel(score?: number | null) {
  if (!Number.isFinite(score ?? NaN)) return "Scenic match";
  const score100 = Math.max(0, Math.min(100, score ?? 0));
  if (score100 >= 70) return "Best scenic match";
  if (score100 >= 40) return "Good scenic match";
  return "Some scenic match";
}

function scenicFitBand(score?: number | null) {
  if (!Number.isFinite(score ?? NaN)) return "Scenic";
  const score100 = Math.max(0, Math.min(100, score ?? 0));
  if (score100 >= 70) return "Best";
  if (score100 >= 40) return "Good";
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

export function SelectedRouteChip({
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

export function MobileRouteDock({
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

export function RouteSessionDock({
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

export function MobileResultsPanel({
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

export function ResultsPanel({
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
export function HandoffModal({
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
export function FailedCard({
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
  return VIBE_LABELS[vibe] ?? vibe.replace(/_/g, " ");
}

export function guidanceFromStatus(status: RouteJobStatusResponse): FailureGuidance | null {
  if (!status.failureCode && (!status.suggestedVibes || status.suggestedVibes.length === 0)) {
    return null;
  }
  return {
    failureCode: status.failureCode,
    suggestedVibes: status.suggestedVibes ?? [],
    suggestedActions: status.suggestedActions ?? [],
  };
}
