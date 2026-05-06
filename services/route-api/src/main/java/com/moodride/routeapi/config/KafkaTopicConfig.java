package com.moodride.routeapi.config;

import com.moodride.eventmodels.RouteJobEvent;
import com.moodride.eventmodels.RouteRatedEvent;
import com.moodride.eventmodels.DriveCompletedEvent;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaAdmin;
import java.util.HashMap;
import java.util.Map;

@Configuration
public class KafkaTopicConfig {
    
    @Value("${spring.kafka.bootstrap-servers}")
    private String bootstrapServers;
    
    @Bean
    public KafkaAdmin kafkaAdmin() {
        Map<String, Object> configs = new HashMap<>();
        configs.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        KafkaAdmin kafkaAdmin = new KafkaAdmin(configs);
        kafkaAdmin.setFatalIfBrokerNotAvailable(false);
        return kafkaAdmin;
    }
    
    @Bean
    public NewTopic routeJobsTopic() {
        return new NewTopic("route-jobs", 3, (short) 1);
    }
    
    @Bean
    public NewTopic routeCompletionsTopic() {
        return new NewTopic("route-completions", 3, (short) 1);
    }

    @Bean
    public NewTopic routeJobsDlqTopic() {
        return new NewTopic(RouteJobEvent.DLQ_TOPIC, 3, (short) 1);
    }

    @Bean
    public NewTopic routeRatedTopic() {
        return new NewTopic(RouteRatedEvent.TOPIC, 6, (short) 1);
    }

    @Bean
    public NewTopic driveCompletedTopic() {
        return new NewTopic(DriveCompletedEvent.TOPIC, 6, (short) 1);
    }
}
