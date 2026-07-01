"use client";

import Link from "next/link";
import { useCallback, useEffect, useMemo, useState } from "react";
import {
  Activity,
  ArrowDownRight,
  ArrowLeft,
  ArrowUpRight,
  BarChart3,
  Compass,
  Gauge,
  Map,
  MapPinned,
  Navigation,
  RefreshCw,
  Route,
  Timer,
  TrendingUp,
  Users
} from "lucide-react";
import { getAnalyticsSummary } from "@/lib/api";
import type { AnalyticsCountResponse, AnalyticsSummaryResponse } from "@/lib/types";

const DAY_OPTIONS = [1, 7, 30, 90];
const ANALYTICS_SNAPSHOT_VERSION = 1;

type AnalyticsSnapshot = {
  version: number;
  viewedAt: string;
  metrics: Record<string, number>;
};

type MetricDelta = {
  direction: "up" | "down";
  label: string;
};

function formatNumber(value: number, digits = 0) {
  return new Intl.NumberFormat("en", {
    maximumFractionDigits: digits,
    minimumFractionDigits: digits
  }).format(Number.isFinite(value) ? value : 0);
}

function formatPercent(value: number) {
  return `${formatNumber((Number.isFinite(value) ? value : 0) * 100, 1)}%`;
}

function formatDuration(ms: number) {
  if (!Number.isFinite(ms) || ms <= 0) return "0s";
  if (ms < 1000) return `${Math.round(ms)}ms`;
  return `${formatNumber(ms / 1000, 1)}s`;
}

function labelize(value: string) {
  return value.replace(/_/g, " ").replace(/\b\w/g, (letter) => letter.toUpperCase());
}

function formatRankedName(value: string) {
  if (value.startsWith("grid:")) {
    const [, lat, lng] = value.split(":");
    return `Region ${lat}, ${lng}`;
  }
  if (/^\d+$/.test(value)) {
    return `${value} min`;
  }
  return labelize(value);
}

function formatRange(from: string, to: string) {
  const start = new Date(from);
  const end = new Date(to);
  if (Number.isNaN(start.getTime()) || Number.isNaN(end.getTime())) {
    return "Current window";
  }
  return `${start.toLocaleDateString(undefined, { month: "short", day: "numeric" })} - ${end.toLocaleDateString(undefined, { month: "short", day: "numeric" })}`;
}

function snapshotStorageKey(days: number) {
  return `wayward-analytics-snapshot-v${ANALYTICS_SNAPSHOT_VERSION}:${days}d`;
}

function readSnapshot(days: number): AnalyticsSnapshot | null {
  if (typeof window === "undefined") return null;
  try {
    const raw = window.localStorage.getItem(snapshotStorageKey(days));
    if (!raw) return null;
    const parsed = JSON.parse(raw) as AnalyticsSnapshot;
    if (parsed.version !== ANALYTICS_SNAPSHOT_VERSION || !parsed.metrics) return null;
    return parsed;
  } catch {
    return null;
  }
}

function writeSnapshot(days: number, metrics: Record<string, number>) {
  if (typeof window === "undefined") return;
  try {
    const snapshot: AnalyticsSnapshot = {
      version: ANALYTICS_SNAPSHOT_VERSION,
      viewedAt: new Date().toISOString(),
      metrics
    };
    window.localStorage.setItem(snapshotStorageKey(days), JSON.stringify(snapshot));
  } catch {
    // Analytics movement hints are best-effort and should never block the page.
  }
}

function metricsFromSummary(summary: AnalyticsSummaryResponse): Record<string, number> {
  return {
    completedRoutes: summary.completedRoutes,
    uniqueAnonymousClients: summary.uniqueAnonymousClients,
    routeSuccessRate: summary.routeSuccessRate,
    averageGenerationMs: summary.averageGenerationMs,
    p95GenerationMs: summary.p95GenerationMs,
    averageRouteOptions: summary.averageRouteOptions,
    threeOptionRouteRate: summary.threeOptionRouteRate,
    averageScenicScore: summary.averageScenicScore,
    startDriveClicks: summary.startDriveClicks,
    navigationOpens: summary.navigationOpens,
    planNewRouteClicks: summary.planNewRouteClicks
  };
}

function buildDelta(
  key: string,
  currentMetrics: Record<string, number> | null,
  previousSnapshot: AnalyticsSnapshot | null,
  formatter: (value: number) => string
): MetricDelta | null {
  if (!currentMetrics || !previousSnapshot) return null;
  const current = currentMetrics[key];
  const previous = previousSnapshot.metrics[key];
  if (!Number.isFinite(current) || !Number.isFinite(previous)) return null;
  const change = current - previous;
  if (Math.abs(change) < 0.0001) return null;
  return {
    direction: change > 0 ? "up" : "down",
    label: formatter(Math.abs(change))
  };
}

