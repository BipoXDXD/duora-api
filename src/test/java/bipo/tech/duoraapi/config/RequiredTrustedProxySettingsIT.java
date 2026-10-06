package bipo.tech.duoraapi.config;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assumptions.assumeThat;

import java.util.stream.Stream;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.SpringApplication;
import org.testcontainers.postgresql.PostgreSQLContainer;

import bipo.tech.duoraapi.DuoraApiApplication;
import bipo.tech.duoraapi.TestcontainersConfiguration;

/**
 * Atrás do proxy (perfil {@code behind-proxy}), a aplicação não sobe sem a faixa de proxies
 * confiáveis: sem ela, todos os clientes dividiriam o IP do ingress e um único bucket do rate
 * limit; em branco, o Tomcat confiaria em qualquer um e o cliente escolheria o próprio IP.
 */
class RequiredTrustedProxySettingsIT {

    private static final String SETTING = "DUORA_TRUSTED_PROXIES";

    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(TestcontainersConfiguration.POSTGRES_IMAGE);

    @BeforeAll
    static void startDatabase() {
        POSTGRES.start();
    }

    @AfterAll
    static void stopDatabase() {
        POSTGRES.stop();
    }

    @Test
    void applicationBehindProxyRefusesToStartWithoutTrustedProxies() {
        assumeThat(System.getenv(SETTING))
                .as("o teste simula o ambiente sem a variável")
                .isNull();

        assertThatThrownBy(() -> SpringApplication.run(DuoraApiApplication.class, behindProxyArguments()))
                .rootCause()
                .hasMessageContaining(SETTING);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " "})
    void applicationBehindProxyRefusesToStartWithBlankTrustedProxies(String blank) {
        var arguments = Stream.concat(
                Stream.of(behindProxyArguments()),
                Stream.of("--" + SETTING + "=" + blank)).toArray(String[]::new);

        assertThatThrownBy(() -> SpringApplication.run(DuoraApiApplication.class, arguments))
                .rootCause()
                .hasMessageContaining("internalProxies");
    }

    /** As variáveis do Entra vêm do config/application.properties do classpath de teste. */
    private static String[] behindProxyArguments() {
        return new String[] {
                "--spring.profiles.active=behind-proxy",
                "--server.port=0",
                "--spring.datasource.url=" + POSTGRES.getJdbcUrl(),
                "--spring.datasource.username=" + POSTGRES.getUsername(),
                "--spring.datasource.password=" + POSTGRES.getPassword()};
    }

}
