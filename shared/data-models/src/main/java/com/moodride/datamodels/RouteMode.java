package com.moodride.datamodels;

import java.util.Locale;

public enum RouteMode {
    DRIVE("drive", "driving", "Drive"),
    WALK("walk", "walking", "Walk"),
    BIKE("bike", "cycling", "Bike");

    private final String apiValue;
    private final String osrmProfile;
    private final String displayName;

    RouteMode(String apiValue, String osrmProfile, String displayName) {
        this.apiValue = apiValue;
        this.osrmProfile = osrmProfile;
        this.displayName = displayName;
    }

    public String apiValue() {
        return apiValue;
    }

    public String osrmProfile() {
        return osrmProfile;
    }

    public String displayName() {
        return displayName;
    }

    public static RouteMode fromApiValue(String rawValue) {
        if (rawValue == null || rawValue.isBlank()) {
            return DRIVE;
        }

        String normalized = rawValue.trim()
            .toLowerCase(Locale.ROOT)
            .replace("-", "_")
            .replace(" ", "_");

        return switch (normalized) {
            case "drive", "driving", "car", "auto" -> DRIVE;
            case "walk", "walking", "foot", "pedestrian" -> WALK;
            case "bike", "biking", "cycling", "cycle", "bicycle" -> BIKE;
            default -> throw new IllegalArgumentException("Invalid route mode: " + rawValue);
        };
    }
}
