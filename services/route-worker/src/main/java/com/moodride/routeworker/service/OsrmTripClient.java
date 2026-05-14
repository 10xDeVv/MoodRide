package com.moodride.routeworker.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.moodride.datamodels.RouteMode;
import com.moodride.routeworker.config.OsrmConfiguration;
import com.moodride.routeworker.graph.RoadNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.StringJoiner;

@Service
public class OsrmTripClient {

    private static final Logger logger = LoggerFactory.getLogger(OsrmTripClient.class);

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final OsrmConfiguration osrmConfiguration;

    public OsrmTripClient(ObjectMapper objectMapper, OsrmConfiguration osrmConfiguration) {
        this.objectMapper = objectMapper;
        this.osrmConfiguration = osrmConfiguration;
        this.httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofMillis(Math.max(1000, osrmConfiguration.getRequestTimeoutMs())))
            .build();
    }

    public Optional<TripResult> requestRoundTrip(List<RoadNode> waypoints) {
        return requestRoundTrip(waypoints, RouteMode.DRIVE);
    }

    public Optional<TripResult> requestRoundTrip(List<RoadNode> waypoints, RouteMode routeMode) {
        if (waypoints == null || waypoints.size() < 2) {
            return Optional.empty();
        }

        try {
            StringJoiner coordinates = new StringJoiner(";");
            for (RoadNode waypoint : waypoints) {
                coordinates.add(waypoint.getLongitude() + "," + waypoint.getLatitude());
            }

            String baseUrl = trimTrailingSlash(osrmConfiguration.getBaseUrl());
            String query = "?roundtrip=true&source=first&overview=full&geometries=polyline";
            URI uri = URI.create(baseUrl + "/trip/v1/" + resolveProfile(routeMode) + "/" + coordinates + query);

            HttpRequest request = HttpRequest.newBuilder(uri)
                .GET()
                .timeout(Duration.ofMillis(Math.max(1000, osrmConfiguration.getRequestTimeoutMs())))
                .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                logger.warn("OSRM trip request returned non-200 status: {}", response.statusCode());
                return Optional.empty();
            }

            JsonNode body = objectMapper.readTree(response.body());
            if (!"Ok".equalsIgnoreCase(body.path("code").asText())) {
                logger.warn("OSRM trip request returned non-ok code: {}", body.path("code").asText());
                return Optional.empty();
            }

            JsonNode trips = body.path("trips");
            if (!trips.isArray() || trips.isEmpty()) {
                logger.warn("OSRM trip request returned no trips");
                return Optional.empty();
            }

            JsonNode trip = trips.get(0);
            String polyline = trip.path("geometry").asText(null);
            if (polyline == null || polyline.isBlank()) {
                logger.warn("OSRM trip response did not include a geometry");
                return Optional.empty();
            }

            List<RoadNode> decodedPath = decodePolyline(polyline);
            if (decodedPath.size() < 2) {
                logger.warn("OSRM trip response geometry had fewer than 2 decoded points");
                return Optional.empty();
            }

            double distanceKm = Math.max(0.0, trip.path("distance").asDouble(0.0) / 1000.0);
            int durationMinutes = (int) Math.round(Math.max(0.0, trip.path("duration").asDouble(0.0) / 60.0));

            return Optional.of(new TripResult(List.copyOf(decodedPath), distanceKm, durationMinutes));
        } catch (Exception ex) {
            if (ex instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            logger.warn("OSRM trip request failed: {}", ex.getMessage());
            return Optional.empty();
        }
    }

    private String resolveProfile(RouteMode routeMode) {
        return (routeMode == null ? RouteMode.DRIVE : routeMode).osrmProfile();
    }

    private String trimTrailingSlash(String url) {
        if (url == null || url.isBlank()) {
            return "http://localhost:5000";
        }
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    // OSRM's default polyline encoding uses precision=5.
    private List<RoadNode> decodePolyline(String encoded) {
        List<RoadNode> points = new ArrayList<>();
        int index = 0;
        int lat = 0;
        int lng = 0;

        while (index < encoded.length()) {
            int b;
            int shift = 0;
            int result = 0;
            do {
                b = encoded.charAt(index++) - 63;
                result |= (b & 0x1F) << shift;
                shift += 5;
            } while (b >= 0x20 && index < encoded.length());
            int deltaLat = ((result & 1) != 0) ? ~(result >> 1) : (result >> 1);
            lat += deltaLat;

            shift = 0;
            result = 0;
            do {
                b = encoded.charAt(index++) - 63;
                result |= (b & 0x1F) << shift;
                shift += 5;
            } while (b >= 0x20 && index < encoded.length());
            int deltaLng = ((result & 1) != 0) ? ~(result >> 1) : (result >> 1);
            lng += deltaLng;

            points.add(new RoadNode(lat / 100000.0, lng / 100000.0));
        }

        return points;
    }

    public record TripResult(List<RoadNode> path, double totalDistanceKm, int durationMinutes) {
    }
}