function MetricCard({
  label,
  value,
  detail,
  icon: Icon,
  delta
}: {
  label: string;
  value: string;
  detail: string;
  icon: typeof Activity;
  delta?: MetricDelta | null;
}) {
  return (
    <section className="analytics-card metric-card">
      <div className="metric-card-icon">
        <Icon size={20} />
      </div>
      <div>
        <div className="analytics-label">{label}</div>
        <div className="metric-card-value-row">
          <div className="metric-card-value">{value}</div>
          {delta && (
            <span className={`metric-delta metric-delta-${delta.direction}`} title="Change since your last dashboard visit">
              {delta.direction === "up" ? <ArrowUpRight size={16} /> : <ArrowDownRight size={16} />}
              {delta.label}
            </span>
          )}
        </div>
        <div className="analytics-muted">{detail}</div>
      </div>
    </section>
  );
}

function RankedList({
  title,
  items,
  emptyLabel,
  formatName = formatRankedName
}: {
  title: string;
  items: AnalyticsCountResponse[];
  emptyLabel: string;
  formatName?: (value: string) => string;
}) {
  const max = Math.max(...items.map((item) => item.count), 1);

  return (
    <section className="analytics-card analytics-ranked-card">
      <div className="analytics-section-title">{title}</div>
      {items.length === 0 ? (
        <p className="analytics-empty-copy">{emptyLabel}</p>
      ) : (
        <div className="analytics-ranked-list">
          {items.map((item) => (
            <div className="analytics-ranked-row" key={item.name}>
              <div className="analytics-ranked-row-top">
                <span>{formatName(item.name)}</span>
                <strong>{item.count}</strong>
              </div>
              <div className="analytics-bar-track" aria-hidden="true">
                <span style={{ width: `${Math.max(7, (item.count / max) * 100)}%` }} />
              </div>
            </div>
          ))}
        </div>
      )}
    </section>
  );
}

function AnalyticsSkeleton() {
  return (
    <div className="analytics-grid">
      {Array.from({ length: 12 }).map((_, index) => (
        <div className="analytics-card analytics-skeleton" key={index} />
      ))}
    </div>
  );
}

