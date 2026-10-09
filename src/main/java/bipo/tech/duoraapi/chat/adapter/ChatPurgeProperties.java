package bipo.tech.duoraapi.chat.adapter;

import java.time.Duration;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import org.hibernate.validator.constraints.time.DurationMin;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Agendamento e tamanho do expurgo do chat (docs/adr/0021). Cada réplica roda {@code interval} depois de terminar a
 * execução anterior, mais um desvio sorteado até {@code jitter}; cada execução apaga no máximo
 * {@code batchSize × maxBatchesPerRun} chats, um lote por comando.
 *
 * @param batchSize chats por comando, cada um com até 300 mensagens: o teto segura o tempo de lock de um lote
 */
@Validated
@ConfigurationProperties("duora.chat.purge")
record ChatPurgeProperties(
        @NotNull @DurationMin(seconds = 1) Duration interval,
        @NotNull @DurationMin(nanos = 0) Duration jitter,
        @Positive @Max(MAX_BATCH_SIZE) int batchSize,
        @Positive int maxBatchesPerRun) {

    static final int MAX_BATCH_SIZE = 10_000;

}
