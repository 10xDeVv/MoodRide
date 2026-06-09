"use client";

import type { RouteDetailResponse, RouteOptionResponse } from "@/lib/types";

interface Props {
  route: RouteDetailResponse;
  selectedOptionId: string;
}

const PROFILE_DISPLAY_NAMES: Record<string, string> = {
  most_scenic: "Most Scenic",
  balanced: "Balanced",
  shorter: "Shorter"
};

const VIBE_HIGHLIGHT_LINES: Record<string, string[]> = {
  coastal:         ["Follows waterfront stretches and open views"],
  riverside:       ["Tracks river corridors and calmer shoreline roads"],
  mountain:        ["Includes elevation changes with hill and ridge views"],
  forest:          ["Routes through greener corridors and wooded areas"],
  countryside:     ["Leans toward quieter roads and open rural scenery"],
  open_roads:      ["Prioritizes sweeping roads with fewer dense intersections"],
  relaxing:        ["Biases toward low-stress roads and calmer scenery"],
  winding_roads:   ["Looks for more road shape, curves, and terrain drama"],
  smooth_cruise:   ["Keeps the route flowing with fewer sharp interruptions"],
  quiet:           ["Favors solitude and lower-density corridors"],
  hidden_gems:     ["Looks for quieter roads with smaller scenic points of interest"],
  minimal_traffic: ["Biases away from busier built-up corridors"],
  scenic:          ["Balances the strongest local scenic signals"],
  sunday_cruise:   ["Leans toward easy countryside and open-road cruising"],
  adventure:       ["Pushes toward terrain, curves, and stronger scenic contrast"],
  photo_worthy:    ["Looks for high-impact water, terrain, or landmark views"],
  nature_escape:   ["Prioritizes forest, riverside, and low-density natural areas"],
  sunset:          ["Looks for open water or elevation views that suit sunset light"]
};

type Signal = {
  key: string;
  label: string;
  value: number;
};

const POSITIVE_SIGNAL_LABELS: Record<string, string> = {
  landscape_score: "Landscape",
  vibe_fit_score: "Mood fit",
  drive_quality_score: "Road feel",
  route_shape_score: "Loop fit",
  scenic_moments_score: "Scenic moments",
  strategy_fit_score: "Vibe corridor",
  water_corridor_share: "Waterfront",
  open_space_corridor_share: "Open space",
  quiet_corridor_share: "Quiet",
  photo_peak_score: "Photo peaks",
  curve_elevation_corridor_share: "Curves + terrain",
  duration_fit_ratio: "Budget fit"
};

function generateHighlights(route: RouteDetailResponse, selectedOption: RouteOptionResponse | undefined): string[] {
  const highlights: string[] = [];

  const profileLabel = selectedOption?.profile
    ? (PROFILE_DISPLAY_NAMES[selectedOption.profile] ?? formatProfile(selectedOption.profile))
    : "Scenic";

  const distKm = Number.isFinite(route.totalDistanceKm) ? route.totalDistanceKm.toFixed(1) : "-";
  const durMin = Number.isFinite(route.estimatedDurationMinutes) ? String(Math.round(route.estimatedDurationMinutes)) : "-";
  highlights.push(`${profileLabel} loop · ${distKm} km · ${durMin} min`);

  if (selectedOption?.explanation?.summary) {
    highlights.push(selectedOption.explanation.summary);
  }

  const activeVibes = route.vibes.length > 0 ? route.vibes : ["countryside"];
  for (const vibe of activeVibes) {
    const lines = VIBE_HIGHLIGHT_LINES[vibe] ?? [];
    for (const line of lines) highlights.push(line);
  }

  if (highlights.length === 1) {
    highlights.push("Prioritizes scenic road segments over direct shortcuts.");
  }

  return highlights;
}

function formatProfile(profile: string): string {
  return profile
    .split("_")
    .filter(Boolean)
    .map((word) => `${word[0].toUpperCase()}${word.slice(1)}`)
    .join(" ");
}

function topSignals(scoreBreakdown: Record<string, number> | undefined): Signal[] {
  if (!scoreBreakdown) {
    return [];
  }

  return Object.entries(POSITIVE_SIGNAL_LABELS)
    .map(([key, label]) => ({
      key,
      label,
      value: scoreBreakdown[key] ?? 0
    }))
    .filter((signal) => Number.isFinite(signal.value) && signal.value > 0)
    .sort((a, b) => b.value - a.value)
    .slice(0, 4);
}

function buildTradeoffLines(route: RouteDetailResponse, selectedOption: RouteOptionResponse | undefined): string[] {
  const scoreBreakdown = selectedOption?.scoreBreakdown ?? route.scoreBreakdown;
  if (!scoreBreakdown) {
    return [];
  }

  const lines: string[] = [];
  const urbanPenalty = scoreBreakdown.urban_penalty ?? 0;
  const startEndPenalty = scoreBreakdown.start_end_penalty ?? 0;
  const durationFit = scoreBreakdown.duration_fit_ratio ?? 0;

  if (urbanPenalty <= 0.18) {
    lines.push("Keeps urban pressure low along the corridor.");
  } else if (urbanPenalty >= 0.36) {
    lines.push("Includes some urban pressure, so the route leans scenic where the road network allows it.");
  }

  if (startEndPenalty >= 0.28) {
    lines.push("The start or finish is less scenic than the best parts of the loop.");
  }

  if (durationFit >= 0.9) {
    lines.push("Uses most of your time budget instead of cutting the drive short.");
  } else if (durationFit > 0 && durationFit <= 0.7) {
    lines.push("Leaves some time budget unused to keep the loop clean and feasible.");
  }

  return lines.slice(0, 3);
}

function formatSignalValue(value: number): string {
  return `${Math.round(Math.max(0, Math.min(1, value)) * 100)}%`;
}

export function ScenicHighlightsPanel({ route, selectedOptionId }: Props) {
  const selectedOption = route.routeOptions.find((option) => option.routeId === selectedOptionId)
    ?? route.routeOptions.find((option) => option.routeId === route.routeId)
    ?? route.routeOptions[0];
  const signals = topSignals(selectedOption?.scoreBreakdown ?? route.scoreBreakdown);
  const tradeoffs = buildTradeoffLines(route, selectedOption);
  const highlights = generateHighlights(route, selectedOption);
  const [headline, ...details] = highlights;

  return (
    <>
      <p className="scenic-highlights-headline">
        {headline}
      </p>
      {details.length > 0 && (
        <ul className="scenic-highlights-list">
          {details.map((h, i) => (
            <li key={i} className="scenic-highlights-item">
              <span className="scenic-highlights-bullet" />
              {h}
            </li>
          ))}
        </ul>
      )}
      {signals.length > 0 && (
        <div className="route-insight-signals" aria-label="Strongest route signals">
          {signals.map((signal) => (
            <div className="route-insight-signal" key={signal.key}>
              <span className="route-insight-signal-label">{signal.label}</span>
              <span className="route-insight-signal-value">{formatSignalValue(signal.value)}</span>
            </div>
          ))}
        </div>
      )}
      {tradeoffs.length > 0 && (
        <ul className="route-insight-tradeoffs" aria-label="Route tradeoffs">
          {tradeoffs.map((tradeoff) => (
            <li key={tradeoff}>{tradeoff}</li>
          ))}
        </ul>
      )}
    </>
  );
}