export default function AnalyticsPage() {
  const [days, setDays] = useState(30);
  const [summary, setSummary] = useState<AnalyticsSummaryResponse | null>(null);
  const [previousSnapshot, setPreviousSnapshot] = useState<AnalyticsSnapshot | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  const loadSummary = useCallback(async () => {
    setLoading(true);
    setError(null);
    setPreviousSnapshot(null);
    try {
      const nextSummary = await getAnalyticsSummary(days);
      const nextMetrics = metricsFromSummary(nextSummary);
      setPreviousSnapshot(readSnapshot(days));
      writeSnapshot(days, nextMetrics);
      setSummary(nextSummary);
    } catch (err) {
      setError(err instanceof Error ? err.message : "Analytics summary could not be loaded.");
    } finally {
      setLoading(false);
    }
  }, [days]);

  useEffect(() => {
    void loadSummary();
  }, [loadSummary]);

  const conversion = useMemo(() => {
    if (!summary || summary.generateClicks === 0) return 0;
    return summary.startDriveClicks / summary.generateClicks;
  }, [summary]);

  const navigationRate = useMemo(() => {
    if (!summary || summary.startDriveClicks === 0) return 0;
    return summary.navigationOpens / summary.startDriveClicks;
  }, [summary]);

  const metricValues = useMemo<Record<string, number> | null>(() => {
    if (!summary) return null;
    return metricsFromSummary(summary);
  }, [summary]);

  const lastViewedLabel = useMemo(() => {
    if (!previousSnapshot?.viewedAt) return null;
    const viewedAt = new Date(previousSnapshot.viewedAt);
    if (Number.isNaN(viewedAt.getTime())) return null;
    return viewedAt.toLocaleString(undefined, {
      month: "short",
      day: "numeric",
      hour: "numeric",
      minute: "2-digit"
    });
  }, [previousSnapshot]);

  const deltaFor = useCallback(
    (key: string, formatter: (value: number) => string = (value) => formatNumber(value)) =>
      buildDelta(key, metricValues, previousSnapshot, formatter),
    [metricValues, previousSnapshot]
  );

  return (
    <main className="analytics-page">
      <div className="analytics-shell">
        <header className="analytics-header">
          <div>
            <Link className="analytics-back-link" href="/">
              <ArrowLeft size={16} />
              Route Planner
            </Link>
            <h1 className="wayward-wordmark analytics-wordmark">Wayward</h1>
            <p className="analytics-kicker">Product analytics</p>
          </div>
          <div className="analytics-actions">
            <div className="analytics-day-tabs" aria-label="Analytics date range">
              {DAY_OPTIONS.map((option) => (
                <button
                  className={option === days ? "active" : ""}
                  key={option}
                  onClick={() => setDays(option)}
                  type="button"
                >
                  {option}d
                </button>
              ))}
            </div>
            <button className="analytics-refresh" onClick={() => void loadSummary()} type="button">
              <RefreshCw size={16} />
              Refresh
            </button>
          </div>
        </header>

        {summary && (
          <div className="analytics-window">
            {formatRange(summary.from, summary.to)} · {formatNumber(summary.totalEvents)} total events
            {lastViewedLabel ? ` · changes since ${lastViewedLabel}` : ""}
          </div>
        )}

        {loading && <AnalyticsSkeleton />}

        {!loading && error && (
          <section className="analytics-card analytics-error-card">
            <div className="analytics-section-title">Analytics unavailable</div>
            <p>{error}</p>
            <button className="analytics-refresh" onClick={() => void loadSummary()} type="button">
              <RefreshCw size={16} />
              Try again
            </button>
          </section>
        )}

        {!loading && !error && summary && (
          <>
            <div className="analytics-grid">
              <MetricCard
                icon={Route}
                label="Generated"
                value={formatNumber(summary.completedRoutes)}
                detail={`${formatNumber(summary.generateClicks)} generate clicks`}
                delta={deltaFor("completedRoutes")}
              />
              <MetricCard
                icon={Users}
                label="Anonymous Clients"
                value={formatNumber(summary.uniqueAnonymousClients)}
                detail="Hashed browser/device ids"
                delta={deltaFor("uniqueAnonymousClients")}
              />
              <MetricCard
                icon={TrendingUp}
                label="Success Rate"
                value={formatPercent(summary.routeSuccessRate)}
                detail={`${formatNumber(summary.failedRoutes)} failed · ${formatNumber(summary.vibeUnavailableRoutes)} unavailable`}
                delta={deltaFor("routeSuccessRate", formatPercent)}
              />
              <MetricCard
                icon={Timer}
                label="Avg Generation"
                value={formatDuration(summary.averageGenerationMs)}
                detail="Route job completion time"
                delta={deltaFor("averageGenerationMs", formatDuration)}
              />
              <MetricCard
                icon={BarChart3}
                label="P95 Generation"
                value={formatDuration(summary.p95GenerationMs)}
                detail="Slowest normal route jobs"
                delta={deltaFor("p95GenerationMs", formatDuration)}
              />
              <MetricCard
                icon={Compass}
                label="Avg Options"
                value={formatNumber(summary.averageRouteOptions, 1)}
                detail="Routes returned per success"
                delta={deltaFor("averageRouteOptions", (value) => formatNumber(value, 1))}
              />
              <MetricCard
                icon={Map}
                label="3-Option Rate"
                value={formatPercent(summary.threeOptionRouteRate)}
                detail="Completed jobs with full choice set"
                delta={deltaFor("threeOptionRouteRate", formatPercent)}
              />
              <MetricCard
                icon={Gauge}
                label="Avg Scenic Score"
                value={formatNumber(summary.averageScenicScore, 1)}
                detail="Completed route average"
                delta={deltaFor("averageScenicScore", (value) => formatNumber(value, 1))}
              />
              <MetricCard
                icon={MapPinned}
                label="Start Drive"
                value={formatNumber(summary.startDriveClicks)}
                detail={`${formatPercent(conversion)} of generate clicks`}
                delta={deltaFor("startDriveClicks")}
              />
              <MetricCard
                icon={Navigation}
                label="Navigation Opens"
                value={formatNumber(summary.navigationOpens)}
                detail={`${formatPercent(navigationRate)} of start-drive clicks`}
                delta={deltaFor("navigationOpens")}
              />
              <MetricCard
                icon={Activity}
                label="Plan New Route"
                value={formatNumber(summary.planNewRouteClicks)}
                detail="Users returning to planning"
                delta={deltaFor("planNewRouteClicks")}
              />
            </div>

            <div className="analytics-ranked-grid">
              <RankedList title="Top Vibes" items={summary.topVibes} emptyLabel="No vibe events yet." />
              <RankedList title="Selected Profiles" items={summary.selectedProfiles} emptyLabel="No route selections yet." />
              <RankedList title="Route Modes" items={summary.routeModes} emptyLabel="No route mode data yet." />
              <RankedList title="Top Regions" items={summary.topRegions} emptyLabel="No coarse region buckets yet." />
              <RankedList title="Time Budgets" items={summary.timeBudgetBuckets} emptyLabel="No time budget buckets yet." />
            </div>
          </>
        )}
      </div>
    </main>
  );
}
