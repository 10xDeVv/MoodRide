package com.moodride.routeapi.service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;

import com.moodride.datamodels.Route;

final class RouteProfileAssignments {

    static final List<String> PROFILES = List.of("most_scenic", "balanced", "shorter");

    private RouteProfileAssignments() {
    }

    static Optional<UUID> resolvePrimary(List<Route> routes) {
        return assign(routes, Route::getRouteProfile, Route::getGeneratedAt, Route::getId).stream()
            .findFirst()
            .map(profiledRoute -> profiledRoute.value().getId());
    }

    static <T> List<Profiled<T>> assign(
        List<T> values,
        Function<T, String> profileExtractor,
        Function<T, Instant> generatedAtExtractor,
        Function<T, UUID> idExtractor
    ) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }

        Comparator<T> generatedOrder = Comparator
            .comparing(generatedAtExtractor, Comparator.nullsLast(Comparator.naturalOrder()))
            .thenComparing(idExtractor, Comparator.nullsLast(Comparator.naturalOrder()));
        List<T> chronologicallyOrdered = values.stream()
            .filter(value -> value != null && idExtractor.apply(value) != null)
            .sorted(generatedOrder)
            .toList();
        Map<String, T> byProfile = new LinkedHashMap<>();
        List<T> unprofiled = new ArrayList<>();

        for (T value : chronologicallyOrdered) {
            String profile = normalizeProfile(profileExtractor.apply(value));
            if (profile == null || byProfile.putIfAbsent(profile, value) != null) {
                unprofiled.add(value);
            }
        }

        List<Profiled<T>> profiledValues = new ArrayList<>(PROFILES.size());
        int fallbackIndex = 0;
        for (String profile : PROFILES) {
            T value = byProfile.get(profile);
            if (value == null && fallbackIndex < unprofiled.size()) {
                value = unprofiled.get(fallbackIndex++);
            }
            if (value != null) {
                profiledValues.add(new Profiled<>(value, profile));
            }
        }
        return List.copyOf(profiledValues);
    }

    static String normalizeProfile(String profile) {
        if (profile == null || profile.isBlank()) {
            return null;
        }
        String normalized = profile.strip().toLowerCase(Locale.ROOT)
            .replace('-', '_')
            .replace(' ', '_');
        return PROFILES.contains(normalized) ? normalized : null;
    }

    record Profiled<T>(T value, String profile) {
    }
}
