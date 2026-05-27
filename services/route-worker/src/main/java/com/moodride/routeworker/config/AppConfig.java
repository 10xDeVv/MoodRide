package com.moodride.routeworker.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.moodride.datamodels.scoring.ScenicScoreCalculator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Application-level configuration for Route Worker service.
 * Provides Jackson ObjectMapper bean for JSON serialization.
 */
@Configuration
public class AppConfig {

    /**
     * Provides a singleton ObjectMapper bean for JSON processing.
     * Used by RouteCompletionProducer for serializing Kafka events.
     * Configured with JSR310 module for Java 8 date/time types.
     */
    @Bean
    public ObjectMapper objectMapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        return mapper;
    }

    @Bean
    public ScenicScoreCalculator scenicScoreCalculator() {
        return new ScenicScoreCalculator();
    }
}

