package com.homehub.database;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.sql.DataSource;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * SQLite creates the database file but not its folder (e.g. {@code data/} on a fresh checkout), so this creates the
 * folder named in {@code spring.datasource.url} as soon as the data source exists, before Flyway first connects.
 */
@Configuration(proxyBeanMethods = false)
public class DatabaseFolder {

    private static final String SQLITE_PREFIX = "jdbc:sqlite:";

    @Bean
    static BeanPostProcessor sqliteDatabaseFolderCreator(Environment environment) {
        return new BeanPostProcessor() {
            @Override
            public Object postProcessBeforeInitialization(Object bean, String beanName) {
                if (bean instanceof DataSource) create(environment.getProperty("spring.datasource.url", ""));
                return bean;
            }
        };
    }

    /** Creates the parent folder of the SQLite file in a JDBC URL; anything else (other databases, memory) is left alone. */
    static void create(String datasourceUrl) {
        if (!datasourceUrl.startsWith(SQLITE_PREFIX)) return;
        String file = datasourceUrl.substring(SQLITE_PREFIX.length());
        int query = file.indexOf('?');
        if (query >= 0) file = file.substring(0, query);
        if (file.isBlank() || file.startsWith(":memory:") || file.startsWith("file:")) return;
        Path parent = Path.of(file).toAbsolutePath().getParent();
        if (parent == null) return;
        try {
            Files.createDirectories(parent);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot create the database folder " + parent, e);
        }
    }
}
