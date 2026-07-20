import {
  AnalyticsEventPayload,
  AnalyticsSummaryResponse,
  LocationSuggestion,
  PrimaryRouteResponse,
  RouteDetailResponse,
  RouteJobStatusResponse,
  RouteRatingResponse,
  RouteRequest,
  RouteSubmissionResponse,
  ScenicRegionsResponse
} from "@/lib/types";

const apiBase =
  process.env.NEXT_PUBLIC_API_BASE_URL ??
  (typeof window !== "undefined" ? "" : "https://usewayward.app");

const ROUTE_SUBMISSION_REQUEST_TIMEOUT_MS = 15_000;
const STATUS_REQUEST_TIMEOUT_MS = 5_000;
const PRIMARY_ROUTE_REQUEST_TIMEOUT_MS = 8_000;
const ROUTE_DETAIL_REQUEST_TIMEOUT_MS = 12_000;

async function withRequestDeadline<T>(
  timeoutMs: number,
  callerSignal: AbortSignal | undefined,
  request: (signal: AbortSignal) => Promise<T>
): Promise<T> {
  const controller = new AbortController();
  const onCallerAbort = () => {
    controller.abort(callerSignal?.reason);
  };

  if (callerSignal?.aborted) {
    onCallerAbort();
  } else {
    callerSignal?.addEventListener("abort", onCallerAbort, { once: true });
  }

  const timer = setTimeout(() => {
    if (!controller.signal.aborted) {
      controller.abort(new DOMException("Request timed out", "TimeoutError"));
    }
  }, timeoutMs);

  try {
    return await request(controller.signal);
  } finally {
    clearTimeout(timer);
    callerSignal?.removeEventListener("abort", onCallerAbort);
  }
}

function apiUrl(path: string): string {
  if (typeof window !== "undefined") {
    const configuredBase = process.env.NEXT_PUBLIC_API_BASE_URL;
    if (!configuredBase) {
      return `/api/proxy${path}`;
    }

    try {
      const configuredUrl = new URL(configuredBase);
      const currentHost = window.location.hostname;
      const configuredHost = configuredUrl.hostname;
      const isWaywardHost = currentHost === "usewayward.app" || currentHost === "www.usewayward.app";
      const isConfiguredWaywardHost = configuredHost === "usewayward.app" || configuredHost === "www.usewayward.app";
      if (configuredUrl.origin === window.location.origin || (isWaywardHost && isConfiguredWaywardHost)) {
        return path;
      }
    } catch {
      // Fall through to the configured API base below.
    }
  }

  return `${apiBase}${path}`;
}

type ScenicRegionApiResponse = ScenicRegionsResponse["regions"][number] & {
  scenicScore?: number;
  compositeScore?: number;
};

type ScenicRegionsApiResponse = Omit<ScenicRegionsResponse, "regions"> & {
  regions: ScenicRegionApiResponse[];
};

type RouteDetailApiResponse = Omit<RouteDetailResponse, "optionRevision"> & {
  optionRevision?: number;
};

export function normalizeRouteDetailResponse(payload: RouteDetailApiResponse): RouteDetailResponse {
  if (payload.optionRevision !== undefined) return payload as RouteDetailResponse;
  return { ...payload, optionRevision: 0 };
}

async function handleJson<T>(response: Response): Promise<T> {
  if (!response.ok) {
    const body = await response.text();
    throw new Error(`HTTP ${response.status}: ${body}`);
  }
  return (await response.json()) as T;
}

