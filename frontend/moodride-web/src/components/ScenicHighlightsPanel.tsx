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
  open_roads: ["Prioritizes sweeping roads with fewer dense intersections"],
  relaxing: ["Biases toward low-stress roads and calmer scenery"],
  winding_roads: ["Looks for more road shape, curves, and terrain drama"],
  smooth_cruise: ["Keeps the route flowing with fewer sharp interruptions"],
  quiet: ["Favors solitude and lower-density corridors"],
  hidden_gems: ["Looks for quieter roads with smaller scenic points of interest"],
  minimal_traffic: ["Biases away from busier built-up corridors"],
  loop_variety: ["Tries to avoid repeated scenery and same-corridor loops"],
  scenic: ["Balances the strongest local scenic signals"],
  clear_my_head: ["Prioritizes peaceful green or rural stretches"],
  date_night: ["Blends scenic views with relaxed, photo-friendly stops"],
  sunday_cruise: ["Leans toward easy countryside and open-road cruising"],
  adventure: ["Pushes toward terrain, curves, and stronger scenic contrast"],
  photo_run: ["Looks for high-impact water, terrain, or landmark views"],
  photo_worthy: ["Looks for high-impact water, terrain, or landmark views"],
  nature_escape: ["Prioritizes forest, riverside, and low-density natural areas"],
  scenic_reset: ["Balances scenic quality without making the drive intense"],
  golden_hour: ["Looks for open water or elevation views that suit golden-hour light"],
  sunset: ["Looks for open water or elevation views that suit sunset light"],
  sunrise: ["Looks for open water or elevation views that suit sunrise light"]
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

