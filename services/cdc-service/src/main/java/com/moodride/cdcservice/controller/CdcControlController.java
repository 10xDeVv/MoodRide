package com.moodride.cdcservice.controller;

import com.moodride.cdcservice.service.CdcControlService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/internal/cdc")
public class CdcControlController {

    private final CdcControlService controlService;

    public CdcControlController(CdcControlService controlService) {
        this.controlService = controlService;
    }

    @PostMapping("/pause")
    public ResponseEntity<Map<String, Object>> pause() {
        controlService.pause();
        return ResponseEntity.ok(statusBody());
    }

    @PostMapping("/resume")
    public ResponseEntity<Map<String, Object>> resume() {
        controlService.resume();
        return ResponseEntity.ok(statusBody());
    }

    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> status() {
        return ResponseEntity.ok(statusBody());
    }

    private Map<String, Object> statusBody() {
        Map<String, Object> response = new HashMap<>();
        response.put("paused", controlService.isPaused());
        response.put("lastProcessedAt", controlService.getLastProcessedAt());
        return response;
    }
}

