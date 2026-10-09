package bipo.tech.duoraapi.profiles.api;

import java.time.Duration;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Quantas edições do perfil cada conta pode enviar por período, as recusadas por versão desatualizada
 * incluídas; o saldo é reposto aos poucos ao longo dele (docs/adr/0011). O período vai até um dia, o teto
 * do Retry-After documentado.
 */
@Validated
@ConfigurationProperties("duora.profiles.edit-rate-limit")
record ProfileEditRateLimitProperties(@Positive int capacity, @NotNull Duration period) {

    /**
     * A capacidade de application.properties. A descrição da rota na spec cita este número, porque uma anotação
     * só aceita constante; o RateLimitDescriptionsTest falha se os dois divergirem.
     */
    static final int DEFAULT_CAPACITY = 120;

}
