package bipo.tech.duoraapi.config;

import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.boot.webmvc.error.ErrorController;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Responde em {@code ProblemDetail} o que o Tomcat encaminha para /error (exceção não tratada,
 * {@code sendError}), como o resto da API (docs/adr/0005). Substitui o BasicErrorController do Boot,
 * que responde {@code application/json} em outro formato. Só status, título e o correlation ID
 * (docs/adr/0011): a causa fica no log, na linha com o mesmo id.
 */
@RestController
class ProblemDetailErrorController implements ErrorController {

    private static final String REQUEST_ID_PROPERTY = "requestId";

    @RequestMapping("${server.error.path:/error}")
    ResponseEntity<ProblemDetail> error(HttpServletRequest request, HttpServletResponse response) {
        HttpStatus status = statusOf(request);
        ProblemDetail problem = ProblemDetail.forStatus(status);
        // O trace da requisição já fechou quando o Tomcat encaminha para cá; o id é o que já foi
        // devolvido no header.
        String requestId = response.getHeader(RequestIdResponseFilter.HEADER);
        if (requestId != null) {
            problem.setProperty(REQUEST_ID_PROPERTY, requestId);
        }
        return ResponseEntity.status(status).body(problem);
    }

    private static HttpStatus statusOf(HttpServletRequest request) {
        if (request.getAttribute(RequestDispatcher.ERROR_STATUS_CODE) instanceof Integer code) {
            HttpStatus status = HttpStatus.resolve(code);
            if (status != null && status.isError()) {
                return status;
            }
        }
        return HttpStatus.INTERNAL_SERVER_ERROR;
    }

}
