package com.moodride.ingestionservice;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.batch.core.configuration.annotation.EnableBatchProcessing;

/**
 * MoodRide Ingestion Service
 * 
 * Batch service for ingesting and processing OpenStreetMap data.
 * Processes OSM PBF files and loads road network into PostGIS database.
 * 
 * Port: 8083
 */
@SpringBootApplication
@EnableBatchProcessing
public class IngestionServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(IngestionServiceApplication.class, args);
    }
}