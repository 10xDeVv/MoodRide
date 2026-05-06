package com.moodride.routeapi.config;

import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

@Configuration
@EntityScan(basePackages = "com.moodride.datamodels")
@EnableJpaRepositories(basePackages = "com.moodride.routeapi.repository")
public class JpaConfig {
}
