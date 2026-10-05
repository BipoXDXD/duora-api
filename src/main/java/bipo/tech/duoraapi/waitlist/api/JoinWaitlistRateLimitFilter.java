package bipo.tech.duoraapi.waitlist.api;

import java.io.IOException;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.time.Duration;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

import io.github.bucket4j.BucketConfiguration;
import io.github.bucket4j.distributed.proxy.ProxyManager;

/**
 * Limita o POST público da waitlist por cliente: o IPv4, ou a rede /64 de um IPv6. Os buckets ficam
 * no PostgreSQL (docs/adr/0006), então o limite vale para todas as réplicas juntas.
 */
class JoinWaitlistRateLimitFilter extends OncePerRequestFilter {

    static final String PATH = "/api/waitlist";

    /** Mesmo matcher das regras de autorização: casa como o MVC roteia, descontando o context path. */
    private static final RequestMatcher JOIN_REQUEST =
            PathPatternRequestMatcher.withDefaults().matcher(HttpMethod.POST, PATH);

    /** Separa estes buckets dos de outros limites na mesma tabela. */
    static final String KEY_PREFIX = "join-waitlist:";

    /** Hextetos do prefixo /64, a menor rede que um provedor costuma entregar a um único cliente. */
    private static final int IPV6_NETWORK_HEXTETS = 4;

    private static final long NANOS_PER_SECOND = Duration.ofSeconds(1).toNanos();

    private static final String TOO_MANY_REQUESTS_BODY = """
            {"type":"about:blank","title":"Too Many Requests","status":429}""";

    private final ProxyManager<String> buckets;
    private final BucketConfiguration limit;

    JoinWaitlistRateLimitFilter(ProxyManager<String> buckets, int capacity, Duration period) {
        this.buckets = buckets;
        this.limit = BucketConfiguration.builder()
                .addLimit(bandwidth -> bandwidth.capacity(capacity).refillGreedy(capacity, period))
                .build();
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !JOIN_REQUEST.matches(request);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        var bucket = buckets.getProxy(KEY_PREFIX + clientOf(request.getRemoteAddr()), () -> limit);
        var probe = bucket.tryConsumeAndReturnRemaining(1);
        if (probe.isConsumed()) {
            chain.doFilter(request, response);
            return;
        }
        rejectTooManyRequests(response, probe.getNanosToWaitForRefill());
    }

    /**
     * Quem tem um IPv6 controla a rede /64 inteira: limitar por endereço deixaria trocar de endereço a
     * cada requisição, escapando do limite e enchendo a tabela de buckets.
     */
    private static String clientOf(String remoteAddress) {
        if (!(InetAddress.ofLiteral(remoteAddress) instanceof Inet6Address ipv6)) {
            return remoteAddress;
        }
        byte[] bytes = ipv6.getAddress();
        var network = new StringBuilder();
        for (int hextet = 0; hextet < IPV6_NETWORK_HEXTETS; hextet++) {
            int value = ((bytes[2 * hextet] & 0xff) << 8) | (bytes[2 * hextet + 1] & 0xff);
            network.append(Integer.toHexString(value)).append(':');
        }
        return network.append(":/64").toString();
    }

    private static void rejectTooManyRequests(HttpServletResponse response, long nanosToWait) throws IOException {
        long retryAfterSeconds = Math.ceilDiv(nanosToWait, NANOS_PER_SECOND);
        response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
        response.setHeader(HttpHeaders.RETRY_AFTER, Long.toString(retryAfterSeconds));
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.getWriter().write(TOO_MANY_REQUESTS_BODY);
    }

}
