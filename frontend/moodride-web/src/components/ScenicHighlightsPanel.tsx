import type { RouteDetailResponse } from "@/lib/types";

interface Props {
  route: RouteDetailResponse | null;
}

const PROFILE_DISPLAY_NAMES: Record<string, string> = {
  most_scenic: "Most Scenic",
  balanced: "Balanced",
  shorter: "Shorter"
};

const VIBE_HIGHLIGHT_LINES: Record<string, string[]> = {
  coastal: ["Follows waterfront stretches and open views"],
  riverside: ["Tracks river corridors and calmer shoreline roads"],
  mountain: ["Includes elevation changes with hill and ridge views"],
  forest: ["Routes through greener corridors and wooded areas"],
  countryside: ["Leans toward quieter roads and open rural scenery"],
  open_roads: ["Prioritizes sweeping roads with fewer dense intersections"]
};

export function ScenicHighlightsPanel({ route }: Props) {
  if (!route) {
    return <p className="small">Scenic highlights will appear after route generation.</p>;
  }

  const highlights = generateHighlights(route);
  const headline = highlights[0];
  const details = highlights.slice(1);

  return (
    <div className="highlights">
      <p className="highlight-headline">{headline}</p>
      {details.length > 0 && (
        <ul className="highlight-list">
          {details.map((highlight, idx) => (
            <li key={`highlight-${idx}`}>{highlight}</li>
          ))}
        </ul>
      )}
    </div>
  );
}

function generateHighlights(route: RouteDetailResponse): string[] {
  const highlights: string[] = [];
  const profile = resolveProfileLabel(route);
  const distance = Number.isFinite(route.totalDistanceKm) ? route.totalDistanceKm.toFixed(1) : "N/A";
  const duration = Number.isFinite(route.estimatedDurationMinutes) ? String(route.estimatedDurationMinutes) : "N/A";

  highlights.push(`${profile} loop · ${distance} km · ${duration} min`);

  const activeVibes = route.vibes.length > 0 ? route.vibes : ["countryside"];
  activeVibes.forEach((vibe) => {
    const lines = VIBE_HIGHLIGHT_LINES[vibe] ?? [];
    lines.forEach((line) => highlights.push(line));
  });

  if (highlights.length === 1) {
    highlights.push("Prioritizes scenic road segments over direct shortcuts.");
  }

  return highlights;
}

function resolveProfileLabel(route: RouteDetailResponse): string {
  const activeOption = route.routeOptions.find((option) => option.routeId === route.routeId);
  if (activeOption?.profile) {
    return formatProfile(activeOption.profile);
  }

  return "Scenic";
}

function formatProfile(profile: string): string {
  if (PROFILE_DISPLAY_NAMES[profile]) {
    return PROFILE_DISPLAY_NAMES[profile];
  }

  return profile
    .split("_")
    .filter((segment) => segment.length > 0)
    .map((segment) => segment[0].toUpperCase() + segment.slice(1))
    .join(" ");
}

