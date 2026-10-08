package bipo.tech.duoraapi.config;

import java.time.Duration;
import java.util.Objects;

/** A conta usou o limite da operação; a próxima chamada fica disponível em {@link #retryAfter()}. */
public class RateLimitExceededException extends RuntimeException {

    private final Duration retryAfter;

    public RateLimitExceededException(Duration retryAfter) {
        super("rate limit exceeded; try again later");
        this.retryAfter = Objects.requireNonNull(retryAfter, "retryAfter");
    }

    public Duration retryAfter() {
        return retryAfter;
    }

}
