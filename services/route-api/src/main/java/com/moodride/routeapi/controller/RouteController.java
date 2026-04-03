package com.moodride.routeapi.controller;

import com.moodride.routeapi.dto.RouteRequest;
import com.moodride.routeapi.dto.RouteResponse;
import com.moodride.routeapi.service.RouteService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.UUID;

@RestController
@RequestMapping("/api/routes")
public class RouteController {
    
    private final RouteService routeService;
    
    public RouteController(RouteService routeService) {
        this.routeService = routeService;
    }
    
    @PostMapping("/generate")
    public ResponseEntity<RouteResponse> generateRoute(@RequestBody RouteRequest request) {
        return ResponseEntity.accepted().body(routeService.generateRoute(request));
    }
    
    @GetMapping("/jobs/{jobId}")
    public ResponseEntity<RouteResponse> getRouteJob(@PathVariable UUID jobId) {
        return ResponseEntity.ok(routeService.getRouteJob(jobId));
    }
    
    @GetMapping("/{routeId}")
    public ResponseEntity<RouteResponse> getRoute(@PathVariable UUID routeId) {
        return ResponseEntity.ok(routeService.getRoute(routeId));
    }
}
