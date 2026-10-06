package bipo.tech.duoraapi.migration;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.List;

import org.flywaydb.core.Flyway;
import org.postgresql.PGConnection;

/**
 * Processo do job de migração do Azure Container Apps (infra/azure, docs/adr/0011). Roda na mesma
 * imagem da API, sem subir o Spring: {@code java -cp app.jar bipo.tech.duoraapi.migration.DatabaseMigration}.
 *
 * <p>Com a credencial de administração, garante o papel da aplicação com a senha atual, aplica as
 * migrations do Flyway e concede ao papel só leitura e escrita de dados. Rodar de novo é seguro e é
 * assim que a senha da aplicação é trocada. Termina com código diferente de zero em qualquer falha,
 * o que marca a execução do job como falha e interrompe o deploy.
 */
public final class DatabaseMigration {

    private static final String SCRAM = "scram-sha-256";
    private static final String MIGRATION_HISTORY_TABLE = "flyway_schema_history";

    private static final List<String> DATA_PRIVILEGES = List.of(
            "grant usage on schema public to %I",
            "grant select, insert, update, delete on all tables in schema public to %I",
            "grant usage, select on all sequences in schema public to %I",
            // O histórico decide o que já foi migrado; a aplicação não tem por que lê-lo nem alterá-lo.
            "revoke all on table " + MIGRATION_HISTORY_TABLE + " from %I");

    private DatabaseMigration() {
    }

    public static void main(String[] args) {
        run(DatabaseMigrationSettings.fromEnvironment(System.getenv()));
    }

    static void run(DatabaseMigrationSettings settings) {
        try {
            try (var connection = migratorConnection(settings)) {
                ensureAppRole(connection, settings.appRole(), settings.appPassword());
            }
            Flyway.configure()
                    .dataSource(settings.jdbcUrl(), settings.migratorUsername(), settings.migratorPassword())
                    .load()
                    .migrate();
            try (var connection = migratorConnection(settings)) {
                grantDataPrivileges(connection, settings.appRole());
            }
        } catch (SQLException e) {
            throw new IllegalStateException("database migration failed for " + settings, e);
        }
    }

    private static Connection migratorConnection(DatabaseMigrationSettings settings) throws SQLException {
        return DriverManager.getConnection(settings.jdbcUrl(), settings.migratorUsername(), settings.migratorPassword());
    }

    /**
     * Cria o papel se faltar e define a senha. O driver calcula o hash SCRAM no cliente, então a senha
     * em texto não chega ao servidor nem a um eventual log de comandos.
     */
    private static void ensureAppRole(Connection connection, String role, String password) throws SQLException {
        if (!roleExists(connection, role)) {
            execute(connection, "create role %I login", role);
        }
        connection.unwrap(PGConnection.class).alterUserPassword(role, password.toCharArray(), SCRAM);
    }

    private static boolean roleExists(Connection connection, String role) throws SQLException {
        try (var statement = connection.prepareStatement("select 1 from pg_roles where rolname = ?")) {
            statement.setString(1, role);
            try (var rows = statement.executeQuery()) {
                return rows.next();
            }
        }
    }

    private static void grantDataPrivileges(Connection connection, String role) throws SQLException {
        for (String template : DATA_PRIVILEGES) {
            execute(connection, template, role);
        }
    }

    /** DDL não aceita parâmetro: o próprio PostgreSQL cita o identificador com {@code format('%I')}. */
    private static void execute(Connection connection, String template, String identifier) throws SQLException {
        String ddl;
        try (var statement = connection.prepareStatement("select format(?, ?)")) {
            statement.setString(1, template);
            statement.setString(2, identifier);
            try (var rows = statement.executeQuery()) {
                rows.next();
                ddl = rows.getString(1);
            }
        }
        try (var statement = connection.createStatement()) {
            statement.execute(ddl);
        }
    }

}
