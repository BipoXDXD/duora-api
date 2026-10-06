package bipo.tech.duoraapi.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import jakarta.servlet.http.HttpServletResponse;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;

/**
 * Recusas da segurança (401 e 403) em {@code ProblemDetail}, como o resto da API (docs/adr/0005).
 * O handler original continua decidindo o status e os headers, como o {@code WWW-Authenticate} da
 * porta bearer; aqui só entra o corpo. Sem detalhe: o motivo da recusa não vai para o cliente.
 */
final class ProblemDetailSecurityResponses {

    private ProblemDetailSecurityResponses() {
    }

    static AuthenticationEntryPoint problemEntryPoint(AuthenticationEntryPoint delegate) {
        return (request, response, exception) -> {
            delegate.commence(request, response, exception);
            writeProblemBody(response);
        };
    }

    static AccessDeniedHandler problemAccessDeniedHandler(AccessDeniedHandler delegate) {
        return (request, response, exception) -> {
            delegate.handle(request, response, exception);
            writeProblemBody(response);
        };
    }

    /** Status, e não sendError: sendError encaminharia para /error e trocaria o corpo. */
    static AccessDeniedHandler forbidden() {
        return (request, response, exception) -> response.setStatus(HttpStatus.FORBIDDEN.value());
    }

    private static void writeProblemBody(HttpServletResponse response) throws IOException {
        HttpStatus status = HttpStatus.valueOf(response.getStatus());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        // Bytes em UTF-8, como o JSON exige (RFC 8259), e Content-Type sem charset, como o resto da API:
        // o getWriter() faria o Tomcat declarar e usar o ISO-8859-1 padrão dele.
        response.getOutputStream().write("""
                {"type":"about:blank","title":"%s","status":%d}""".formatted(status.getReasonPhrase(), status.value())
                .getBytes(StandardCharsets.UTF_8));
    }

}
