package com.moodride.routeapi;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.cache.annotation.EnableCaching;

/**
 * MoodRide Route API Service
 * 
 * REST API for scenic route generation and job management.
 * Accepts route requests, publishes jobs to Kafka, and manages job status.
 * 
 * Port: 8080
 */
@SpringBootApplication
@EnableKafka
@EnableCaching
public class RouteApiApplication {

    public static void main(String[] args) {
        SpringApplication.run(RouteApiApplication.class, args);
    }
}