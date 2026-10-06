package bipo.tech.duoraapi.events.api;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import bipo.tech.duoraapi.events.application.EventNotFoundException;
import bipo.tech.duoraapi.events.domain.EventStateConflictException;
import bipo.tech.duoraapi.events.domain.InvalidEventException;

/** Erros das rotas de eventos em ProblemDetail (docs/adr/0005). As mensagens nunca trazem o valor recebido. */
@RestControllerAdvice(basePackageClasses = EventsExceptionHandler.class)
class EventsExceptionHandler {

    @ExceptionHandler(InvalidEventException.class)
    ProblemDetail handleInvalidEvent(InvalidEventException exception) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, exception.getMessage());
    }

    @ExceptionHandler(EventNotFoundException.class)
    ProblemDetail handleNotFound(EventNotFoundException exception) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, exception.getMessage());
    }

    @ExceptionHandler(EventStateConflictException.class)
    ProblemDetail handleStateConflict(EventStateConflictException exception) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, exception.getMessage());
    }

    /** Publicar e cancelar o mesmo evento ao mesmo tempo: a versão otimista deixa só uma ação gravar. */
    @ExceptionHandler(ObjectOptimisticLockingFailureException.class)
    ProblemDetail handleConcurrentChange() {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT,
                "the event changed at the same time; read it again before retrying");
    }

}
