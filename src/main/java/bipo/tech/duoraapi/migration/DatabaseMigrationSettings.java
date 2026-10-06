package bipo.tech.duoraapi.migration;

import java.util.Map;
import java.util.regex.Pattern;

/**
 * Configuração do job de migração, lida das variáveis de ambiente que o Container Apps preenche com
 * segredos do Key Vault (infra/azure). Sem uma delas, ou com ela em branco, o job não começa.
 *
 * @param jdbcUrl          banco a migrar, com TLS e verificação de hostname na URL
 * @param migratorUsername login de administração, dono do schema
 * @param migratorPassword senha do login de administração
 * @param appRole          papel com que a aplicação conecta, só com privilégios sobre os dados
 * @param appPassword      senha que o papel da aplicação passa a ter
 */
record DatabaseMigrationSettings(
        String jdbcUrl, String migratorUsername, String migratorPassword, String appRole, String appPassword) {

    static final String JDBC_URL = "DUORA_MIGRATION_JDBC_URL";
    static final String MIGRATOR_USERNAME = "DUORA_MIGRATION_USERNAME";
    static final String MIGRATOR_PASSWORD = "DUORA_MIGRATION_PASSWORD";
    static final String APP_ROLE = "DUORA_APP_DB_USERNAME";
    static final String APP_PASSWORD = "DUORA_APP_DB_PASSWORD";

    /** O nome do papel entra em DDL, que não aceita parâmetro: só identificadores simples do PostgreSQL. */
    private static final Pattern PLAIN_ROLE_NAME = Pattern.compile("[a-z_][a-z0-9_]{0,62}");

    static DatabaseMigrationSettings fromEnvironment(Map<String, String> environment) {
        String appRole = required(environment, APP_ROLE);
        if (!PLAIN_ROLE_NAME.matcher(appRole).matches()) {
            throw new IllegalStateException(APP_ROLE + " must be a lowercase PostgreSQL identifier of up to 63 characters");
        }
        return new DatabaseMigrationSettings(
                required(environment, JDBC_URL),
                required(environment, MIGRATOR_USERNAME),
                required(environment, MIGRATOR_PASSWORD),
                appRole,
                required(environment, APP_PASSWORD));
    }

    private static String required(Map<String, String> environment, String variable) {
        String value = environment.get(variable);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(variable + " must be set and not blank");
        }
        return value;
    }

    /** O registro aparece em log de erro; as senhas, nunca. */
    @Override
    public String toString() {
        return "DatabaseMigrationSettings[jdbcUrl=" + jdbcUrl + ", migratorUsername=" + migratorUsername
                + ", appRole=" + appRole + "]";
    }

}