function sleep(ms: number, signal?: AbortSignal): Promise<void> {
  const promiseConstructor = Promise as PromiseConstructor & {
    withResolvers<T>(): {
      promise: Promise<T>;
      resolve: (value: T | PromiseLike<T>) => void;
      reject: (reason?: unknown) => void;
    };
  };
  const { promise, resolve, reject } = promiseConstructor.withResolvers<void>();
  const timer = setTimeout(() => {
    signal?.removeEventListener("abort", onAbort);
    resolve();
  }, ms);
  const onAbort = () => {
    clearTimeout(timer);
    signal?.removeEventListener("abort", onAbort);
    reject(signal?.reason ?? new DOMException("The operation was aborted.", "AbortError"));
  };
  signal?.addEventListener("abort", onAbort, { once: true });
  if (signal?.aborted) onAbort();
  return promise;
}

let fallbackAnalyticsClientId: string | null = null;

function randomAnalyticsClientId(): string {
  const cryptoApi = typeof window !== "undefined" ? window.crypto : undefined;
  if (cryptoApi?.randomUUID) {
    return cryptoApi.randomUUID();
  }
  return `anon-${Date.now()}-${Math.random().toString(16).slice(2)}`;
}

function getAnalyticsClientId(): string {
  if (typeof window === "undefined") {
    return "server";
  }

  try {
    const key = "wayward-anonymous-client-id";
    const existing = window.localStorage.getItem(key);
    if (existing) {
      return existing;
    }
    const generated = randomAnalyticsClientId();
    window.localStorage.setItem(key, generated);
    return generated;
  } catch {
    fallbackAnalyticsClientId ??= randomAnalyticsClientId();
    return fallbackAnalyticsClientId;
  }
}

export function coarseAnalyticsRegionKey(lat: number | null | undefined, lng: number | null | undefined): string | null {
  if (typeof lat !== "number" || typeof lng !== "number" || !Number.isFinite(lat) || !Number.isFinite(lng)) {
    return null;
  }
  const bucketSize = 0.5;
  const bucketLat = Math.floor(lat / bucketSize) * bucketSize;
  const bucketLng = Math.floor(lng / bucketSize) * bucketSize;
  return `grid:${bucketLat.toFixed(1)}:${bucketLng.toFixed(1)}`;
}

export function trackAnalyticsEvent(payload: AnalyticsEventPayload): void {
  if (typeof window === "undefined") return;

  const anonymousClientId = getAnalyticsClientId();
  const body = JSON.stringify({
    anonymousSessionId: anonymousClientId,
    anonymousClientId,
    ...payload
  });

  void fetch(apiUrl("/api/analytics/events"), {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body,
    keepalive: true
  }).catch(() => {
    // Analytics should never block route planning or navigation.
  });
}

export async function getAnalyticsSummary(days = 30): Promise<AnalyticsSummaryResponse> {
  const params = new URLSearchParams({ days: String(Math.max(1, Math.min(days, 90))) });
  const response = await fetch(apiUrl(`/api/analytics/summary?${params.toString()}`), { cache: "no-store" });
  return handleJson<AnalyticsSummaryResponse>(response);
}

export async function submitRoute(payload: RouteRequest, signal?: AbortSignal): Promise<RouteSubmissionResponse> {
  return withRequestDeadline(ROUTE_SUBMISSION_REQUEST_TIMEOUT_MS, signal, async (requestSignal) => {
    const response = await fetch(apiUrl("/api/routes"), {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(payload),
      signal: requestSignal
    });
    return handleJson<RouteSubmissionResponse>(response);
  });
}

export async function getJobStatus(jobId: string, signal?: AbortSignal): Promise<RouteJobStatusResponse> {
  return withRequestDeadline(STATUS_REQUEST_TIMEOUT_MS, signal, async (requestSignal) => {
    const response = await fetch(apiUrl(`/api/routes/${jobId}`), {
      cache: "no-store",
      signal: requestSignal
    });
    return handleJson<RouteJobStatusResponse>(response);
  });
}

export async function getPrimaryRoute(routeId: string, signal?: AbortSignal): Promise<PrimaryRouteResponse> {
  return withRequestDeadline(PRIMARY_ROUTE_REQUEST_TIMEOUT_MS, signal, async (requestSignal) => {
    const response = await fetch(apiUrl(`/api/routes/route/${routeId}/primary`), {
      cache: "no-store",
      signal: requestSignal
    });
    return handleJson<PrimaryRouteResponse>(response);
  });
}

