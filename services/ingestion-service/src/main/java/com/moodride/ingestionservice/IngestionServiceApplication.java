package com.moodride.ingestionservice;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.AutoConfigurationPackage;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * MoodRide Ingestion Service.
 * Ingestion service for processing OpenStreetMap and scenic scoring data.
 * Phase 1: Loads OSM data (planet_osm_line) into PostGIS with native SQL,
 * including geometry, H3 indexing, and curvature.
 * Phase 2: Computes scenic scores for H3 tiles using multiple data sources (NLCD,
 * OpenTopoData, Natural Earth, OSM).
 * Port: 8083.
 * Batch Jobs: scenicScoringJob computes scenic_score_tiles.
 */
@SpringBootApplication(scanBasePackages = "com.moodride")
@EnableKafka
@EnableScheduling
@AutoConfigurationPackage(basePackages = "com.moodride")
public class IngestionServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(IngestionServiceApplication.class, args);
    }
}