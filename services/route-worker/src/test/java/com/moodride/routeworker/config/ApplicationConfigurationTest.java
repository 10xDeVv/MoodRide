package com.moodride.routeworker.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

class ApplicationConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
        .withUserConfiguration(TestConfiguration.class);

    @Test
    void usesCompiledRuntimeDefaults() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            ApplicationConfiguration configuration = context.getBean(ApplicationConfiguration.class);
            assertThat(configuration.getProfile()).isEqualTo("hybrid_osrm_v2");
            assertThat(configuration.getMode()).isEqualTo("drive");
        });
    }

    @Test
    void bindsSupportedRuntimeProperties() {
        contextRunner
            .withPropertyValues(
                "moodride.algorithm.profile=hybrid_osrm_v2",
                "moodride.algorithm.mode=drive"
            )
            .run(context -> {
                assertThat(context).hasNotFailed();
                ApplicationConfiguration configuration = context.getBean(ApplicationConfiguration.class);
                assertThat(configuration.getProfile()).isEqualTo("hybrid_osrm_v2");
                assertThat(configuration.getMode()).isEqualTo("drive");
            });
    }

    @Test
    void startupRejectsUnsupportedProfile() {
        contextRunner
            .withPropertyValues("moodride.algorithm.profile=experimental_v3")
            .run(context -> {
                assertThat(context).hasFailed();
                assertThat(context.getStartupFailure())
                    .hasRootCauseInstanceOf(IllegalStateException.class)
                    .hasStackTraceContaining(
                        "Unsupported moodride.algorithm.profile 'experimental_v3'; this route-worker binary supports only 'hybrid_osrm_v2'"
                    );
            });
    }

    @Test
    void startupRejectsUnsupportedMode() {
        contextRunner
            .withPropertyValues("moodride.algorithm.mode=walk")
            .run(context -> {
                assertThat(context).hasFailed();
                assertThat(context.getStartupFailure())
                    .hasRootCauseInstanceOf(IllegalStateException.class)
                    .hasStackTraceContaining(
                        "Unsupported moodride.algorithm.mode 'walk'; this route-worker binary supports only 'drive'"
                    );
            });
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(ApplicationConfiguration.class)
    static class TestConfiguration {
    }
}
