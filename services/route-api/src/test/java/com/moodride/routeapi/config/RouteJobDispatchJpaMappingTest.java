package com.moodride.routeapi.config;

import java.sql.Connection;
import java.sql.SQLException;

import javax.sql.DataSource;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.datasource.AbstractDataSource;

import com.moodride.routeapi.dispatch.RouteJobDispatch;
import com.moodride.routeapi.repository.RouteJobDispatchRepository;

import jakarta.persistence.EntityManagerFactory;

import static org.assertj.core.api.Assertions.assertThat;

class RouteJobDispatchJpaMappingTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(HibernateJpaAutoConfiguration.class))
        .withUserConfiguration(JpaConfig.class)
        .withBean(DataSource.class, NoConnectionDataSource::new)
        .withPropertyValues(
            "spring.jpa.database-platform=org.hibernate.dialect.PostgreSQLDialect",
            "spring.jpa.hibernate.ddl-auto=none",
            "spring.jpa.open-in-view=false",
            "spring.jpa.properties.hibernate.boot.allow_jdbc_metadata_access=false"
        );

    @Test
    void applicationContextStartsWithDispatchRepositoryAndManagedEntity() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(RouteJobDispatchRepository.class);

            EntityManagerFactory entityManagerFactory =
                context.getBean(EntityManagerFactory.class);
            assertThat(entityManagerFactory.getMetamodel().managedType(RouteJobDispatch.class))
                .isNotNull();
        });
    }

    private static final class NoConnectionDataSource extends AbstractDataSource {
        @Override
        public Connection getConnection() throws SQLException {
            throw new SQLException("The mapping smoke must not access a database");
        }

        @Override
        public Connection getConnection(String username, String password) throws SQLException {
            return getConnection();
        }
    }
}
