package bipo.tech.duoraapi;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * Relógio parado, que o teste avança quando precisa: toda regra que depende do "agora" (começo de um
 * evento, maioridade) fica reproduzível. É um contexto Spring à parte, com o próprio PostgreSQL.
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestClockConfiguration {

    /** O "agora" de cada teste, até ele avançar o relógio. */
    public static final Instant NOW = Instant.parse("2026-10-06T12:00:00Z");

    @Bean
    @Primary
    TestClock testClock() {
        return new TestClock(NOW);
    }

    /** Relógio em UTC que só anda quando o teste manda. Thread-safe: os testes de concorrência o leem em paralelo. */
    public static final class TestClock extends Clock {

        private volatile Instant now;

        TestClock(Instant now) {
            this.now = now;
        }

        public void setTo(Instant instant) {
            now = instant;
        }

        public void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return Clock.fixed(now, zone);
        }

        @Override
        public Instant instant() {
            return now;
        }

    }

}
