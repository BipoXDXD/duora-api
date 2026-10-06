package bipo.tech.duoraapi.config;

import java.util.List;
import java.util.regex.Pattern;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.session.web.http.CookieSerializer;

/**
 * Só entrega ao Spring Session os ids de sessão no formato que ele mesmo gera (UUID). O resto vale
 * como cookie ausente: um id com NUL, por exemplo, ia direto à consulta no PostgreSQL, que o recusa
 * com exceção, e qualquer rota respondia 500.
 */
final class WellFormedSessionIdCookieSerializer implements CookieSerializer {

    /** Forma canônica e minúscula de {@code UUID.toString()}, a do gerador padrão do Spring Session. */
    private static final Pattern SESSION_ID =
            Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");

    private final CookieSerializer delegate;

    WellFormedSessionIdCookieSerializer(CookieSerializer delegate) {
        this.delegate = delegate;
    }

    @Override
    public void writeCookieValue(CookieValue cookieValue) {
        delegate.writeCookieValue(cookieValue);
    }

    @Override
    public List<String> readCookieValues(HttpServletRequest request) {
        return delegate.readCookieValues(request).stream()
                .filter(sessionId -> SESSION_ID.matcher(sessionId).matches())
                .toList();
    }

}
