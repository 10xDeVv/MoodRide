package com.moodride.routeapi.config;

import java.util.Arrays;
import java.util.stream.Stream;
import javax.sql.DataSource;

import org.flywaydb.core.Flyway;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class FlywayConfig {

    @Bean(initMethod = "migrate")
    Flyway flyway(DataSource dataSource) {
        return Flyway.configure()
            .dataSource(dataSource)
            .locations("classpath:db/migration")
            .baselineVersion("2")
            .baselineOnMigrate(true)
            .outOfOrder(false)
            .load();
    }

    @Bean
    static BeanFactoryPostProcessor entityManagerFactoryDependsOnFlyway() {
        return beanFactory -> {
            if (!beanFactory.containsBeanDefinition("entityManagerFactory")) {
                return;
            }

            BeanDefinition entityManagerFactory = beanFactory.getBeanDefinition("entityManagerFactory");
            String[] existingDependencies = entityManagerFactory.getDependsOn();
            String[] updatedDependencies = Stream.concat(
                    existingDependencies == null ? Stream.empty() : Arrays.stream(existingDependencies),
                    Stream.of("flyway"))
                .distinct()
                .toArray(String[]::new);

            entityManagerFactory.setDependsOn(updatedDependencies);
        };
    }
}
