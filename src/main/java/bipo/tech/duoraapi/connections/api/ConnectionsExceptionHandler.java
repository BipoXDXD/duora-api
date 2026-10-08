package bipo.tech.duoraapi.connections.api;

import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import bipo.tech.duoraapi.connections.application.DecisionNotFoundException;
import bipo.tech.duoraapi.connections.application.NotPairedException;

/**
 * Erros das rotas de conexões em ProblemDetail (docs/adr/0005). As mensagens nunca trazem o valor recebido
 * nem nada da decisão do par.
 */
@RestControllerAdvice(basePackageClasses = ConnectionsExceptionHandler.class)
class ConnectionsExceptionHandler {

    /** O teto de espera é de 2 s (JdbcDecisionRepository.LOCK_TIMEOUT); um segundo basta para tentar de novo. */
    static final String RETRY_AFTER_SECONDS = "1";

    @ExceptionHandler(InvalidRequestException.class)
    ProblemDetail handleInvalidRequest(InvalidRequestException exception) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, exception.getMessage());
    }

    @ExceptionHandler(NotPairedException.class)
    ProblemDetail handleNotPaired(NotPairedException exception) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, exception.getMessage());
    }

    @ExceptionHandler(DecisionNotFoundException.class)
    ProblemDetail handleDecisionNotFound(DecisionNotFoundException exception) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, exception.getMessage());
    }

    /**
     * A decisão esperou a do par na mesma rodada além do teto. Nada foi gravado, e tentar de novo é seguro:
     * repetir a mesma escolha é idempotente.
     */
    @ExceptionHandler(PessimisticLockingFailureException.class)
    ResponseEntity<ProblemDetail> handleLockWaitTimeout() {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .header(HttpHeaders.RETRY_AFTER, RETRY_AFTER_SECONDS)
                .body(ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE,
                        "another decision about the same pair is in progress; try again in a moment"));
    }

}
