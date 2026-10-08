package bipo.tech.duoraapi.chat.api;

import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import bipo.tech.duoraapi.chat.application.ChatNotFoundException;
import bipo.tech.duoraapi.chat.application.MessageNotFoundException;

/**
 * Erros das rotas do chat em ProblemDetail (docs/adr/0005). As mensagens nunca trazem o valor recebido, o
 * texto de uma mensagem nem quem conversa com quem. As recusas de regra (chat fechado, chave reusada) saem
 * com reason pelo tratamento comum (docs/adr/0020).
 */
@RestControllerAdvice(basePackageClasses = ChatExceptionHandler.class)
class ChatExceptionHandler {

    /** O teto de espera é de 2 s (JdbcChatRepository.LOCK_TIMEOUT); um segundo basta para tentar de novo. */
    static final String RETRY_AFTER_SECONDS = "1";

    @ExceptionHandler(InvalidRequestException.class)
    ProblemDetail handleInvalidRequest(InvalidRequestException exception) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, exception.getMessage());
    }

    @ExceptionHandler(ChatNotFoundException.class)
    ProblemDetail handleChatNotFound(ChatNotFoundException exception) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, exception.getMessage());
    }

    @ExceptionHandler(MessageNotFoundException.class)
    ProblemDetail handleMessageNotFound(MessageNotFoundException exception) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, exception.getMessage());
    }

    /**
     * O envio esperou outro no mesmo chat além do teto. Nada foi gravado, e tentar de novo com a mesma
     * Idempotency-Key é seguro.
     */
    @ExceptionHandler(PessimisticLockingFailureException.class)
    ResponseEntity<ProblemDetail> handleLockWaitTimeout() {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .header(HttpHeaders.RETRY_AFTER, RETRY_AFTER_SECONDS)
                .body(ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE,
                        "another message to the same chat is in progress; try again in a moment"));
    }

}
