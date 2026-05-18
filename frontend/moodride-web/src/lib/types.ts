export type Vibe =
  | "coastal"
  | "mountain"
  | "forest"
  | "countryside"
  | "riverside"
  | "open_roads"
  | "relaxing"
  | "winding_roads"
  | "smooth_cruise"
  | "quiet"
  | "hidden_gems"
  | "minimal_traffic"
  | "loop_variety"
  | "scenic"
  | "clear_my_head"
  | "date_night"
  | "sunday_cruise"
  | "adventure"
  | "photo_run"
  | "photo_worthy"
  | "nature_escape"
  | "scenic_reset"
  | "golden_hour"
  | "sunset"
  | "sunrise";
export type RouteMode = "drive" | "walk" | "bike";

export interface RouteRequest {
  userId: string;
  lat: number;
  lng: number;
  timeBudgetMinutes: number;
  routeMode: RouteMode;
  vibes: string[];
  preferenceVector: Record<string, number>;
}

export interface RouteSubmissionResponse {
  jobId: string;
  status: string;
  estimatedCompletionSeconds: number;
  statusUrl: string;
  wsChannel: string;
  queuedAt: string;
  retryCount: number;
  maxRetries: number;
}

export interface RouteOptionResponse {
  profile: string;
  routeId: string;
  routeUrl: string;
  scenicScore: number;
  totalDistanceKm: number;
  estimatedDurationMinutes: number;
  explanation: RouteOptionExplanationResponse | null;
}

export interface RouteOptionExplanationResponse {
  componentAverages: Record<string, number>;
  baselineAverages: Record<string, number>;
  componentLifts: Record<string, number>;
  componentWeights: Record<string, number>;
  weightedContributions: Record<string, number>;
  leadingComponents: string[];
  summary: string;
  sampleTileCount: number;
  baselineTileCount: number;
}

export interface RouteJobStatusResponse {
  jobId: string;
  status: string;
  routeId: string | null;
  routeUrl: string | null;
  routeOptions: RouteOptionResponse[];
  reason: string | null;
  queuedAt: string;
  startedAt: string | null;
  completedAt: string | null;
  failedAt: string | null;
  estimatedRemainingSeconds: number | null;
  retryCount: number;
  maxRetries: number;
  routeMode: RouteMode;
}

export interface RouteDetailResponse {
  routeId: string;
  jobId: string;
  routeUrl: string;
  scenicScore: number;
  qualityTier: string;
  totalDistanceKm: number;
  estimatedDurationMinutes: number;
  timeBudgetMinutes: number | null;
  routeMode: RouteMode;
  startLat: number;
  startLng: number;
  vibes: string[];
  geometry: {
    type: "Feature";
    geometry: {
      type: "LineString";
      coordinates: [number, number][];
    };
    properties: {
      segmentScores: number[];
      segmentColors: string[];
    };
  };
  scenicHighlights: Record<string, unknown>[];
  routeOptions: RouteOptionResponse[];
  algorithmVersion: string;
  beamCandidates: number | null;
  computationTimeMs: number | null;
  userRating: number | null;
  ratedAt: string | null;
  createdAt: string;
  expiresAt: string | null;
}

export interface ScenicRegion {
  h3Index: string;
  scenicScore: number;
  centerLat: number;
  centerLng: number;
  dominantFeature: string;
  confidence: number;
}

export interface ScenicRegionsResponse {
  regions: ScenicRegion[];
  totalRegions: number;
  boundingBox: {
    north: number;
    south: number;
    east: number;
    west: number;
  };
}

export interface JobSocketEvent {
  jobId: string;
  routeId?: string;
  scenicScore?: number;
  reason?: string;
  retryable?: boolean;
  timestamp?: string;
}

export interface RouteRatingResponse {
  routeId: string;
  rating: number;
  ratedAt: string;
}

export interface LocationSuggestion {
  placeId: string;
  displayName: string;
  lat: number;
  lng: number;
}

