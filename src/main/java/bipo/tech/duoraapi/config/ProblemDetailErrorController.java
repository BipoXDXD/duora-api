package bipo.tech.duoraapi.config;

import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.http.HttpServletRequest;

import org.springframework.boot.webmvc.error.ErrorController;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Responde em {@code ProblemDetail} o que o Tomcat encaminha para /error (exceção não tratada,
 * {@code sendError}), como o resto da API (docs/adr/0005). Substitui o BasicErrorController do Boot,
 * que responde {@code application/json} em outro formato. Só status e título: a causa fica no log.
 */
@RestController
class ProblemDetailErrorController implements ErrorController {

    @RequestMapping("${server.error.path:/error}")
    ResponseEntity<ProblemDetail> error(HttpServletRequest request) {
        HttpStatus status = statusOf(request);
        return ResponseEntity.status(status).body(ProblemDetail.forStatus(status));
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
