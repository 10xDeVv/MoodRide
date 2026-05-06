package com.moodride.scenicscoringservice.controller;

import com.moodride.scenicscoringservice.service.TrafficRefreshEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

@RestController
@RequestMapping("/api/internal/scenic")
public class ScenicInternalController {

    private final TrafficRefreshEventPublisher eventPublisher;

    public ScenicInternalController(TrafficRefreshEventPublisher eventPublisher) {
        this.eventPublisher = eventPublisher;
    }

    @PostMapping("/traffic-refresh-events")
    public TrafficRefreshEventResponse publishTrafficRefresh(@RequestBody TrafficRefreshEventRequest request) {
        if (request == null || isBlank(request.source()) || request.h3Indexes() == null || request.h3Indexes().isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "source and non-empty h3Indexes are required");
        }

        for (String h3 : request.h3Indexes()) {
            if (isBlank(h3)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "h3Indexes must not contain blank values");
            }
        }

        String eventId = eventPublisher.publishTrafficTilesUpdated(request.source(), request.h3Indexes());
        return new TrafficRefreshEventResponse(eventId, request.h3Indexes().size());
    }

    public record TrafficRefreshEventRequest(String source, List<String> h3Indexes) {}

    public record TrafficRefreshEventResponse(String eventId, int tileCount) {
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }
}


