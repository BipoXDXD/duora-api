package bipo.tech.duoraapi.chat.adapter;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.random.RandomGenerator;

import org.springframework.scheduling.Trigger;
import org.springframework.scheduling.TriggerContext;

/**
 * Atraso fixo a partir do fim da execução anterior (ou da subida), mais um desvio sorteado a cada vez, de zero até
 * {@code maxJitter}: as réplicas, que sobem juntas no deploy, não batem no banco no mesmo instante (Dogpile).
 */
final class JitteredDelayTrigger implements Trigger {

    private final Duration delay;
    private final Duration maxJitter;
    private final RandomGenerator random;

    JitteredDelayTrigger(Duration delay, Duration maxJitter, RandomGenerator random) {
        this.delay = Objects.requireNonNull(delay, "delay");
        this.maxJitter = Objects.requireNonNull(maxJitter, "maxJitter");
        this.random = Objects.requireNonNull(random, "random");
        if (!delay.isPositive()) {
            throw new IllegalArgumentException("the delay must be positive");
        }
        if (maxJitter.isNegative()) {
            throw new IllegalArgumentException("the jitter must not be negative");
        }
    }

    @Override
    public Instant nextExecution(TriggerContext context) {
        Instant lastCompletion = context.lastCompletion();
        Instant from = lastCompletion == null ? context.getClock().instant() : lastCompletion;
        return from.plus(delay).plusMillis(random.nextLong(maxJitter.toMillis() + 1));
    }

}
