package bipo.tech.duoraapi.waitlist.api;

import java.io.IOException;
import java.time.Duration;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;

/**
 * Limita o POST público da waitlist por IP do cliente. Os buckets ficam em memória, então o
 * limite vale por instância; com várias instâncias, trocar para um backend compartilhado do Bucket4j.
 */
class JoinWaitlistRateLimitFilter extends OncePerRequestFilter {

    static final String PATH = "/api/waitlist";

    /** Teto de IPs acompanhados ao mesmo tempo, para a memória não crescer sob ataque distribuído. */
    private static final long MAX_TRACKED_CLIENTS = 100_000;

    private static final long NANOS_PER_SECOND = Duration.ofSeconds(1).toNanos();

    private static final String TOO_MANY_REQUESTS_BODY = """
            {"type":"about:blank","title":"Too Many Requests","status":429}""";

    private final Bandwidth limit;
    private final Cache<String, Bucket> bucketsByClient;

    JoinWaitlistRateLimitFilter(int capacity, Duration period) {
        this.limit = Bandwidth.builder().capacity(capacity).refillGreedy(capacity, period).build();
        this.bucketsByClient = Caffeine.newBuilder()
                .maximumSize(MAX_TRACKED_CLIENTS)
                .expireAfterAccess(period)
                .build();
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !(HttpMethod.POST.matches(request.getMethod()) && PATH.equals(request.getRequestURI()));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        var bucket = bucketsByClient.get(request.getRemoteAddr(), client -> Bucket.builder().addLimit(limit).build());
        var probe = bucket.tryConsumeAndReturnRemaining(1);
        if (probe.isConsumed()) {
            chain.doFilter(request, response);
            return;
        }
        rejectTooManyRequests(response, probe.getNanosToWaitForRefill());
    }

    private static void rejectTooManyRequests(HttpServletResponse response, long nanosToWait) throws IOException {
        long retryAfterSeconds = Math.ceilDiv(nanosToWait, NANOS_PER_SECOND);
        response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
        response.setHeader(HttpHeaders.RETRY_AFTER, Long.toString(retryAfterSeconds));
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.getWriter().write(TOO_MANY_REQUESTS_BODY);
    }

}
