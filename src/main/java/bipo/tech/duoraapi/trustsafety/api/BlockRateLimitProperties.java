package bipo.tech.duoraapi.trustsafety.api;

import java.time.Duration;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Quantos {@code :block} e {@code :unblock}, somados, cada conta pode fazer por período, repetições
 * idempotentes e contas inexistentes incluídas; o saldo é reposto aos poucos ao longo dele (docs/adr/0015).
 * O período vai até um dia, o teto do Retry-After documentado.
 */
@Validated
@ConfigurationProperties("duora.trustsafety.block-rate-limit")
record BlockRateLimitProperties(@Positive int capacity, @NotNull Duration period) {
}
