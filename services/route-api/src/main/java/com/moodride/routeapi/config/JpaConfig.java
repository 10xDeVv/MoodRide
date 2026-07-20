package com.moodride.routeapi.config;

import com.moodride.datamodels.RouteJob;
import com.moodride.routeapi.dispatch.RouteJobDispatch;

import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

@Configuration
@EntityScan(basePackageClasses = {RouteJob.class, RouteJobDispatch.class})
@EnableJpaRepositories(basePackages = "com.moodride.routeapi.repository")
public class JpaConfig {
}
