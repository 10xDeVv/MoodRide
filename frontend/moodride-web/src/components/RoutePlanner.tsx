"use client";

import { useEffect, useMemo, useRef, useState, type CSSProperties } from "react";
import { getJobStatus, getRoute, getScenicRegions, searchLocations, submitRoute, submitRouteRating } from "@/lib/api";
import { connectJobChannel } from "@/lib/ws";
import type {
  JobSocketEvent,
  LocationSuggestion,
  RouteDetailResponse,
  RouteJobStatusResponse,
  RouteOptionResponse,
  RouteSubmissionResponse,
  ScenicRegionsResponse,
  Vibe
} from "@/lib/types";
import { RouteMap } from "@/components/RouteMap";
import { ScenicHighlightsPanel } from "@/components/ScenicHighlightsPanel";

const VIBES: Vibe[] = ["coastal", "mountain", "countryside", "riverside", "forest", "open_roads"];
const TIME_BUDGET_OPTIONS = [30, 60, 90, 120] as const;
const PROFILE_DISPLAY_NAMES: Record<string, string> = {
  most_scenic: "Most Scenic",
  balanced: "Balanced",
  shorter: "Shorter"
};
const VIBE_DISPLAY_NAMES: Record<string, string> = {
  coastal: "Coastal",
  mountain: "Mountain",
  countryside: "Countryside",
  riverside: "Riverside",
  forest: "Forest",
  open_roads: "Open Roads"
};
const IOS_DEVICE_REGEX = /iPad|iPhone|iPod/;

type Coordinate = {
  lat: number;
  lng: number;
};

function sampleWaypoints(coordinates: [number, number][], maxPoints: number): Coordinate[] {
  if (!Array.isArray(coordinates) || coordinates.length === 0 || maxPoints <= 0) {
    return [];
  }

  if (coordinates.length <= maxPoints) {
    return coordinates.map(([lng, lat]) => ({ lat, lng }));
  }

  const sampled: Coordinate[] = [];
  const step = (coordinates.length - 1) / (maxPoints - 1);
  for (let i = 0; i < maxPoints; i++) {
    const index = Math.round(i * step);
    const [lng, lat] = coordinates[Math.min(index, coordinates.length - 1)];
    sampled.push({ lat, lng });
  }

  return sampled.filter((point, index, list) => {
    if (index === 0) {
      return true;
    }
    const previous = list[index - 1];
    return previous.lat !== point.lat || previous.lng !== point.lng;
  });
}

function formatCoordinate(point: Coordinate): string {
  return `${point.lat},${point.lng}`;
}

function buildGoogleMapsUrl(points: Coordinate[]): string {
  const origin = points[0];
  const destination = points[points.length - 1];
  const waypoints = points.slice(1, -1).map(formatCoordinate).join("|");

  const url = new URL("https://www.google.com/maps/dir/");
  url.searchParams.set("api", "1");
  url.searchParams.set("origin", formatCoordinate(origin));
  url.searchParams.set("destination", formatCoordinate(destination));
  if (waypoints) {
    url.searchParams.set("waypoints", waypoints);
  }
  url.searchParams.set("travelmode", "driving");
  return url.toString();
}

function buildAppleMapsUrl(points: Coordinate[]): string {
  const origin = points[0];
  const destinations = points.slice(1).map(formatCoordinate);

  const url = new URL("https://maps.apple.com/");
  url.searchParams.set("saddr", formatCoordinate(origin));
  if (destinations.length > 0) {
    url.searchParams.set("daddr", destinations.join("+to:"));
  }
  return url.toString();
}

function sanitizeFileName(value: string): string {
  const fallback = "moodride_route";
  const trimmed = value.trim();
  if (!trimmed) {
    return fallback;
  }
  return trimmed.replace(/[^a-z0-9-_]+/gi, "_").replace(/_+/g, "_");
}

