package com.referralhub.common.testing;

import java.util.List;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Schema and cleanup helpers for tests that want a real database but not a Spring context.
 *
 * <p>Plenty of this codebase is SQL — {@code SKIP LOCKED} claims, partial indexes, LSH band
 * lookups. Those behaviours cannot be asserted against an in-memory database that does not
 * implement them, and mocking the JdbcTemplate would only assert that we wrote the string we
 * wrote. So the store tests talk to Postgres directly.
 */
public final class Databases {

    private Databases() {
    }

    /** A DataSource against the shared Postgres container, migrated to the given locations. */
    public static DataSource migrated(String... migrationLocations) {
        PostgreSQLContainer<?> pg = PlatformContainers.postgres();
        DriverManagerDataSource ds = new DriverManagerDataSource(
                pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        ds.setDriverClassName("org.postgresql.Driver");

        Flyway.configure()
                .dataSource(ds)
                .locations(migrationLocations)
                .baselineOnMigrate(true)
                .load()
                .migrate();
        return ds;
    }

    /** Empties the given tables and everything referencing them. Cheaper than re-migrating. */
    public static void truncate(JdbcTemplate jdbc, String... tables) {
        if (tables.length == 0) {
            return;
        }
        jdbc.execute("TRUNCATE TABLE " + String.join(", ", tables) + " RESTART IDENTITY CASCADE");
    }

    /** Every table in the public schema except Flyway's own bookkeeping. */
    public static void truncateAll(JdbcTemplate jdbc) {
        List<String> tables = jdbc.queryForList("""
                SELECT tablename FROM pg_tables
                WHERE schemaname = 'public' AND tablename <> 'flyway_schema_history'
                """, String.class);
        if (!tables.isEmpty()) {
            truncate(jdbc, tables.toArray(String[]::new));
        }
    }
}
