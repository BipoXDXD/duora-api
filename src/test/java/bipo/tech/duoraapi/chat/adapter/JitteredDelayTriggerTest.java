package bipo.tech.duoraapi.chat.adapter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.random.RandomGenerator;

import org.junit.jupiter.api.Test;
import org.springframework.scheduling.support.SimpleTriggerContext;

class JitteredDelayTriggerTest {

    private static final Instant NOW = Instant.parse("2026-11-03T03:00:00Z");
    private static final Duration DELAY = Duration.ofMinutes(10);
    private static final Duration JITTER = Duration.ofMinutes(2);

    @Test
    void theFirstRunComesAfterTheDelayPlusTheDrawnJitter() {
        var context = new SimpleTriggerContext(Clock.fixed(NOW, ZoneOffset.UTC));

        assertThat(new JitteredDelayTrigger(DELAY, JITTER, LOWEST).nextExecution(context))
                .isEqualTo(NOW.plus(DELAY));
        assertThat(new JitteredDelayTrigger(DELAY, JITTER, HIGHEST).nextExecution(context))
                .isEqualTo(NOW.plus(DELAY).plus(JITTER));
    }

    /** Conta do fim da execução anterior: uma execução longa nunca encavala na seguinte. */
    @Test
    void eachNextRunCountsFromTheEndOfThePreviousOne() {
        var context = new SimpleTriggerContext(Clock.fixed(NOW, ZoneOffset.UTC));
        Instant finished = NOW.minus(Duration.ofMinutes(1));
        context.update(finished.minusSeconds(30), finished.minusSeconds(30), finished);

        assertThat(new JitteredDelayTrigger(DELAY, JITTER, HIGHEST).nextExecution(context))
                .isEqualTo(finished.plus(DELAY).plus(JITTER));
    }

    @Test
    void theJitterStaysWithinItsBound() {
        var trigger = new JitteredDelayTrigger(DELAY, JITTER, RandomGenerator.of("L64X128MixRandom"));
        var context = new SimpleTriggerContext(Clock.fixed(NOW, ZoneOffset.UTC));

        for (int i = 0; i < 1_000; i++) {
            assertThat(trigger.nextExecution(context)).isBetween(NOW.plus(DELAY), NOW.plus(DELAY).plus(JITTER));
        }
    }

    @Test
    void withoutJitterTheDelayIsExact() {
        var context = new SimpleTriggerContext(Clock.fixed(NOW, ZoneOffset.UTC));

        assertThat(new JitteredDelayTrigger(DELAY, Duration.ZERO, HIGHEST).nextExecution(context))
                .isEqualTo(NOW.plus(DELAY));
    }

    @Test
    void theDelayIsPositiveAndTheJitterIsNotNegative() {
        assertThatThrownBy(() -> new JitteredDelayTrigger(Duration.ZERO, JITTER, LOWEST))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new JitteredDelayTrigger(DELAY, Duration.ofSeconds(-1), LOWEST))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /** Sorteia sempre o menor valor permitido. */
    private static final RandomGenerator LOWEST = new RandomGenerator() {
        @Override
        public long nextLong() {
            return 0;
        }

        @Override
        public long nextLong(long bound) {
            return 0;
        }
    };

    /** Sorteia sempre o maior valor permitido, logo abaixo do limite exclusivo. */
    private static final RandomGenerator HIGHEST = new RandomGenerator() {
        @Override
        public long nextLong() {
            return Long.MAX_VALUE;
        }

        @Override
        public long nextLong(long bound) {
            return bound - 1;
        }
    };

}
