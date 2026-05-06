package com.moodride.routeapi.controller;

import com.moodride.routeapi.dto.ScenicRegionsResponse;
import com.moodride.routeapi.service.ScenicRegionService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping({"", "/api"})
public class ScenicRegionController {

    private final ScenicRegionService scenicRegionService;

    public ScenicRegionController(ScenicRegionService scenicRegionService) {
        this.scenicRegionService = scenicRegionService;
    }

    @GetMapping("/scenic-regions")
    public ResponseEntity<ScenicRegionsResponse> getScenicRegions(
        @RequestParam("lat") double latitude,
        @RequestParam("lng") double longitude,
        @RequestParam(name = "radiusKm", defaultValue = "50") double radiusKm,
        @RequestParam(name = "limit", defaultValue = "25") int limit,
        @RequestParam(name = "vibe", required = false) String vibe
    ) {
        return ResponseEntity.ok(
            scenicRegionService.getScenicRegions(latitude, longitude, radiusKm, limit, vibe)
        );
    }
}
