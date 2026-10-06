package bipo.tech.duoraapi.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class DatabaseMigrationSettingsTest {

    private static final String MIGRATOR_PASSWORD = "CANARY-MIGRATOR-6f1c";
    private static final String APP_PASSWORD = "CANARY-APP-93ab";

    @Test
    void readsEveryValueFromTheEnvironment() {
        var settings = DatabaseMigrationSettings.fromEnvironment(completeEnvironment());

        assertThat(settings.jdbcUrl()).isEqualTo("jdbc:postgresql://db.internal:5432/duora");
        assertThat(settings.migratorUsername()).isEqualTo("duora_admin");
        assertThat(settings.migratorPassword()).isEqualTo(MIGRATOR_PASSWORD);
        assertThat(settings.appRole()).isEqualTo("duora_app");
        assertThat(settings.appPassword()).isEqualTo(APP_PASSWORD);
    }

    @ParameterizedTest
    @ValueSource(strings = {"DUORA_MIGRATION_JDBC_URL", "DUORA_MIGRATION_USERNAME", "DUORA_MIGRATION_PASSWORD",
            "DUORA_APP_DB_USERNAME", "DUORA_APP_DB_PASSWORD"})
    void refusesToStartWithoutAVariable(String variable) {
        var environment = completeEnvironment();
        environment.remove(variable);

        assertThatThrownBy(() -> DatabaseMigrationSettings.fromEnvironment(environment))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(variable);
    }

    @ParameterizedTest
    @ValueSource(strings = {"DUORA_MIGRATION_PASSWORD", "DUORA_APP_DB_PASSWORD"})
    void refusesToStartWithABlankVariable(String variable) {
        var environment = completeEnvironment();
        environment.put(variable, "  ");

        assertThatThrownBy(() -> DatabaseMigrationSettings.fromEnvironment(environment))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(variable);
    }

    @ParameterizedTest
    @ValueSource(strings = {"_app", "duora_app_2", "a"})
    void acceptsLowercaseRoleNames(String role) {
        var environment = completeEnvironment();
        environment.put("DUORA_APP_DB_USERNAME", role);

        assertThat(DatabaseMigrationSettings.fromEnvironment(environment).appRole()).isEqualTo(role);
    }

    /** O nome entra em DDL, que não aceita parâmetro: só nomes simples, sem aspas nem espaço. */
    @ParameterizedTest
    @ValueSource(strings = {"Duora_app", "duora app", "duora_app;drop role postgres", "2app", "duora-app",
            "a234567890123456789012345678901234567890123456789012345678901234"})
    void rejectsRoleNamesThatAreNotPlainIdentifiers(String role) {
        var environment = completeEnvironment();
        environment.put("DUORA_APP_DB_USERNAME", role);

        assertThatThrownBy(() -> DatabaseMigrationSettings.fromEnvironment(environment))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("DUORA_APP_DB_USERNAME");
    }

    @Test
    void textRepresentationHidesThePasswords() {
        var text = DatabaseMigrationSettings.fromEnvironment(completeEnvironment()).toString();

        assertThat(text).doesNotContain(MIGRATOR_PASSWORD, APP_PASSWORD).contains("duora_admin", "duora_app");
    }

    private static Map<String, String> completeEnvironment() {
        var environment = new HashMap<String, String>();
        environment.put("DUORA_MIGRATION_JDBC_URL", "jdbc:postgresql://db.internal:5432/duora");
        environment.put("DUORA_MIGRATION_USERNAME", "duora_admin");
        environment.put("DUORA_MIGRATION_PASSWORD", MIGRATOR_PASSWORD);
        environment.put("DUORA_APP_DB_USERNAME", "duora_app");
        environment.put("DUORA_APP_DB_PASSWORD", APP_PASSWORD);
        return environment;
    }

}
