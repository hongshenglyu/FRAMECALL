package com.footballref.assist.config;

import org.springframework.boot.CommandLineRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Locale;

@Component
public class SchemaMigrator implements CommandLineRunner {
    private final DataSource dataSource;
    private final JdbcTemplate jdbcTemplate;

    public SchemaMigrator(DataSource dataSource, JdbcTemplate jdbcTemplate) {
        this.dataSource = dataSource;
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void run(String... args) throws Exception {
        ensureAnnotationStatsColumns();
    }

    private void ensureAnnotationStatsColumns() throws SQLException {
        if (!tableExists("annotation_task")) {
            return;
        }
        addColumnIfMissing("annotation_task", "sample_count", "integer default 0 not null");
        addColumnIfMissing("annotation_task", "event_count", "integer default 0 not null");
        addColumnIfMissing("annotation_task", "event_type_summary", "varchar(300)");
        addColumnIfMissing("annotation_task", "split_summary", "varchar(300)");
    }

    private void addColumnIfMissing(String tableName, String columnName, String definition) throws SQLException {
        if (columnExists(tableName, columnName)) {
            return;
        }
        jdbcTemplate.execute("alter table " + tableName + " add column " + columnName + " " + definition);
    }

    private boolean tableExists(String tableName) throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            DatabaseMetaData metaData = connection.getMetaData();
            for (String candidate : candidates(tableName)) {
                try (ResultSet tables = metaData.getTables(null, null, candidate, new String[]{"TABLE"})) {
                    if (tables.next()) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private boolean columnExists(String tableName, String columnName) throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            DatabaseMetaData metaData = connection.getMetaData();
            for (String tableCandidate : candidates(tableName)) {
                for (String columnCandidate : candidates(columnName)) {
                    try (ResultSet columns = metaData.getColumns(null, null, tableCandidate, columnCandidate)) {
                        if (columns.next()) {
                            return true;
                        }
                    }
                }
            }
        }
        return false;
    }

    private String[] candidates(String identifier) {
        return new String[]{
                identifier,
                identifier.toLowerCase(Locale.ROOT),
                identifier.toUpperCase(Locale.ROOT)
        };
    }
}
