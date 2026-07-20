package com.moodride.routeapi.controller;

import com.moodride.routeapi.dto.PrimaryRouteResponse;
import com.moodride.routeapi.dto.RouteDetailResponse;
import com.moodride.routeapi.dto.RouteJobStatusResponse;
import com.moodride.routeapi.dto.RouteRatingRequest;
import com.moodride.routeapi.dto.RouteRatingResponse;
import com.moodride.routeapi.dto.RouteRequest;
import com.moodride.routeapi.dto.RouteSubmissionResponse;
import com.moodride.routeapi.service.PrimaryRouteService;
import com.moodride.routeapi.service.RouteService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.UUID;

@RestController
@RequestMapping({"/api/routes", "/routes"})
public class RouteController {

    private static final String LEGACY_DEPRECATION = "Fri, 01 May 2026 00:00:00 GMT";
    private static final String LEGACY_SUNSET = "Sat, 01 Aug 2026 00:00:00 GMT";
    private static final String SUCCESSOR_LINK = "</api/routes>; rel=\"successor-version\"";
    
    private final RouteService routeService;
    private final PrimaryRouteService primaryRouteService;
    
    public RouteController(RouteService routeService, PrimaryRouteService primaryRouteService) {
        this.routeService = routeService;
        this.primaryRouteService = primaryRouteService;
    }
    
    @PostMapping({"", "/generate"})
    public ResponseEntity<RouteSubmissionResponse> generateRoute(@Valid @RequestBody RouteRequest request,
                                                                 HttpServletRequest servletRequest) {
        return responseFor(servletRequest, ResponseEntity.accepted())
            .body(routeService.submitRoute(request));
    }

    @GetMapping("/{jobId}")
    public ResponseEntity<RouteJobStatusResponse> getRouteJobById(@PathVariable UUID jobId,
                                                                  HttpServletRequest servletRequest) {
        return responseFor(servletRequest, ResponseEntity.ok())
            .body(routeService.getRouteJobStatus(jobId));
    }

    @GetMapping("/jobs/{jobId}")
    public ResponseEntity<RouteJobStatusResponse> getRouteJob(@PathVariable UUID jobId,
                                                              HttpServletRequest servletRequest) {
        return responseFor(servletRequest, ResponseEntity.ok())
            .body(routeService.getRouteJobStatus(jobId));
    }

    @GetMapping("/route/{routeId}")
    public ResponseEntity<RouteDetailResponse> getRoute(@PathVariable UUID routeId,
                                                        HttpServletRequest servletRequest) {
        return responseFor(servletRequest, ResponseEntity.ok())
            .body(routeService.getRoute(routeId));
    }
    @GetMapping("/route/{routeId}/primary")
    public ResponseEntity<PrimaryRouteResponse> getPrimaryRoute(@PathVariable UUID routeId,
                                                                HttpServletRequest servletRequest) {
        return responseFor(servletRequest, ResponseEntity.ok())
            .body(primaryRouteService.getPrimaryRoute(routeId));
    }


    @PostMapping("/{routeId}/rating")
    public ResponseEntity<RouteRatingResponse> rateRoute(@PathVariable UUID routeId,
                                                          @Valid @RequestBody RouteRatingRequest request,
                                                          HttpServletRequest servletRequest) {
        return responseFor(servletRequest, ResponseEntity.ok())
            .body(routeService.rateRoute(routeId, request));
    }

    private ResponseEntity.BodyBuilder responseFor(HttpServletRequest request, ResponseEntity.BodyBuilder builder) {
        if (!isLegacyRouteAlias(request)) {
            return builder;
        }
        return builder
            .header("Deprecation", LEGACY_DEPRECATION)
            .header("Sunset", LEGACY_SUNSET)
            .header("Link", SUCCESSOR_LINK);
    }

    private boolean isLegacyRouteAlias(HttpServletRequest request) {
        String uri = request.getRequestURI();
        if (uri == null) {
            return false;
        }
        return uri.equals("/routes") || uri.startsWith("/routes/");
    }
}
