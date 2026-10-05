package bipo.tech.duoraapi.waitlist.api;

import java.time.Duration;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** Quantas inscrições cada IP pode fazer por período; o saldo é reposto aos poucos ao longo dele. */
@Validated
@ConfigurationProperties("duora.waitlist.join-rate-limit")
record JoinWaitlistRateLimitProperties(@Positive int capacity, @NotNull Duration period) {
}
