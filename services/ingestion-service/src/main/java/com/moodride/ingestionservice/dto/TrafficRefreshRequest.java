package com.moodride.ingestionservice.dto;

import java.util.List;

public record TrafficRefreshRequest(
        String source,
        List<String> h3Indexes
) {
}

