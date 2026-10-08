package bipo.tech.duoraapi.config;

import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import bipo.tech.duoraapi.ActionRefusedException;
import bipo.tech.duoraapi.RefusalReason;

/**
 * Os 409 e os 403 de regra de negócio levam, além do detail, o membro {@code reason} (docs/adr/0020), para
 * o cliente escolher a mensagem sem ler o texto em inglês. Um ponto só para todos os módulos: o domínio diz
 * o motivo, e só aqui o motivo vira status HTTP.
 */
@RestControllerAdvice
@Order(0)
class RefusalProblemHandler {

    /** O membro de extensão do ProblemDetail (RFC 9457, seção 3.2). */
    static final String REASON_PROPERTY = "reason";

    @ExceptionHandler(ActionRefusedException.class)
    ProblemDetail handleRefusal(ActionRefusedException exception) {
        var problem = ProblemDetail.forStatusAndDetail(statusOf(exception.reason()), exception.getMessage());
        problem.setProperty(REASON_PROPERTY, exception.reason());
        return problem;
    }

    /**
     * Sem {@code default}: um motivo novo não compila até ganhar status. 403 quando falta algo a quem chama,
     * 409 quando o estado do recurso não permite a ação.
     */
    static HttpStatus statusOf(RefusalReason reason) {
        return switch (reason) {
            case PROFILE_INCOMPLETE, UNDERAGE -> HttpStatus.FORBIDDEN;
            case EVENT_NOT_PUBLISHED, EVENT_ALREADY_PUBLISHED, EVENT_CANCELLED, EVENT_STARTED, EVENT_ENDED,
                    EVENT_FULL, EVENT_NOT_UNDERWAY, ROUND_OUT_OF_SEQUENCE, BIRTH_DATE_ALREADY_SET,
                    DECISION_ALREADY_MADE -> HttpStatus.CONFLICT;
        };
    }

}
