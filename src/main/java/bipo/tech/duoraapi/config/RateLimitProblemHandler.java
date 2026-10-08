package bipo.tech.duoraapi.config;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Limite por conta esgotado (429) ou não contável (503), em ProblemDetail (docs/adr/0005). */
@RestControllerAdvice
class RateLimitProblemHandler {

    private static final long NANOS_PER_SECOND = 1_000_000_000L;

    @ExceptionHandler(RateLimitExceededException.class)
    ResponseEntity<ProblemDetail> handleExceeded(RateLimitExceededException exception) {
        long retryAfterSeconds = Math.ceilDiv(exception.retryAfter().toNanos(), NANOS_PER_SECOND);
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header(HttpHeaders.RETRY_AFTER, Long.toString(retryAfterSeconds))
                .body(ProblemDetail.forStatusAndDetail(HttpStatus.TOO_MANY_REQUESTS, exception.getMessage()));
    }

    /** Falha fechada (docs/adr/0006): sem contar o limite, a chamada não passa. */
    @ExceptionHandler(RateLimitUnavailableException.class)
    ResponseEntity<ProblemDetail> handleUnavailable() {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .header(HttpHeaders.RETRY_AFTER, AccountRateLimit.UNAVAILABLE_RETRY_AFTER_SECONDS)
                .body(ProblemDetail.forStatus(HttpStatus.SERVICE_UNAVAILABLE));
    }

}
