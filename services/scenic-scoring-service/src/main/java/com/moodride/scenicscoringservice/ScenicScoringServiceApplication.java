package com.moodride.scenicscoringservice;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.batch.core.configuration.annotation.EnableBatchProcessing;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * MoodRide Scenic Scoring Service
 * 
 * Weekly batch service for computing scenic scores of H3 hexagonal tiles.
 * Uses 6 external APIs to score water proximity, elevation, land use, etc.
 * 
 * Port: 8085
 */
@SpringBootApplication
@EnableBatchProcessing
@EnableScheduling
public class ScenicScoringServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(ScenicScoringServiceApplication.class, args);
    }
}