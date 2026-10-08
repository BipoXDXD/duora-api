package bipo.tech.duoraapi.chat.api;

import java.time.Duration;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Quantas mensagens cada conta pode enviar por período, repetições com a mesma Idempotency-Key incluídas; o
 * saldo é reposto aos poucos ao longo dele (docs/adr/0021). O período vai até um dia, o teto do Retry-After
 * documentado.
 */
@Validated
@ConfigurationProperties("duora.chat.message-rate-limit")
record ChatRateLimitProperties(@Positive int capacity, @NotNull Duration period) {
}
