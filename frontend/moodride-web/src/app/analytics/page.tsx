"use client";

import Link from "next/link";
import { useCallback, useEffect, useMemo, useState } from "react";
import { Activity, ArrowLeft, Compass, Gauge, MapPinned, Navigation, RefreshCw, Route, Timer, TrendingUp } from "lucide-react";
import { getAnalyticsSummary } from "@/lib/api";
import type { AnalyticsCountResponse, AnalyticsSummaryResponse } from "@/lib/types";

const DAY_OPTIONS = [1, 7, 30, 90];

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

function formatRange(from: string, to: string) {
  const start = new Date(from);
  const end = new Date(to);
  if (Number.isNaN(start.getTime()) || Number.isNaN(end.getTime())) {
    return "Current window";
  }
  return `${start.toLocaleDateString(undefined, { month: "short", day: "numeric" })} - ${end.toLocaleDateString(undefined, { month: "short", day: "numeric" })}`;
}

function MetricCard({
  label,
  value,
  detail,
  icon: Icon
}: {
  label: string;
  value: string;
  detail: string;
  icon: typeof Activity;
}) {
  return (
    <section className="analytics-card metric-card">
      <div className="metric-card-icon">
        <Icon size={20} />
      </div>
      <div>
        <div className="analytics-label">{label}</div>
        <div className="metric-card-value">{value}</div>
        <div className="analytics-muted">{detail}</div>
      </div>
    </section>
  );
}

function RankedList({
  title,
  items,
  emptyLabel
}: {
  title: string;
  items: AnalyticsCountResponse[];
  emptyLabel: string;
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
                <span>{labelize(item.name)}</span>
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
      {Array.from({ length: 8 }).map((_, index) => (
        <div className="analytics-card analytics-skeleton" key={index} />
      ))}
    </div>
  );
}

export default function AnalyticsPage() {
  const [days, setDays] = useState(30);
  const [summary, setSummary] = useState<AnalyticsSummaryResponse | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  const loadSummary = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const nextSummary = await getAnalyticsSummary(days);
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
              />
              <MetricCard
                icon={TrendingUp}
                label="Success Rate"
                value={formatPercent(summary.routeSuccessRate)}
                detail={`${formatNumber(summary.failedRoutes)} failed · ${formatNumber(summary.vibeUnavailableRoutes)} unavailable`}
              />
              <MetricCard
                icon={Timer}
                label="Avg Generation"
                value={formatDuration(summary.averageGenerationMs)}
                detail="Route job completion time"
              />
              <MetricCard
                icon={Compass}
                label="Avg Options"
                value={formatNumber(summary.averageRouteOptions, 1)}
                detail="Routes returned per success"
              />
              <MetricCard
                icon={Gauge}
                label="Avg Scenic Score"
                value={formatNumber(summary.averageScenicScore, 1)}
                detail="Completed route average"
              />
              <MetricCard
                icon={MapPinned}
                label="Start Drive"
                value={formatNumber(summary.startDriveClicks)}
                detail={`${formatPercent(conversion)} of generate clicks`}
              />
              <MetricCard
                icon={Navigation}
                label="Navigation Opens"
                value={formatNumber(summary.navigationOpens)}
                detail={`${formatPercent(navigationRate)} of start-drive clicks`}
              />
              <MetricCard
                icon={Activity}
                label="Plan New Route"
                value={formatNumber(summary.planNewRouteClicks)}
                detail="Users returning to planning"
              />
            </div>

            <div className="analytics-ranked-grid">
              <RankedList title="Top Vibes" items={summary.topVibes} emptyLabel="No vibe events yet." />
              <RankedList title="Selected Profiles" items={summary.selectedProfiles} emptyLabel="No route selections yet." />
              <RankedList title="Route Modes" items={summary.routeModes} emptyLabel="No route mode data yet." />
            </div>
          </>
        )}
      </div>
    </main>
  );
}
