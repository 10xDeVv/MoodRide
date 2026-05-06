package com.moodride.cdcservice;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.kafka.annotation.EnableKafka;

/**
 * MoodRide CDC Service
 * 
 * Change Data Capture service using Debezium to monitor PostgreSQL changes.
 * Publishes cache invalidation events when scenic tile scores are updated.
 * 
 * Port: 8082
 */
@SpringBootApplication
@EnableKafka
@ConfigurationPropertiesScan
public class CdcServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(CdcServiceApplication.class, args);
    }
}