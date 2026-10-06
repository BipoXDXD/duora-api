package bipo.tech.duoraapi.config;

import java.io.IOException;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.web.filter.OncePerRequestFilter;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;

/**
 * Devolve ao cliente o correlation ID da requisição: o trace id W3C, o mesmo que vai em cada linha
 * de log (docs/adr/0011). Quem reporta um erro informa esse valor, e o suporte acha o log.
 * Roda depois da observação HTTP, que abre o trace, e antes da segurança, para que as recusas
 * também levem o header.
 */
final class RequestIdResponseFilter extends OncePerRequestFilter {

    static final String HEADER = "X-Request-Id";

    private final Tracer tracer;

    RequestIdResponseFilter(Tracer tracer) {
        this.tracer = tracer;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Span span = tracer.currentSpan();
        if (span != null) {
            response.setHeader(HEADER, span.context().traceId());
        }
        chain.doFilter(request, response);
    }

}
