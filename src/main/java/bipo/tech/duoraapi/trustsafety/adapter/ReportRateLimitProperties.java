package bipo.tech.duoraapi.trustsafety.adapter;

import java.time.Duration;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** Quantas denúncias cada conta pode fazer por período; o saldo é reposto aos poucos ao longo dele. */
@Validated
@ConfigurationProperties("duora.trustsafety.report-rate-limit")
record ReportRateLimitProperties(@Positive int capacity, @NotNull Duration period) {
}
