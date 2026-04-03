package com.moodride.routeworker;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.cache.annotation.EnableCaching;

/**
 * MoodRide Route Worker Service
 * 
 * Background service that processes route generation jobs using beam search algorithm.
 * Consumes jobs from Kafka, generates scenic routes, and publishes completion events.
 * 
 * Port: 8081
 */
@SpringBootApplication
@EnableKafka
@EnableCaching
public class RouteWorkerApplication {

    public static void main(String[] args) {
        SpringApplication.run(RouteWorkerApplication.class, args);
    }
}