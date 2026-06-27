package com.moodride.notificationservice;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.kafka.annotation.EnableKafka;

/**
 * Wayward Notification Service
 * 
 * WebSocket service for real-time route delivery to frontend clients.
 * Consumes route completion events from Kafka and delivers via WebSocket.
 * 
 * Port: 8084
 */
@SpringBootApplication
@EnableKafka
public class NotificationServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(NotificationServiceApplication.class, args);
    }
}
