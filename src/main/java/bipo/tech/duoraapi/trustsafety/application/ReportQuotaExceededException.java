package bipo.tech.duoraapi.trustsafety.application;

import java.time.Duration;
import java.util.Objects;

/** A conta usou a cota de denúncias; a próxima fica disponível em {@link #retryAfter()}. */
public class ReportQuotaExceededException extends RuntimeException {

    private final Duration retryAfter;

    public ReportQuotaExceededException(Duration retryAfter) {
        super("report quota exceeded; try again later");
        this.retryAfter = Objects.requireNonNull(retryAfter, "retryAfter");
    }

    public Duration retryAfter() {
        return retryAfter;
    }

}
