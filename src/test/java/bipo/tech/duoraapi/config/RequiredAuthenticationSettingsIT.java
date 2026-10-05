package bipo.tech.duoraapi.config;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assumptions.assumeThat;

import java.util.Map;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.SpringApplication;
import org.testcontainers.postgresql.PostgreSQLContainer;

import bipo.tech.duoraapi.DuoraApiApplication;
import bipo.tech.duoraapi.TestcontainersConfiguration;

/**
 * Sem a configuração do Entra External ID a aplicação não pode subir. O banco é real para que
 * a única coisa faltando seja a variável testada.
 */
class RequiredAuthenticationSettingsIT {

    private static final Map<String, String> SETTINGS = Map.of(
            "DUORA_AUTH_ISSUER_URI", "https://tenant-id.ciamlogin.example/tenant-id/v2.0",
            "DUORA_AUTH_JWK_SET_URI", "https://tenant.ciamlogin.example/tenant-id/discovery/v2.0/keys",
            "DUORA_AUTH_AUDIENCE", "duora-api-client-id");

    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(TestcontainersConfiguration.POSTGRES_IMAGE);

    @BeforeAll
    static void startDatabase() {
        POSTGRES.start();
    }

    @AfterAll
    static void stopDatabase() {
        POSTGRES.stop();
    }

    @ParameterizedTest
    @ValueSource(strings = {"DUORA_AUTH_ISSUER_URI", "DUORA_AUTH_JWK_SET_URI", "DUORA_AUTH_AUDIENCE"})
    void applicationRefusesToStartWithoutSetting(String missing) {
        assumeThat(System.getenv(missing))
                .as("o teste simula o ambiente sem a variável")
                .isNull();

        assertThatThrownBy(() -> SpringApplication.run(DuoraApiApplication.class, argumentsWithout(missing)))
                .rootCause()
                .hasMessageContaining("Could not resolve placeholder '" + missing + "'");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " "})
    void applicationRefusesToStartWithBlankAudience(String audience) {
        var arguments = Stream.concat(
                Stream.of(argumentsWithout("DUORA_AUTH_AUDIENCE")),
                Stream.of("--DUORA_AUTH_AUDIENCE=" + audience)).toArray(String[]::new);

        assertThatThrownBy(() -> SpringApplication.run(DuoraApiApplication.class, arguments))
                .rootCause()
                .hasMessageContaining("DUORA_AUTH_AUDIENCE");
    }

    private static String[] argumentsWithout(String missing) {
        var database = Stream.of(
                // Só o application.properties principal, sem os valores fictícios do classpath de teste.
                "--spring.config.location=classpath:/application.properties",
                "--server.port=0",
                "--spring.datasource.url=" + POSTGRES.getJdbcUrl(),
                "--spring.datasource.username=" + POSTGRES.getUsername(),
                "--spring.datasource.password=" + POSTGRES.getPassword());
        var otherSettings = SETTINGS.entrySet().stream()
                .filter(setting -> !setting.getKey().equals(missing))
                .map(setting -> "--" + setting.getKey() + "=" + setting.getValue());
        return Stream.concat(database, otherSettings).toArray(String[]::new);
    }

}
