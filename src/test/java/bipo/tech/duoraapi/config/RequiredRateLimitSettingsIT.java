package bipo.tech.duoraapi.config;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.SpringApplication;
import org.testcontainers.postgresql.PostgreSQLContainer;

import bipo.tech.duoraapi.DuoraApiApplication;
import bipo.tech.duoraapi.TestcontainersConfiguration;

/**
 * Os limites por conta de inscrições, de rodadas e de decisões não sobem com valor inválido: capacidade zero bloquearia
 * todo mundo, e período zero ou acima de um dia quebra o teto do Retry-After documentado na spec.
 */
class RequiredRateLimitSettingsIT {

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
    @CsvSource({
            "duora.events.registration-rate-limit.capacity, 0, capacity",
            "duora.events.registration-rate-limit.capacity, -1, capacity",
            "duora.events.registration-rate-limit.period, PT0S, period",
            "duora.events.registration-rate-limit.period, P2D, period",
            "duora.matching.round-rate-limit.capacity, 0, capacity",
            "duora.matching.round-rate-limit.capacity, -1, capacity",
            "duora.matching.round-rate-limit.period, PT0S, period",
            "duora.matching.round-rate-limit.period, P2D, period",
            "duora.connections.decision-rate-limit.capacity, 0, capacity",
            "duora.connections.decision-rate-limit.capacity, -1, capacity",
            "duora.connections.decision-rate-limit.period, PT0S, period",
            "duora.connections.decision-rate-limit.period, P2D, period"})
    void applicationRefusesToStartWithAnInvalidLimit(String property, String value, String field) {
        String[] arguments = {
                "--server.port=0",
                "--spring.datasource.url=" + POSTGRES.getJdbcUrl(),
                "--spring.datasource.username=" + POSTGRES.getUsername(),
                "--spring.datasource.password=" + POSTGRES.getPassword(),
                "--" + property + "=" + value};

        assertThatThrownBy(() -> SpringApplication.run(DuoraApiApplication.class, arguments))
                .rootCause()
                .hasMessageContaining(field);
    }

}
