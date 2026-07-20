package com.moodride.routeapi.dto;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AnalyticsEventRequestValidationTest {

    private static ValidatorFactory validatorFactory;
    private static Validator validator;

    @BeforeAll
    static void setUpValidator() {
        validatorFactory = Validation.buildDefaultValidatorFactory();
        validator = validatorFactory.getValidator();
    }

    @AfterAll
    static void closeValidatorFactory() {
        validatorFactory.close();
    }

    @Test
    void rejectsMoreThanThreeVibes() {
        AnalyticsEventRequest request = validRequest(
            List.of("coastal", "mountain", "relaxing", "adventure"),
            Map.of("source", "test")
        );

        assertThat(validator.validate(request))
            .extracting(violation -> violation.getPropertyPath().toString())
            .contains("vibes");
    }

    @Test
    void rejectsMoreThanTwentyTopLevelMetadataItems() {
        Map<String, Object> metadata = new LinkedHashMap<>();
        for (int index = 0; index < 21; index++) {
            metadata.put("item-" + index, index);
        }
        AnalyticsEventRequest request = validRequest(List.of("scenic"), metadata);

        assertThat(validator.validate(request))
            .extracting(violation -> violation.getPropertyPath().toString())
            .contains("metadata");
    }

    private AnalyticsEventRequest validRequest(List<String> vibes, Map<String, Object> metadata) {
        return new AnalyticsEventRequest(
            "anon-session-1",
            "anon-client-1",
            "route_generation_primary_ready",
            null,
            null,
            null,
            "drive",
            vibes,
            60,
            "grid:43.5:-79.5",
            1,
            "completed",
            500L,
            null,
            metadata
        );
    }
}
