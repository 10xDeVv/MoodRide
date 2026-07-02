package com.moodride.routeapi.config;

import java.util.Arrays;
import java.util.stream.Stream;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebCorsConfig implements WebMvcConfigurer {

    private static final String[] DEFAULT_ALLOWED_ORIGINS = {
        "http://localhost:3000",
        "http://localhost:3001",
        "https://usewayward.app",
        "https://www.usewayward.app"
    };

    @Value("${moodride.cors.allowed-origins:http://localhost:3000,http://localhost:3001,https://usewayward.app,https://www.usewayward.app}")
    private String allowedOrigins;

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        String[] originPatterns = allowedOriginPatterns();

        registry.addMapping("/api/**")
            .allowedOriginPatterns(originPatterns)
            .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
            .allowedHeaders("*")
            .allowCredentials(true)
            .maxAge(3600);

        registry.addMapping("/routes/**")
            .allowedOriginPatterns(originPatterns)
            .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
            .allowedHeaders("*")
            .allowCredentials(true)
            .maxAge(3600);
    }

    private String[] allowedOriginPatterns() {
        return Stream.concat(Arrays.stream(allowedOrigins.split(",")), Arrays.stream(DEFAULT_ALLOWED_ORIGINS))
            .map(String::trim)
            .map(origin -> origin.replaceAll("^[\\\"']+|[\\\"']+$", ""))
            .filter(origin -> !origin.isBlank())
            .distinct()
            .toArray(String[]::new);
    }
}