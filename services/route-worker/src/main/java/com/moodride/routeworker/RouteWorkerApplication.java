package com.moodride.routeworker;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * MoodRide Route Worker Service
 * 
 * Background service that processes route generation jobs using hybrid OSRM routing.
 * Consumes jobs from Kafka, generates scenic routes, and publishes completion events.
 * 
 * Port: 8081
 */
@SpringBootApplication
@EntityScan(basePackages = "com.moodride.datamodels")
@EnableKafka
@EnableCaching
@EnableScheduling
public class RouteWorkerApplication {

    public static void main(String[] args) {
        SpringApplication.run(RouteWorkerApplication.class, args);
    }
}
