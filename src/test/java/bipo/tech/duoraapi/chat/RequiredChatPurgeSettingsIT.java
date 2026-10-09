package bipo.tech.duoraapi.chat;

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
 * O expurgo do chat (docs/adr/0021) não sobe com valor inválido: intervalo zero rodaria sem parar, jitter negativo
 * não é atraso, e lote zero ou sem teto não apagaria nada ou seguraria locks demais.
 */
class RequiredChatPurgeSettingsIT {

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
            "duora.chat.purge.interval, PT0S, interval",
            "duora.chat.purge.jitter, -PT1S, jitter",
            "duora.chat.purge.batch-size, 0, batchSize",
            "duora.chat.purge.batch-size, 10001, batchSize",
            "duora.chat.purge.max-batches-per-run, 0, maxBatchesPerRun"})
    void applicationRefusesToStartWithAnInvalidPurgeSetting(String property, String value, String field) {
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
