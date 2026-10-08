package bipo.tech.duoraapi.matching.api;

import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import bipo.tech.duoraapi.matching.application.RoundNotFoundException;
import bipo.tech.duoraapi.matching.application.SeatNotFoundException;
import bipo.tech.duoraapi.matching.application.UnknownEventException;
import bipo.tech.duoraapi.matching.domain.InvalidRoundNumberException;

/**
 * Erros das rotas de pareamento em ProblemDetail (docs/adr/0005). As mensagens nunca trazem o valor recebido.
 * As recusas de regra de negócio, com reason, saem do RefusalProblemHandler (docs/adr/0020).
 */
@RestControllerAdvice(basePackageClasses = MatchingExceptionHandler.class)
class MatchingExceptionHandler {

    /** O teto de espera é de 5 s (JdbcRoundRepository.LOCK_TIMEOUT); um segundo basta para tentar de novo. */
    static final String RETRY_AFTER_SECONDS = "1";

    @ExceptionHandler(InvalidRoundNumberException.class)
    ProblemDetail handleInvalidRoundNumber(InvalidRoundNumberException exception) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, exception.getMessage());
    }

    @ExceptionHandler(UnknownEventException.class)
    ProblemDetail handleUnknownEvent(UnknownEventException exception) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, exception.getMessage());
    }

    @ExceptionHandler(RoundNotFoundException.class)
    ProblemDetail handleRoundNotFound(RoundNotFoundException exception) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, exception.getMessage());
    }

    @ExceptionHandler(SeatNotFoundException.class)
    ProblemDetail handleSeatNotFound(SeatNotFoundException exception) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, exception.getMessage());
    }

    /**
     * A rodada esperou outra transação que criava a mesma além do teto. Nada foi gravado, e tentar de novo é
     * seguro: o PUT é idempotente.
     */
    @ExceptionHandler(PessimisticLockingFailureException.class)
    ResponseEntity<ProblemDetail> handleLockWaitTimeout() {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .header(HttpHeaders.RETRY_AFTER, RETRY_AFTER_SECONDS)
                .body(ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE,
                        "the round is being started by another request; try again in a moment"));
    }

}
