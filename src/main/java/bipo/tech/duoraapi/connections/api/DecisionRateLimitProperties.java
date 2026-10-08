package bipo.tech.duoraapi.connections.api;

import java.time.Duration;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Quantas decisões cada conta pode enviar por período, repetições idempotentes incluídas; o saldo é reposto
 * aos poucos ao longo dele (docs/adr/0019). O período vai até um dia, o teto do Retry-After documentado.
 */
@Validated
@ConfigurationProperties("duora.connections.decision-rate-limit")
record DecisionRateLimitProperties(@Positive int capacity, @NotNull Duration period) {
}