export async function getRoute(routeId: string, signal?: AbortSignal): Promise<RouteDetailResponse> {
  const maxAttempts = 5;
  let lastError: Error | null = null;

  for (let attempt = 1; attempt <= maxAttempts; attempt++) {
    try {
      return await withRequestDeadline(ROUTE_DETAIL_REQUEST_TIMEOUT_MS, signal, async (requestSignal) => {
        const response = await fetch(apiUrl(`/api/routes/route/${routeId}`), {
          cache: "no-store",
          signal: requestSignal
        });
        const payload = await handleJson<RouteDetailApiResponse>(response);
        return normalizeRouteDetailResponse(payload);
      });
    } catch (error) {
      if (signal?.aborted) throw error;
      lastError = error as Error;
      if (attempt < maxAttempts) {
        await sleep(500 * attempt, signal);
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
  const url = apiUrl(`/api/scenic-regions?${params.toString()}`);
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

export async function getRouteByUrl(routeUrl: string): Promise<RouteDetailResponse> {
  const maxAttempts = 3;
  let lastError: Error | null = null;

  const targetUrl = (() => {
    try {
      if (routeUrl.startsWith("/api/")) return apiUrl(routeUrl);
      if (routeUrl.startsWith("/routes/")) return apiUrl(`/api${routeUrl}`);
      if (routeUrl.startsWith("http://") || routeUrl.startsWith("https://")) {
        const parsed = new URL(routeUrl);
        if (parsed.pathname.startsWith("/api/")) {
          return apiUrl(`${parsed.pathname}${parsed.search}`);
        }
        if (parsed.pathname.startsWith("/routes/")) {
          return apiUrl(`/api${parsed.pathname}${parsed.search}`);
        }
      }
    } catch {
      // Fall back to the original routeUrl below.
    }
    return routeUrl;
  })();

  for (let attempt = 1; attempt <= maxAttempts; attempt++) {
    try {
      const response = await fetch(targetUrl, { cache: "no-store" });
      const payload = await handleJson<RouteDetailApiResponse>(response);
      return normalizeRouteDetailResponse(payload);
    } catch (error) {
      lastError = error as Error;
      if (attempt < maxAttempts) {
        await sleep(300 * attempt);
      }
    }
  }

  throw lastError ?? new Error("Route fetch by URL failed after retries.");
}

export async function submitRouteRating(routeId: string, rating: number, feedbackTags: string[] = []): Promise<RouteRatingResponse> {
  const response = await fetch(apiUrl(`/api/routes/${routeId}/rating`), {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ rating, feedbackTags })
  });
  return handleJson<RouteRatingResponse>(response);
}

type NominatimSearchResult = {
  place_id: number;
  display_name: string;
  lat: string;
  lon: string;
};

export async function searchLocations(query: string, limit = 6): Promise<LocationSuggestion[]> {
  const trimmed = query.trim();
  if (trimmed.length < 2) {
    return [];
  }

  const params = new URLSearchParams({
    format: "jsonv2",
    q: trimmed,
    limit: String(Math.max(1, Math.min(limit, 10))),
    "accept-language": "en"
  });
  const response = await fetch(`https://nominatim.openstreetmap.org/search?${params.toString()}`, {
    headers: { Accept: "application/json" },
    cache: "no-store"
  });
  const payload = await handleJson<NominatimSearchResult[]>(response);
  return payload
    .map((candidate) => ({
      placeId: String(candidate.place_id),
      displayName: candidate.display_name,
      lat: Number(candidate.lat),
      lng: Number(candidate.lon)
    }))
    .filter((candidate) => Number.isFinite(candidate.lat) && Number.isFinite(candidate.lng));
}
