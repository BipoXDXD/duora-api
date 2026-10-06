package bipo.tech.duoraapi.config;

import java.io.IOException;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.filter.ServerHttpObservationFilter;

/**
 * Registra a exceção que escapou de toda a aplicação ainda dentro do trace da requisição, para que o
 * stack trace saia no log com o correlation ID (docs/adr/0013). Sem isto, quem a registraria é o
 * Tomcat, depois que o trace já fechou. Em seguida responde 500 pelo /error, que monta o
 * {@code ProblemDetail} sem a causa.
 */
final class UnhandledExceptionLoggingFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(UnhandledExceptionLoggingFilter.class);

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        try {
            chain.doFilter(request, response);
        } catch (ServletException | IOException | RuntimeException exception) {
            log.error("Unhandled exception on {} {}", request.getMethod(), request.getRequestURI(), exception);
            if (response.isCommitted()) {
                throw exception;
            }
            // A métrica http.server.requests continua com a exceção, como se ela tivesse chegado ao Tomcat.
            ServerHttpObservationFilter.findObservationContext(request)
                    .ifPresent(context -> context.setError(exception));
            response.sendError(HttpStatus.INTERNAL_SERVER_ERROR.value());
        }
    }

}
