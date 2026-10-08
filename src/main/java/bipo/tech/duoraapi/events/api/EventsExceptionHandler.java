package bipo.tech.duoraapi.events.api;

import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import bipo.tech.duoraapi.events.application.EventNotFoundException;
import bipo.tech.duoraapi.events.application.RegistrationNotFoundException;

/**
 * Erros das rotas de eventos em ProblemDetail (docs/adr/0005). As mensagens nunca trazem o valor recebido. As
 * recusas de regra de negócio, com reason, saem do RefusalProblemHandler (docs/adr/0020).
 */
@RestControllerAdvice(basePackageClasses = EventsExceptionHandler.class)
class EventsExceptionHandler {

    /** O teto de espera do lock do evento é de 2 s; um segundo basta para tentar de novo. */
    static final String RETRY_AFTER_SECONDS = "1";

    @ExceptionHandler(InvalidPageRequestException.class)
    ProblemDetail handleInvalidPageRequest(InvalidPageRequestException exception) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, exception.getMessage());
    }

    @ExceptionHandler(EventNotFoundException.class)
    ProblemDetail handleNotFound(EventNotFoundException exception) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, exception.getMessage());
    }

    @ExceptionHandler(RegistrationNotFoundException.class)
    ProblemDetail handleRegistrationNotFound(RegistrationNotFoundException exception) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, exception.getMessage());
    }

    /**
     * A inscrição esperou o lock do evento além do teto (ver RegistrationRepository.LOCK_TIMEOUT). Nada
     * foi gravado, e tentar de novo é seguro: o PUT é idempotente.
     */
    @ExceptionHandler(PessimisticLockingFailureException.class)
    ResponseEntity<ProblemDetail> handleLockWaitTimeout() {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .header(HttpHeaders.RETRY_AFTER, RETRY_AFTER_SECONDS)
                .body(ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE,
                        "the event is busy; try again in a moment"));
    }

    /** Publicar e cancelar o mesmo evento ao mesmo tempo: a versão otimista deixa só uma ação gravar. */
    @ExceptionHandler(ObjectOptimisticLockingFailureException.class)
    ProblemDetail handleConcurrentChange() {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT,
                "the event changed at the same time; read it again before retrying");
    }

}