function escapeXml(value: string): string {
  return value
    .replace(/&/g, "&amp;")
    .replace(/</g, "&lt;")
    .replace(/>/g, "&gt;")
    .replace(/"/g, "&quot;")
    .replace(/'/g, "&apos;");
}

function exportRouteAsGpx(route: RouteDetailResponse, routeName: string) {
  const points = route.geometry.geometry.coordinates
    .map(([lng, lat]) => `      <trkpt lat="${lat}" lon="${lng}" />`)
    .join("\n");

  const gpx = `<?xml version="1.0" encoding="UTF-8"?>
<gpx version="1.1" creator="MoodRide" xmlns="http://www.topografix.com/GPX/1/1">
  <trk>
    <name>${escapeXml(routeName)}</name>
    <trkseg>
${points}
    </trkseg>
  </trk>
</gpx>`;

  const blob = new Blob([gpx], { type: "application/gpx+xml" });
  const downloadUrl = URL.createObjectURL(blob);
  const anchor = document.createElement("a");
  anchor.href = downloadUrl;
  anchor.download = `${sanitizeFileName(routeName)}.gpx`;
  anchor.click();
  URL.revokeObjectURL(downloadUrl);
}

function requestBrowserLocation(
  onSuccess: (latitude: number, longitude: number) => void,
  onError: (message: string) => void
) {
  if (!navigator.geolocation) {
    onError("Geolocation is unavailable in this browser.");
    return;
  }

  navigator.geolocation.getCurrentPosition(
    (position) => {
      onSuccess(Number(position.coords.latitude.toFixed(5)), Number(position.coords.longitude.toFixed(5)));
    },
    (error) => {
      onError(`Unable to fetch geolocation: ${error.message}`);
    },
    { enableHighAccuracy: true, timeout: 8000 }
  );
}

type JobPhase = "idle" | "submitting" | "tracking" | "completed" | "failed";

function staggerStyle(index: number): CSSProperties {
  return { ["--stagger-index" as const]: index } as CSSProperties;
}

export function RoutePlanner() {
  const [lat, setLat] = useState(45.52);
  const [lng, setLng] = useState(-122.68);
  const [locationQuery, setLocationQuery] = useState("");
  const [locationSuggestions, setLocationSuggestions] = useState<LocationSuggestion[]>([]);
  const [locationLookupPending, setLocationLookupPending] = useState(false);
  const [locationLookupError, setLocationLookupError] = useState<string | null>(null);
  const [locationDropdownVisible, setLocationDropdownVisible] = useState(false);
  const [timeBudgetMinutes, setTimeBudgetMinutes] = useState(60);
  const [vibes, setVibes] = useState<string[]>(["countryside"]);
  const [submission, setSubmission] = useState<RouteSubmissionResponse | null>(null);
  const [jobStatus, setJobStatus] = useState<RouteJobStatusResponse | null>(null);
  const [route, setRoute] = useState<RouteDetailResponse | null>(null);
  const [scenicRegions, setScenicRegions] = useState<ScenicRegionsResponse | null>(null);
  const [phase, setPhase] = useState<JobPhase>("idle");
  const [message, setMessage] = useState<string>("");
  const [pollingEnabled, setPollingEnabled] = useState(false);
  const [selectedRating, setSelectedRating] = useState<number | null>(null);
  const [ratingSubmitted, setRatingSubmitted] = useState(false);
  const [showDebug, setShowDebug] = useState(false);

  const stopWsRef = useRef<null | (() => void)>(null);
  const pollTimerRef = useRef<ReturnType<typeof setInterval> | null>(null);
  const routeDetailsRef = useRef<Record<string, RouteDetailResponse>>({});
  const locationLookupSequenceRef = useRef(0);

  const formatNumber = (value: number | null | undefined, digits = 2) =>
    typeof value === "number" && Number.isFinite(value) ? value.toFixed(digits) : "N/A";
  const regions = scenicRegions?.regions ?? [];

  const canSubmit = useMemo(
    () => vibes.length > 0 && vibes.length <= 3 && phase !== "submitting" && phase !== "tracking",
    [phase, vibes.length]
  );

  const formatRouteProfile = (profile: string) => {
    if (PROFILE_DISPLAY_NAMES[profile]) {
      return PROFILE_DISPLAY_NAMES[profile];
    }

    return profile
      .split("_")
      .filter((segment) => segment.length > 0)
      .map((segment) => segment[0].toUpperCase() + segment.slice(1))
      .join(" ");
  };

  const formatVibe = (vibe: string) => {
    if (VIBE_DISPLAY_NAMES[vibe]) {
      return VIBE_DISPLAY_NAMES[vibe];
    }

    return vibe
      .split("_")
      .filter((segment) => segment.length > 0)
      .map((segment) => segment[0].toUpperCase() + segment.slice(1))
      .join(" ");
  };

  const getPrimaryRouteId = (status: RouteJobStatusResponse): string | null => {
    if (status.routeId) {
      return status.routeId;
    }

    if (!Array.isArray(status.routeOptions) || status.routeOptions.length === 0) {
      return null;
    }

    const mostScenic = status.routeOptions.find((option) => option.profile === "most_scenic");
    return mostScenic?.routeId ?? status.routeOptions[0].routeId;
  };

  const setActiveRoute = (detail: RouteDetailResponse) => {
    routeDetailsRef.current[detail.routeId] = detail;
    setRoute(detail);
  };

  const loadRouteDetail = async (routeId: string) => {
    const cached = routeDetailsRef.current[routeId];
    if (cached) {
      return cached;
    }

    const detail = await getRoute(routeId);
    routeDetailsRef.current[detail.routeId] = detail;
    return detail;
  };

  const routeOptions = Array.isArray(route?.routeOptions) ? route.routeOptions : [];

  useEffect(() => {
    if (!route) {
      setSelectedRating(null);
      setRatingSubmitted(false);
      return;
    }

    setSelectedRating(route.userRating);
    setRatingSubmitted(Boolean(route.ratedAt));
  }, [route]);

  useEffect(() => {
    requestBrowserLocation(
      (latitude, longitude) => {
        setLat(latitude);
        setLng(longitude);
        setLocationQuery((current) => current || `Current location (${latitude.toFixed(5)}, ${longitude.toFixed(5)})`);
        setMessage("Location acquired from browser geolocation.");
      },
      (locationMessage) => {
        setMessage(locationMessage);
      }
    );

    return () => {
      if (stopWsRef.current) {
        stopWsRef.current();
      }
      if (pollTimerRef.current) {
        clearInterval(pollTimerRef.current);
      }
    };
  }, []);

  useEffect(() => {
    const query = locationQuery.trim();
    if (!locationDropdownVisible || query.length < 2) {
      setLocationSuggestions([]);
      setLocationLookupPending(false);
      setLocationLookupError(null);
      return;
    }

    const currentSequence = ++locationLookupSequenceRef.current;
    const timer = setTimeout(async () => {
      setLocationLookupPending(true);
      setLocationLookupError(null);
      try {
        const suggestions = await searchLocations(query);
        if (currentSequence !== locationLookupSequenceRef.current) {
          return;
        }
        setLocationSuggestions(suggestions);
      } catch {
        if (currentSequence !== locationLookupSequenceRef.current) {
          return;
        }
        setLocationLookupError("Location lookup unavailable right now.");
        setLocationSuggestions([]);
      } finally {
        if (currentSequence === locationLookupSequenceRef.current) {
          setLocationLookupPending(false);
        }
      }
    }, 320);

    return () => clearTimeout(timer);
  }, [locationQuery, locationDropdownVisible]);

  const toggleVibe = (vibe: Vibe) => {
    setVibes((current) => {
      if (current.includes(vibe)) {
        return current.filter((v) => v !== vibe);
      }
      if (current.length >= 3) {
        return current;
      }
      return [...current, vibe];
    });
  };

  const useCurrentLocation = () => {
    requestBrowserLocation(
      (latitude, longitude) => {
        setLat(latitude);
        setLng(longitude);
        setLocationQuery(`Current location (${latitude.toFixed(5)}, ${longitude.toFixed(5)})`);
        setLocationDropdownVisible(false);
        setLocationSuggestions([]);
        setMessage("Location acquired from browser geolocation.");
      },
      (locationMessage) => {
        setMessage(locationMessage);
      }
    );
  };

  const applyLocationSuggestion = (suggestion: LocationSuggestion) => {
    const nextLat = Number(suggestion.lat.toFixed(5));
    const nextLng = Number(suggestion.lng.toFixed(5));
    setLat(nextLat);
    setLng(nextLng);
    setLocationQuery(suggestion.displayName);
    setLocationDropdownVisible(false);
    setLocationSuggestions([]);
    setLocationLookupError(null);
    setMessage(`Location set to ${suggestion.displayName}.`);
  };

  const refreshScenicRegions = async () => {
    try {
      const response = await getScenicRegions(lat, lng, 50, 12, vibes[0]);
      setScenicRegions(response);
    } catch (error) {
      setMessage(`Scenic region fetch failed: ${(error as Error).message}`);
    }
  };

  const stopAsyncTracking = () => {
    if (stopWsRef.current) {
      stopWsRef.current();
      stopWsRef.current = null;
    }
    if (pollTimerRef.current) {
      clearInterval(pollTimerRef.current);
      pollTimerRef.current = null;
    }
    setPollingEnabled(false);
  };

  const onSocketEvent = async (event: JobSocketEvent) => {
    if (!event.jobId || !submission || event.jobId !== submission.jobId) {
      return;
    }

    if (event.routeId) {
      const detail = await loadRouteDetail(event.routeId);
      setActiveRoute(detail);
      setPhase("completed");
      setMessage("Route is ready.");
      stopAsyncTracking();
      return;
    }

    if (event.reason) {
      setPhase("failed");
      setMessage(`Route generation failed: ${event.reason}`);
      stopAsyncTracking();
    }
  };

  const startPolling = (jobId: string) => {
    setPollingEnabled(true);

    const tick = async () => {
      try {
        const status = await getJobStatus(jobId);
        setJobStatus(status);

        if (status.status === "COMPLETED") {
          const primaryRouteId = getPrimaryRouteId(status);
          if (primaryRouteId) {
            const detail = await loadRouteDetail(primaryRouteId);
            setActiveRoute(detail);
            setPhase("completed");
            setMessage("Route is ready.");
            stopAsyncTracking();
          }
        } else if (["FAILED", "TIMEOUT"].includes(status.status)) {
          setPhase("failed");
          setMessage(`Route job ended with status ${status.status}: ${status.reason ?? "no reason"}`);
          stopAsyncTracking();
        }
      } catch (error) {
        setMessage(`Polling status failed: ${(error as Error).message}`);
      }
    };

    void tick();
    pollTimerRef.current = setInterval(() => void tick(), 2500);
  };

  const submit = async () => {
    setPhase("submitting");
    setMessage("");
    setSubmission(null);
    setJobStatus(null);
    setRoute(null);
    routeDetailsRef.current = {};

    try {
      await refreshScenicRegions();

      const response = await submitRoute({
        userId: crypto.randomUUID(),
        lat,
        lng,
        timeBudgetMinutes,
        vibes,
        preferenceVector: { avoidTolls: false }
      });

      setSubmission(response);
      setPhase("tracking");
      setMessage("Route submitted.");

      stopWsRef.current = connectJobChannel(
        response.jobId,
        response.wsChannel,
        (event) => {
          void onSocketEvent(event);
        },
        () => {
          setMessage("Live updates disconnected. Continuing with status checks.");
          if (!pollingEnabled) {
            startPolling(response.jobId);
          }
        }
      );

      startPolling(response.jobId);
    } catch (error) {
      setPhase("failed");
      setMessage(`Route submission failed: ${(error as Error).message}`);
      stopAsyncTracking();
    }
  };

  const regenerateRoute = () => {
    void submit();
  };

  const selectRouteOption = async (option: RouteOptionResponse) => {
    if (route?.routeId === option.routeId) {
      return;
    }

    try {
      const detail = await loadRouteDetail(option.routeId);
      setActiveRoute(detail);
      setMessage(`Showing ${formatRouteProfile(option.profile)} route option.`);
    } catch (error) {
      setMessage(`Failed to load ${formatRouteProfile(option.profile)} option: ${(error as Error).message}`);
    }
  };

  const startDrive = () => {
    if (!route) {
      return;
    }

    const points = route.geometry.geometry.coordinates;
    if (points.length < 2) {
      setMessage("Route geometry is unavailable for navigation handoff.");
      return;
    }

    const sampledPoints = sampleWaypoints(points, 15);
    if (sampledPoints.length < 2) {
      setMessage("Not enough route points to launch navigation.");
      return;
    }

    const isIos = IOS_DEVICE_REGEX.test(navigator.userAgent);
    const navigationUrl = isIos ? buildAppleMapsUrl(sampledPoints) : buildGoogleMapsUrl(sampledPoints);
    window.open(navigationUrl, "_blank", "noopener,noreferrer");
    setMessage(isIos ? "Opening Apple Maps." : "Opening Google Maps.");
  };

  const exportGpx = () => {
    if (!route) {
      return;
    }

    const optionProfile = routeOptions.find((option) => option.routeId === route.routeId)?.profile;
    const routeName = optionProfile ? `${formatRouteProfile(optionProfile)} Loop` : "MoodRide Scenic Loop";
    exportRouteAsGpx(route, routeName);
    setMessage("GPX export started.");
  };

  const submitRating = async () => {
    if (!route || selectedRating == null) {
      return;
    }

    try {
      const response = await submitRouteRating(route.routeId, selectedRating);
      setRoute({
        ...route,
        userRating: response.rating,
        ratedAt: response.ratedAt
      });
      setRatingSubmitted(true);
      setMessage("Rating saved and user feedback events published.");
    } catch (error) {
      setMessage(`Rating submit failed: ${(error as Error).message}`);
    }
  };

  const activeRouteProfile = routeOptions.find((option) => option.routeId === route?.routeId)?.profile;
  const activeProfileLabel = activeRouteProfile ? formatRouteProfile(activeRouteProfile) : "Route";
  const submitLabel = phase === "submitting" || phase === "tracking" ? "Generating Route..." : "Submit Route";

  return (
    <main className="planner-page">
      <section className="panel panel-stagger planner-hero" style={staggerStyle(0)}>
        <span className="planner-eyebrow">Scenic Intelligence</span>
        <h1 className="planner-title">MoodRide Scenic Planner</h1>
        <p className="planner-subtitle">
          Build a loop route around your location, compare options instantly, then launch navigation or export GPX without extra setup.
        </p>
        <div className="hero-stats">
          <div className="hero-stat">
            <p className="hero-stat-label">Phase</p>
            <p className="hero-stat-value">{phase}</p>
          </div>
          <div className="hero-stat">
            <p className="hero-stat-label">Selected Vibes</p>
            <p className="hero-stat-value">{vibes.length}/3</p>
          </div>
          <div className="hero-stat">
            <p className="hero-stat-label">Current Route</p>
            <p className="hero-stat-value">{route ? activeProfileLabel : "Not Ready"}</p>
          </div>
        </div>
      </section>
      <div className="grid grid-2">
        <section className="panel panel-stagger" style={staggerStyle(1)}>
          <div className="panel-title-row">
            <h2>Route Request</h2>
            <span className="small">Choose point, budget, and vibe blend.</span>
          </div>

          <label htmlFor="location-search">Search Place</label>
          <div className="location-search-shell">
            <input
              id="location-search"
              type="text"
              value={locationQuery}
              onChange={(e) => {
                setLocationQuery(e.target.value);
                setLocationDropdownVisible(true);
              }}
              onFocus={() => setLocationDropdownVisible(true)}
              onBlur={() => {
                setTimeout(() => setLocationDropdownVisible(false), 120);
              }}
              onKeyDown={(event) => {
                if (event.key === "Enter" && locationSuggestions.length > 0) {
                  event.preventDefault();
                  applyLocationSuggestion(locationSuggestions[0]);
                }
              }}
              placeholder="Search city, address, or landmark"
              autoComplete="off"
            />
            {locationDropdownVisible && (locationLookupPending || locationSuggestions.length > 0 || locationLookupError) && (
              <div className="location-search-results">
                {locationLookupPending && <p className="small">Searching locations...</p>}
                {!locationLookupPending && locationLookupError && <p className="small error">{locationLookupError}</p>}
                {!locationLookupPending && !locationLookupError && locationSuggestions.length === 0 && (
                  <p className="small">No matches yet. Keep typing.</p>
                )}
                {locationSuggestions.map((suggestion) => (
                  <button
                    key={suggestion.placeId}
                    type="button"
                    className="location-suggestion-btn"
                    onClick={() => applyLocationSuggestion(suggestion)}
                  >
                    {suggestion.displayName}
                  </button>
                ))}
              </div>
            )}
          </div>

          <label htmlFor="lat">Latitude</label>
          <input id="lat" type="number" value={lat} onChange={(e) => setLat(Number(e.target.value))} step="0.00001" />

          <label htmlFor="lng">Longitude</label>
          <input id="lng" type="number" value={lng} onChange={(e) => setLng(Number(e.target.value))} step="0.00001" />

          <button type="button" onClick={useCurrentLocation} className="location-btn">
            Use Current Location
          </button>

          <label htmlFor="budget">Time Budget</label>
          <select id="budget" value={timeBudgetMinutes} onChange={(e) => setTimeBudgetMinutes(Number(e.target.value))}>
            {TIME_BUDGET_OPTIONS.map((value) => (
              <option key={value} value={value}>
                {value} minutes
              </option>
            ))}
          </select>

          <label>Vibes (max 3)</label>
          <div className="tag-list">
            {VIBES.map((vibe) => {
              const active = vibes.includes(vibe);
              return (
                <button
                  type="button"
                  className={`tag ${active ? "active" : ""}`}
                  key={vibe}
                  onClick={() => toggleVibe(vibe)}
                >
                  {formatVibe(vibe)}
                </button>
              );
            })}
          </div>

          <button type="button" onClick={() => void submit()} disabled={!canSubmit}>
            {submitLabel}
          </button>

          {route && phase === "completed" && (
            <div className="actions-row">
              <button type="button" className="secondary-btn" onClick={regenerateRoute}>
                Regenerate
              </button>
              <button type="button" className="primary-drive-btn" onClick={startDrive}>
                Start Drive
              </button>
              <button type="button" className="secondary-btn" onClick={exportGpx}>
                Export GPX
              </button>
            </div>
          )}

          <div className="status-row">
            <span className={`status-pill status-${phase}`}>{phase}</span>
            <span className="small">
              {route ? `${route.geometry.geometry.coordinates.length} geometry points loaded` : "Submit a request to generate a route."}
            </span>
          </div>
          {message && <div className={`message-banner ${phase === "failed" ? "error" : ""}`}>{message}</div>}

          {showDebug && submission && (
            <div className="small">
              <p>Job ID: {submission.jobId}</p>
              <p>Estimated completion: {submission.estimatedCompletionSeconds}s</p>
              <p>WS Channel: {submission.wsChannel}</p>
            </div>
          )}

          {showDebug && jobStatus && (
            <div className="small">
              <p>Backend status: {jobStatus.status}</p>
              <p>
                Retry: {jobStatus.retryCount}/{jobStatus.maxRetries}
              </p>
              {jobStatus.estimatedRemainingSeconds !== null && <p>ETA: {jobStatus.estimatedRemainingSeconds}s</p>}
            </div>
          )}
        </section>

        <section className="grid">
          <div className="panel panel-stagger" style={staggerStyle(2)}>
            <div className="panel-title-row">
              <h2>Route Map</h2>
              {route && <span className="small">{activeProfileLabel}</span>}
            </div>
            <RouteMap route={route} />
          </div>

          <div className="panel panel-stagger" style={staggerStyle(3)}>
            <div className="panel-title-row">
              <h2>Route Details</h2>
              {route && <span className="small">{activeProfileLabel}</span>}
            </div>
            {!route && <p className="small">No completed route yet.</p>}
            {route && (
              <div className="route-detail-surface" key={route.routeId}>
                {routeOptions.length > 0 && (
                  <div className="route-options">
                    <p className="small">Choose route option</p>
                    <div className="route-options-list">
                      {routeOptions.map((option) => {
                        const active = option.routeId === route.routeId;
                        return (
                          <button
                            type="button"
                            key={option.routeId}
                            className={`route-option ${active ? "active" : ""}`}
                            onClick={() => void selectRouteOption(option)}
                          >
                            <span className="route-option-title">{formatRouteProfile(option.profile)}</span>
                            <span className="small">
                              {formatNumber(option.totalDistanceKm, 1)} km · {option.estimatedDurationMinutes} min ·
                              score {formatNumber(option.scenicScore, 2)}
                            </span>
                          </button>
                        );
                      })}
                    </div>
                  </div>
                )}
                <div className="detail-metrics">
                  <div className="detail-metric">
                    <p className="detail-metric-label">Scenic Score</p>
                    <p className="detail-metric-value">{formatNumber(route.scenicScore, 2)}</p>
                  </div>
                  <div className="detail-metric">
                    <p className="detail-metric-label">Distance</p>
                    <p className="detail-metric-value">{formatNumber(route.totalDistanceKm, 1)} km</p>
                  </div>
                  <div className="detail-metric">
                    <p className="detail-metric-label">Duration</p>
                    <p className="detail-metric-value">{route.estimatedDurationMinutes} min</p>
                  </div>
                </div>
                {showDebug && <p className="small">Algorithm: {route.algorithmVersion}</p>}
              </div>
            )}
          </div>

          <div className="panel panel-stagger" style={staggerStyle(4)}>
            <div className="panel-title-row">
              <h2>Scenic Highlights</h2>
            </div>
            <ScenicHighlightsPanel route={route} />
          </div>

          {route && phase === "completed" && (
            <div className="panel panel-stagger" style={staggerStyle(5)}>
              <div className="panel-title-row">
                <h2>Rate This Drive</h2>
              </div>
              <p className="small">How was your drive? (1-5 stars)</p>
              <div className="rating-row" role="group" aria-label="Drive rating">
                {[1, 2, 3, 4, 5].map((ratingValue) => (
                  <button
                    type="button"
                    key={ratingValue}
                    className={`rating-star ${selectedRating === ratingValue ? "active" : ""}`}
                    onClick={() => setSelectedRating(ratingValue)}
                  >
                    {ratingValue}
                  </button>
                ))}
              </div>
              <button type="button" onClick={() => void submitRating()} disabled={selectedRating == null || ratingSubmitted}>
                {ratingSubmitted ? "Rating Saved" : "Submit Rating"}
              </button>
            </div>
          )}

          {showDebug && (
            <div className="panel panel-stagger" style={staggerStyle(6)}>
              <div className="panel-title-row">
                <h2>Nearby Scenic Regions</h2>
              </div>
              {!regions.length && <p className="small">No region data loaded yet.</p>}
              {regions.length > 0 && (
                <>
                  <p className="small">
                    Showing {regions.length} of {scenicRegions?.totalRegions ?? regions.length} scenic regions.
                  </p>
                  {scenicRegions?.boundingBox && (
                    <p className="small">
                      Bounds: N {formatNumber(scenicRegions.boundingBox.north, 4)}, S {formatNumber(scenicRegions.boundingBox.south, 4)}, E{" "}
                      {formatNumber(scenicRegions.boundingBox.east, 4)}, W {formatNumber(scenicRegions.boundingBox.west, 4)}
                    </p>
                  )}
                  <ul>
                    {regions.map((region) => (
                      <li key={region.h3Index}>
                        {region.h3Index} - score {formatNumber(region.scenicScore, 2)} [{region.dominantFeature}, confidence{" "}
                        {formatNumber(region.confidence, 2)}] ({formatNumber(region.centerLat, 4)}, {formatNumber(region.centerLng, 4)})
                      </li>
                    ))}
                  </ul>
                </>
              )}
            </div>
          )}

          <button type="button" className="debug-toggle" onClick={() => setShowDebug((current) => !current)}>
            {showDebug ? "Hide Debug" : "Debug"}
          </button>
        </section>
      </div>
    </main>
  );
}


