package com.moodride.routeapi.controller;

import com.moodride.routeapi.dto.AnalyticsEventRequest;
import com.moodride.routeapi.dto.AnalyticsEventResponse;
import com.moodride.routeapi.dto.AnalyticsSummaryResponse;
import com.moodride.routeapi.service.AnalyticsService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/analytics")
public class AnalyticsController {

    private final AnalyticsService analyticsService;

    public AnalyticsController(AnalyticsService analyticsService) {
        this.analyticsService = analyticsService;
    }

    @PostMapping("/events")
    public ResponseEntity<AnalyticsEventResponse> recordEvent(@Valid @RequestBody AnalyticsEventRequest request) {
        return ResponseEntity.accepted().body(analyticsService.recordEvent(request));
    }

    @GetMapping("/summary")
    public AnalyticsSummaryResponse getSummary(@RequestParam(defaultValue = "30") int days) {
        return analyticsService.getSummary(days);
    }
}
