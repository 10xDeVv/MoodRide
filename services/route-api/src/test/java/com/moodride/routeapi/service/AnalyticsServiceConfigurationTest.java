package com.moodride.routeapi.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.moodride.routeapi.repository.AnalyticsEventRepository;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.PropertyPlaceholderAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class AnalyticsServiceConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(PropertyPlaceholderAutoConfiguration.class))
        .withUserConfiguration(TestConfiguration.class)
        .withBean(AnalyticsEventRepository.class, () -> mock(AnalyticsEventRepository.class))
        .withBean(JdbcTemplate.class, () -> mock(JdbcTemplate.class))
        .withBean(ObjectMapper.class, ObjectMapper::new);

    @Test
    void productionStartupFailsWhenAnalyticsSecretIsMissing() {
        contextRunner
            .withPropertyValues("spring.profiles.active=prod")
            .run(context -> {
                assertThat(context).hasFailed();
                assertThat(context.getStartupFailure())
                    .hasStackTraceContaining("Could not resolve placeholder 'moodride.analytics.hash-secret'");
            });
    }

    @Test
    void productionStartupFailsWhenAnalyticsSecretIsBlank() {
        contextRunner
            .withPropertyValues(
                "spring.profiles.active=prod",
                "moodride.analytics.hash-secret= "
            )
            .run(context -> {
                assertThat(context).hasFailed();
                assertThat(context.getStartupFailure())
                    .hasRootCauseInstanceOf(IllegalStateException.class)
                    .hasStackTraceContaining("hash-secret must be configured");
            });
    }

    @Test
    void productionStartupAcceptsConfiguredAnalyticsSecret() {
        contextRunner
            .withPropertyValues(
                "spring.profiles.active=prod",
                "moodride.analytics.hash-secret=production-test-secret"
            )
            .run(context -> {
                assertThat(context).hasNotFailed();
                assertThat(context).hasSingleBean(AnalyticsService.class);
            });
    }

    @Configuration(proxyBeanMethods = false)
    @Import(AnalyticsService.class)
    static class TestConfiguration {
    }
}
