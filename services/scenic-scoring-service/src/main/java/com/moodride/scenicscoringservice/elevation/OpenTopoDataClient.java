package com.moodride.scenicscoringservice.elevation;

import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Component
public class OpenTopoDataClient {

    private final OpenTopoDataProperties properties;

    public OpenTopoDataClient(OpenTopoDataProperties properties) {
        this.properties = properties;
    }

    public List<Double> fetchElevations(List<LatLon> points) {
        List<Double> elevations = new ArrayList<>();
        if (points == null || points.isEmpty()) {
            return elevations;
        }

        if (!properties.isEnabled()) {
            return nullList(points.size());
        }

        RestTemplate restTemplate = createRestTemplate();

        int batchSize = Math.max(1, properties.getRequestBatchSize());
        for (int i = 0; i < points.size(); i += batchSize) {
            List<LatLon> chunk = points.subList(i, Math.min(i + batchSize, points.size()));
            elevations.addAll(fetchChunk(restTemplate, chunk));
        }

        return elevations;
    }

    public boolean isServiceReachable() {
        if (!properties.isEnabled()) {
            return false;
        }

        String url = UriComponentsBuilder.fromUriString(normalizeBaseUrl(properties.getBaseUrl()))
                .path("/health")
                .build(true)
                .toUriString();

        try {
            ResponseEntity<String> response = createRestTemplate().getForEntity(url, String.class);
            return response.getStatusCode().is2xxSuccessful();
        } catch (Exception ex) {
            return false;
        }
    }

    public boolean isDatasetAvailable() {
        if (!properties.isEnabled()) {
            return false;
        }

        String url = UriComponentsBuilder.fromUriString(normalizeBaseUrl(properties.getBaseUrl()))
                .path("/datasets")
                .build(true)
                .toUriString();

        try {
            ResponseEntity<String> response = createRestTemplate().getForEntity(url, String.class);
            if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
                return false;
            }
            return response.getBody().contains(properties.getDataset());
        } catch (Exception ex) {
            return false;
        }
    }

    private List<Double> fetchChunk(RestTemplate restTemplate, List<LatLon> chunk) {
        StringBuilder locations = new StringBuilder();
        for (int i = 0; i < chunk.size(); i++) {
            LatLon point = chunk.get(i);
            if (i > 0) {
                locations.append("|");
            }
            locations.append(point.latitude()).append(",").append(point.longitude());
        }

        String url = UriComponentsBuilder.fromUriString(normalizeBaseUrl(properties.getBaseUrl()))
                .pathSegment("v1", properties.getDataset())
                .queryParam("locations", locations.toString())
                .build(true)
                .toUriString();

        try {
            ResponseEntity<Map> response = restTemplate.getForEntity(url, Map.class);
            if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
                return nullList(chunk.size());
            }

            Object bodyResults = response.getBody().get("results");
            if (!(bodyResults instanceof List<?> responseList)) {
                return nullList(chunk.size());
            }

            List<Double> result = new ArrayList<>();
            for (Object item : responseList) {
                if (item instanceof Map<?, ?> row && row.get("elevation") instanceof Number number) {
                    result.add(number.doubleValue());
                } else {
                    result.add(null);
                }
            }

            while (result.size() < chunk.size()) {
                result.add(null);
            }
            return result;
        } catch (Exception ex) {
            return nullList(chunk.size());
        }
    }

    private RestTemplate createRestTemplate() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(properties.getConnectTimeoutMs());
        factory.setReadTimeout(properties.getReadTimeoutMs());
        return new RestTemplate(factory);
    }

    private String normalizeBaseUrl(String baseUrl) {
        if (baseUrl.endsWith("/")) {
            return baseUrl.substring(0, baseUrl.length() - 1);
        }
        return baseUrl;
    }

    private List<Double> nullList(int size) {
        List<Double> values = new ArrayList<>();
        for (int i = 0; i < size; i++) {
            values.add(null);
        }
        return values;
    }

    public record LatLon(double latitude, double longitude) {
    }
}

