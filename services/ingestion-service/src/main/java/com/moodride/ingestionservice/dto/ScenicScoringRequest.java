package com.moodride.ingestionservice.dto;

import java.util.List;

public record ScenicScoringRequest(
        List<String> h3Indexes,
        Integer maxTiles,
        Boolean onlyUnscored,
        String startAfterH3
) {
}
