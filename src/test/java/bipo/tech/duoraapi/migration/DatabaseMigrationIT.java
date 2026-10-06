package bipo.tech.duoraapi.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.util.PSQLException;
import org.testcontainers.postgresql.PostgreSQLContainer;

import bipo.tech.duoraapi.TestcontainersConfiguration;

/**
 * O job de migração do Container Apps (infra/azure): migra o schema com a credencial de
 * administração e deixa a aplicação com um papel que só lê e escreve dados (docs/adr/0014).
 *
 * <p>Como no Azure, quem migra não é superusuário: tem {@code CREATEROLE} e é dono do banco. Cada
 * teste usa um banco e um papel de aplicação próprios, porque papéis valem para o servidor inteiro.
 */
class DatabaseMigrationIT {

    private static final String MIGRATOR = "duora_admin";
    private static final String MIGRATOR_PASSWORD = "migrator-test-only";
    private static final String INSUFFICIENT_PRIVILEGE = "42501";
    private static final String INVALID_PASSWORD = "28P01";
    private static final AtomicInteger SEQUENCE = new AtomicInteger();

    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(TestcontainersConfiguration.POSTGRES_IMAGE);

    private String database;
    private String appRole;

    @BeforeAll
    static void startServerWithANonSuperuserMigrator() throws SQLException {
        POSTGRES.start();
        try (var connection = superuserConnection(); var statement = connection.createStatement()) {
            statement.execute("create role " + MIGRATOR + " login createrole password '" + MIGRATOR_PASSWORD + "'");
        }
    }

    @AfterAll
    static void stopServer() {
        POSTGRES.stop();
    }

    @BeforeEach
    void createDatabaseOwnedByTheMigrator() throws SQLException {
        int id = SEQUENCE.incrementAndGet();
        database = "duora_" + id;
        appRole = "duora_app_" + id;
        try (var connection = superuserConnection(); var statement = connection.createStatement()) {
            statement.execute("create database " + database + " owner " + MIGRATOR);
        }
    }

    @Test
    void appliesEveryMigration() {
        DatabaseMigration.run(settings("app-password-1"));

        var info = Flyway.configure().dataSource(jdbcUrl(), MIGRATOR, MIGRATOR_PASSWORD).load().info();
        assertThat(info.pending()).isEmpty();
        assertThat(info.applied()).isNotEmpty().hasSameSizeAs(info.all());
    }

    @Test
    void appRoleLogsInAndReadsAndWritesEveryApplicationTable() throws SQLException {
        DatabaseMigration.run(settings("app-password-1"));

        try (var connection = DriverManager.getConnection(jdbcUrl(), appRole, "app-password-1")) {
            // Sem as tabelas na lista, a checagem seguinte passaria sem conferir nada.
            assertThat(applicationTables(connection)).contains("waitlist_entry", "account", "profile");
            assertThat(applicationTablesWithout(connection, "SELECT,INSERT,UPDATE,DELETE")).isEmpty();
            try (var statement = connection.createStatement()) {
                statement.execute("insert into waitlist_entry (email, joined_at) values ('ana@example.com', now())");
            }
        }
    }

    /** Tabela criada depois do job pelo dono das migrations (uma migration nova, por exemplo). */
    @Test
    void appRoleReadsAndWritesTablesCreatedLaterByTheMigrator() throws SQLException {
        DatabaseMigration.run(settings("app-password-1"));
        try (var connection = DriverManager.getConnection(jdbcUrl(), MIGRATOR, MIGRATOR_PASSWORD);
                var statement = connection.createStatement()) {
            statement.execute("create table later_table (id bigint generated always as identity primary key, note text)");
        }

        try (var connection = DriverManager.getConnection(jdbcUrl(), appRole, "app-password-1");
                var statement = connection.createStatement()) {
            statement.execute("insert into later_table (note) values ('written by the app')");
            statement.execute("update later_table set note = 'changed'");
            statement.execute("delete from later_table");
        }
    }

    @Test
    void appRoleCannotChangeTheSchema() throws SQLException {
        DatabaseMigration.run(settings("app-password-1"));

        try (var connection = DriverManager.getConnection(jdbcUrl(), appRole, "app-password-1");
                var statement = connection.createStatement()) {
            assertThatThrownBy(() -> statement.execute("create table intruder (id int)"))
                    .isInstanceOf(PSQLException.class)
                    .extracting(error -> ((PSQLException) error).getSQLState()).isEqualTo(INSUFFICIENT_PRIVILEGE);
            assertThatThrownBy(() -> statement.execute("drop table waitlist_entry"))
                    .isInstanceOf(PSQLException.class);
        }
    }

    @Test
    void appRoleCannotTouchTheMigrationHistory() throws SQLException {
        DatabaseMigration.run(settings("app-password-1"));

        try (var connection = DriverManager.getConnection(jdbcUrl(), appRole, "app-password-1");
                var statement = connection.createStatement()) {
            assertThatThrownBy(() -> statement.executeQuery("select count(*) from flyway_schema_history"))
                    .isInstanceOf(PSQLException.class)
                    .extracting(error -> ((PSQLException) error).getSQLState()).isEqualTo(INSUFFICIENT_PRIVILEGE);
        }
    }

    @Test
    void runningAgainWithANewPasswordRotatesIt() throws SQLException {
        DatabaseMigration.run(settings("app-password-1"));

        DatabaseMigration.run(settings("app-password-2"));

        assertThatThrownBy(() -> DriverManager.getConnection(jdbcUrl(), appRole, "app-password-1").close())
                .isInstanceOf(PSQLException.class)
                .extracting(error -> ((PSQLException) error).getSQLState()).isEqualTo(INVALID_PASSWORD);
        try (var connection = DriverManager.getConnection(jdbcUrl(), appRole, "app-password-2")) {
            assertThat(connection.isValid(1)).isTrue();
        }
    }

    private DatabaseMigrationSettings settings(String appPassword) {
        return new DatabaseMigrationSettings(jdbcUrl(), MIGRATOR, MIGRATOR_PASSWORD, appRole, appPassword);
    }

    private String jdbcUrl() {
        return "jdbc:postgresql://" + POSTGRES.getHost() + ":" + POSTGRES.getMappedPort(5432) + "/" + database;
    }

    private static List<String> applicationTables(Connection connection) throws SQLException {
        var sql = """
                select relname from pg_class
                where relnamespace = 'public'::regnamespace and relkind in ('r', 'p')
                  and relname <> 'flyway_schema_history'
                """;
        var tables = new ArrayList<String>();
        try (var statement = connection.createStatement(); var rows = statement.executeQuery(sql)) {
            while (rows.next()) {
                tables.add(rows.getString(1));
            }
        }
        return tables;
    }

    /** Tabelas da aplicação (todas menos o histórico do Flyway) em que o papel conectado não tem os privilégios. */
    private static List<String> applicationTablesWithout(Connection connection, String privileges) throws SQLException {
        var sql = """
                select relname from pg_class
                where relnamespace = 'public'::regnamespace and relkind in ('r', 'p')
                  and relname <> 'flyway_schema_history'
                  and not has_table_privilege(current_user, oid, ?)
                """;
        var missing = new ArrayList<String>();
        try (var statement = connection.prepareStatement(sql)) {
            statement.setString(1, privileges);
            try (var rows = statement.executeQuery()) {
                while (rows.next()) {
                    missing.add(rows.getString(1));
                }
            }
        }
        return missing;
    }

    private static Connection superuserConnection() throws SQLException {
        return DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }

}
