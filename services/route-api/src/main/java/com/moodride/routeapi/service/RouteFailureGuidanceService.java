package com.moodride.routeapi.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.moodride.datamodels.RouteJob;
import com.moodride.geo.VibeCatalog;
import org.springframework.stereotype.Service;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

@Service
public class RouteFailureGuidanceService {

    private final ObjectMapper objectMapper;

    public RouteFailureGuidanceService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper.copy().findAndRegisterModules();
    }

    public RouteFailureGuidance buildFailureGuidance(RouteJob job) {
        if (job == null || job.getStatus() != RouteJob.JobStatus.FAILED) {
            return RouteFailureGuidance.empty();
        }

        String reason = job.getFailureReason();
        if (reason == null || reason.isBlank()) {
            return new RouteFailureGuidance(
                "route_generation_failed",
                "Route generation failed. Try a different starting point, more time, or another vibe.",
                List.of("scenic", "open_roads"),
                List.of("Try Scenic", "Try Open Roads", "Increase time budget")
            );
        }

        String normalized = reason.toLowerCase(Locale.ROOT);
        if (normalized.contains("no strong ") || normalized.contains("no feasible route found")) {
            return new RouteFailureGuidance(
                "vibe_unavailable",
                reason,
                suggestedFallbackVibes(job),
                suggestedFailureActions(job)
            );
        }

        return new RouteFailureGuidance(
            "route_generation_failed",
            reason,
            List.of("scenic", "open_roads"),
            List.of("Try Scenic", "Try Open Roads", "Increase time budget")
        );
    }

    private List<String> suggestedFallbackVibes(RouteJob job) {
        List<String> routeVibes = resolveRouteVibes(job);
        Set<String> suggestions = new LinkedHashSet<>();
        if (routeVibes.stream().anyMatch(vibe -> vibe.equals("countryside") || vibe.equals("sunday_cruise"))) {
            suggestions.add("scenic");
            suggestions.add("open_roads");
            suggestions.add("relaxing");
        } else if (routeVibes.contains("mountain")) {
            suggestions.add("scenic");
            suggestions.add("winding_roads");
            suggestions.add("open_roads");
        } else {
            suggestions.add("scenic");
            suggestions.add("open_roads");
            suggestions.add("relaxing");
        }
        suggestions.removeAll(routeVibes);
        return List.copyOf(suggestions);
    }

    private List<String> suggestedFailureActions(RouteJob job) {
        int currentBudget = job == null ? 60 : job.getTimeBudgetMinutes();
        int nextBudget = currentBudget < 60 ? 60 : currentBudget < 90 ? 90 : currentBudget < 120 ? 120 : currentBudget + 30;
        return List.of(
            "Try Scenic",
            "Try Open Roads",
            "Increase time budget to " + nextBudget + " minutes",
            "Move the start point farther from downtown"
        );
    }

    private List<String> resolveRouteVibes(RouteJob job) {
        if (job == null) {
            return List.of(VibeCatalog.defaultVibe());
        }
        List<String> parsed = parseVibesJson(job.getVibesJson());
        if (!parsed.isEmpty()) {
            return parsed;
        }
        String normalized = VibeCatalog.normalize(job.getVibe());
        return List.of(normalized.isBlank() ? VibeCatalog.defaultVibe() : normalized);
    }

    private List<String> parseVibesJson(String rawVibesJson) {
        if (rawVibesJson == null || rawVibesJson.isBlank()) {
            return List.of();
        }
        try {
            List<String> raw = objectMapper.readValue(rawVibesJson, new TypeReference<>() {
            });
            if (raw == null || raw.isEmpty()) {
                return List.of();
            }
            return raw.stream()
                .filter(vibe -> vibe != null && !vibe.isBlank())
                .map(VibeCatalog::normalize)
                .filter(vibe -> !vibe.isBlank() && VibeCatalog.isSupported(vibe))
                .distinct()
                .toList();
        } catch (Exception ignored) {
            return List.of();
        }
    }
}

record RouteFailureGuidance(String code,
                            String userMessage,
                            List<String> suggestedVibes,
                            List<String> suggestedActions) {
    static RouteFailureGuidance empty() {
        return new RouteFailureGuidance(null, null, List.of(), List.of());
    }
}
