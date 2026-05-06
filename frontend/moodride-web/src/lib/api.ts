import {
  RouteDetailResponse,
  RouteJobStatusResponse,
  RouteRatingResponse,
  RouteRequest,
  RouteSubmissionResponse,
  ScenicRegionsResponse
} from "@/lib/types";

const apiBase = process.env.NEXT_PUBLIC_API_BASE_URL ?? "http://localhost:8080";

type ScenicRegionApiResponse = ScenicRegionsResponse["regions"][number] & {
  scenicScore?: number;
  compositeScore?: number;
};

type ScenicRegionsApiResponse = Omit<ScenicRegionsResponse, "regions"> & {
  regions: ScenicRegionApiResponse[];
};

async function handleJson<T>(response: Response): Promise<T> {
  if (!response.ok) {
    const body = await response.text();
    throw new Error(`HTTP ${response.status}: ${body}`);
  }
  return (await response.json()) as T;
}

function sleep(ms: number): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

export async function submitRoute(payload: RouteRequest): Promise<RouteSubmissionResponse> {
  const response = await fetch(`${apiBase}/api/routes`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(payload)
  });
  return handleJson<RouteSubmissionResponse>(response);
}

export async function getJobStatus(jobId: string): Promise<RouteJobStatusResponse> {
  const response = await fetch(`${apiBase}/api/routes/${jobId}`, { cache: "no-store" });
  return handleJson<RouteJobStatusResponse>(response);
}

export async function getRoute(routeId: string): Promise<RouteDetailResponse> {
  const maxAttempts = 5;
  let lastError: Error | null = null;

  for (let attempt = 1; attempt <= maxAttempts; attempt++) {
    try {
      const response = await fetch(`${apiBase}/api/routes/route/${routeId}`, { cache: "no-store" });
      return await handleJson<RouteDetailResponse>(response);
    } catch (error) {
      lastError = error as Error;
      if (attempt < maxAttempts) {
        await sleep(500 * attempt);
      }
    }
  }

  throw lastError ?? new Error("Route detail fetch failed after retries.");
}

export async function getScenicRegions(
  lat: number,
  lng: number,
  radiusKm = 50,
  limit = 25,
  vibe?: string
): Promise<ScenicRegionsResponse> {
  const params = new URLSearchParams({
    lat: String(lat),
    lng: String(lng),
    radiusKm: String(radiusKm),
    limit: String(limit)
  });
  if (vibe) {
    params.set("vibe", vibe);
  }
  const url = `${apiBase}/api/scenic-regions?${params.toString()}`;
  const response = await fetch(url, { cache: "no-store" });
  const payload = await handleJson<ScenicRegionsApiResponse>(response);

  return {
    ...payload,
    regions: payload.regions.map((region) => ({
      ...region,
      scenicScore:
        typeof region.scenicScore === "number"
          ? region.scenicScore
          : typeof region.compositeScore === "number"
            ? region.compositeScore
            : Number.NaN
    }))
  };
}

export async function submitRouteRating(routeId: string, rating: number): Promise<RouteRatingResponse> {
  const response = await fetch(`${apiBase}/api/routes/${routeId}/rating`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ rating })
  });
  return handleJson<RouteRatingResponse>(response);
}

