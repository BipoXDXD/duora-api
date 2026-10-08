package bipo.tech.duoraapi.matching.api;

import java.time.Duration;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Quantos sorteios de rodada cada conta ADMIN pode pedir por período; o saldo é reposto aos poucos ao
 * longo dele (docs/adr/0017). O período vai até um dia, o teto do Retry-After documentado.
 */
@Validated
@ConfigurationProperties("duora.matching.round-rate-limit")
record RoundRateLimitProperties(@Positive int capacity, @NotNull Duration period) {
}
