package bipo.tech.duoraapi.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.UnaryOperator;
import java.util.stream.Stream;

import jakarta.servlet.http.Cookie;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.util.UriComponents;
import org.springframework.web.util.UriComponentsBuilder;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import bipo.tech.duoraapi.TestcontainersConfiguration;

/**
 * Fluxo de login do front web (BFF, docs/adr/0002) de ponta a ponta: o Entra External ID é
 * substituído por um servidor local que entrega token e JWKS, e a sessão fica no PostgreSQL real.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class WebLoginIT {

    private static final String SESSION_COOKIE = "__Host-DUORA_SESSION";
    private static final String CSRF_COOKIE = "XSRF-TOKEN";
    private static final String CSRF_HEADER = "X-XSRF-TOKEN";
    private static final String ADMIN_ONLY_PATH = "/api/admin/waitlist/stats";

    private static final String ISSUER = "https://tenant-id.ciamlogin.example/tenant-id/v2.0";
    private static final String WEB_CLIENT_ID = "duora-web-client-id";
    private static final String API_AUDIENCE = "duora-api-client-id";
    private static final String USER_OBJECT_ID = "user-object-id";
    private static final String KEY_ID = "signing-key";

    private static final RSAKey SIGNING_KEY = generateRsaKey();
    private static final RSAKey FOREIGN_KEY = generateRsaKey();
    /** Resposta que o endpoint de token simulado entrega na próxima troca de código. */
    private static final AtomicReference<String> NEXT_TOKEN_RESPONSE = new AtomicReference<>();
    private static final HttpServer IDENTITY_PROVIDER = startIdentityProvider();
    private static final String AUTHORITY = "http://127.0.0.1:" + IDENTITY_PROVIDER.getAddress().getPort() + "/tenant";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcClient jdbcClient;

    @DynamicPropertySource
    static void authenticationProperties(DynamicPropertyRegistry registry) {
        registry.add("DUORA_AUTH_AUTHORITY", () -> AUTHORITY);
        registry.add("DUORA_AUTH_ISSUER_URI", () -> ISSUER);
        registry.add("DUORA_AUTH_JWK_SET_URI", () -> AUTHORITY + "/discovery/v2.0/keys");
        registry.add("DUORA_AUTH_AUDIENCE", () -> API_AUDIENCE);
        registry.add("DUORA_AUTH_WEB_CLIENT_ID", () -> WEB_CLIENT_ID);
    }

    @AfterAll
    static void stopIdentityProvider() {
        IDENTITY_PROVIDER.stop(0);
    }

    @Test
    void loginRedirectsToFrontAndRotatesSessionId() throws Exception {
        var start = startLogin();

        var callback = completeLogin(start, UnaryOperator.identity(), UnaryOperator.identity());

        assertThat(callback.getResponse().getRedirectedUrl()).isEqualTo("/");
        assertThat(sessionCookieOf(callback).getValue()).isNotEqualTo(start.session().getValue());
    }

    @Test
    void loggedInAdminReadsStatsWithSessionCookie() throws Exception {
        var session = logIn();

        mockMvc.perform(get(ADMIN_ONLY_PATH).cookie(session)).andExpect(status().isOk());
    }

    @Test
    void preLoginSessionIsUselessAfterLogin() throws Exception {
        var start = startLogin();
        completeLogin(start, UnaryOperator.identity(), UnaryOperator.identity());

        mockMvc.perform(get(ADMIN_ONLY_PATH).cookie(start.session())).andExpect(status().isUnauthorized());
    }

    @Test
    void sessionIsStoredInDatabaseUnderTheUserObjectId() throws Exception {
        logIn();

        var sessions = jdbcClient.sql("select count(*) from spring_session where principal_name = ?")
                .param(USER_OBJECT_ID)
                .query(Long.class)
                .single();
        assertThat(sessions).isPositive();
    }

    @Test
    void sessionCookieIsHostOnlyHttpOnlySecureAndLax() throws Exception {
        var callback = completeLogin(startLogin(), UnaryOperator.identity(), UnaryOperator.identity());

        var setCookie = callback.getResponse().getHeaders(HttpHeaders.SET_COOKIE).stream()
                .filter(header -> header.startsWith(SESSION_COOKIE + "="))
                .findFirst()
                .orElseThrow();
        assertThat(setCookie)
                .contains("; Path=/")
                .contains("; HttpOnly")
                .contains("; Secure")
                .contains("; SameSite=Lax")
                .doesNotContainIgnoringCase("Domain=");
    }

    @Test
    void logoutWithoutCsrfTokenIsRejectedAndKeepsSession() throws Exception {
        var session = logIn();

        mockMvc.perform(post("/logout").cookie(session)).andExpect(status().isForbidden());

        mockMvc.perform(get(ADMIN_ONLY_PATH).cookie(session)).andExpect(status().isOk());
    }

    @Test
    void logoutEndsSessionHereAndAtEntra() throws Exception {
        var session = logIn();
        var csrf = csrfCookie(session);

        var logout = mockMvc.perform(post("/logout").cookie(session, csrf).header(CSRF_HEADER, csrf.getValue()))
                .andExpect(status().is3xxRedirection())
                .andReturn();

        var entraLogout = UriComponentsBuilder.fromUriString(logout.getResponse().getRedirectedUrl()).build();
        assertThat(entraLogout.getPath()).isEqualTo("/tenant/oauth2/v2.0/logout");
        assertThat(entraLogout.getQueryParams()).containsKeys("id_token_hint", "post_logout_redirect_uri");
        mockMvc.perform(get(ADMIN_ONLY_PATH).cookie(session)).andExpect(status().isUnauthorized());
    }

    @ParameterizedTest
    @MethodSource("rejectedLogins")
    void rejectsLoginWithInvalidTokens(UnaryOperator<JWTClaimsSet.Builder> idToken,
            UnaryOperator<JWTClaimsSet.Builder> accessToken) throws Exception {
        var start = startLogin();

        var callback = completeLogin(start, idToken, accessToken);

        assertThat(callback.getResponse().getRedirectedUrl()).isEqualTo("/?loginError");
        assertThat(callback.getResponse().getCookie(SESSION_COOKIE)).as("sessão nova após login recusado").isNull();
        mockMvc.perform(get(ADMIN_ONLY_PATH).cookie(start.session())).andExpect(status().isUnauthorized());
    }

    static Stream<Arguments> rejectedLogins() {
        UnaryOperator<JWTClaimsSet.Builder> unchanged = UnaryOperator.identity();
        return Stream.of(
                Arguments.of(Named.of("ID token de outro tenant",
                        (UnaryOperator<JWTClaimsSet.Builder>) claims -> claims.issuer("https://other.example/v2.0")), unchanged),
                Arguments.of(Named.of("ID token de outro app",
                        (UnaryOperator<JWTClaimsSet.Builder>) claims -> claims.audience("other-client-id")), unchanged),
                Arguments.of(Named.of("nonce de outra tentativa de login",
                        (UnaryOperator<JWTClaimsSet.Builder>) claims -> claims.claim("nonce", "replayed-nonce")), unchanged),
                Arguments.of(Named.of("ID token sem oid",
                        (UnaryOperator<JWTClaimsSet.Builder>) claims -> claims.claim("oid", null)), unchanged),
                Arguments.of(Named.of("access token de outra API", unchanged),
                        (UnaryOperator<JWTClaimsSet.Builder>) claims -> claims.audience("other-api")),
                Arguments.of(Named.of("access token de outro usuário", unchanged),
                        (UnaryOperator<JWTClaimsSet.Builder>) claims -> claims.claim("oid", "someone-else")),
                Arguments.of(Named.of("access token assinado por outra chave", unchanged),
                        (UnaryOperator<JWTClaimsSet.Builder>) claims -> claims.claim("signWithForeignKey", true)));
    }

    private Cookie logIn() throws Exception {
        return sessionCookieOf(completeLogin(startLogin(), UnaryOperator.identity(), UnaryOperator.identity()));
    }

    private LoginStart startLogin() throws Exception {
        var redirect = mockMvc.perform(get(WebLoginConfiguration.LOGIN_PATH))
                .andExpect(status().is3xxRedirection())
                .andReturn();
        UriComponents authorize = UriComponentsBuilder.fromUriString(redirect.getResponse().getRedirectedUrl()).build();
        assertThat(authorize.getPath()).isEqualTo("/tenant/oauth2/v2.0/authorize");
        return new LoginStart(sessionCookieOf(redirect), queryParam(authorize, "state"), queryParam(authorize, "nonce"));
    }

    private MvcResult completeLogin(LoginStart start, UnaryOperator<JWTClaimsSet.Builder> idTokenChange,
            UnaryOperator<JWTClaimsSet.Builder> accessTokenChange) throws Exception {
        var idToken = idTokenChange.apply(baseClaims().audience(WEB_CLIENT_ID).claim("nonce", start.nonce())).build();
        var accessToken = accessTokenChange.apply(baseClaims().audience(API_AUDIENCE).claim("roles", List.of("ADMIN"))).build();
        NEXT_TOKEN_RESPONSE.set("""
                {"token_type": "Bearer", "expires_in": 3600, "id_token": "%s", "access_token": "%s"}
                """.formatted(sign(idToken), sign(accessToken)));
        return mockMvc.perform(get("/login/oauth2/code/" + WebLoginConfiguration.REGISTRATION_ID)
                        .param("code", "authorization-code")
                        .param("state", start.state())
                        .cookie(start.session()))
                .andExpect(status().is3xxRedirection())
                .andReturn();
    }

    /** Uma leitura qualquer faz o Spring emitir o cookie do token CSRF, como o front faria. */
    private Cookie csrfCookie(Cookie session) throws Exception {
        var result = mockMvc.perform(get(ADMIN_ONLY_PATH).cookie(session)).andReturn();
        var csrf = result.getResponse().getCookie(CSRF_COOKIE);
        assertThat(csrf).as("cookie %s", CSRF_COOKIE).isNotNull();
        return csrf;
    }

    private static Cookie sessionCookieOf(MvcResult result) {
        var cookie = result.getResponse().getCookie(SESSION_COOKIE);
        assertThat(cookie).as("cookie %s", SESSION_COOKIE).isNotNull();
        return cookie;
    }

    private static String queryParam(UriComponents uri, String name) {
        var value = uri.getQueryParams().getFirst(name);
        assertThat(value).as("parâmetro %s", name).isNotNull();
        return java.net.URLDecoder.decode(value, StandardCharsets.UTF_8);
    }

    private static JWTClaimsSet.Builder baseClaims() {
        var now = Instant.now();
        return new JWTClaimsSet.Builder()
                .issuer(ISSUER)
                .subject("pairwise-subject")
                .claim("oid", USER_OBJECT_ID)
                .issueTime(Date.from(now.minus(Duration.ofMinutes(1))))
                .expirationTime(Date.from(now.plus(Duration.ofMinutes(10))));
    }

    /** A claim fictícia signWithForeignKey só escolhe a chave no teste; ela sai do token. */
    private static String sign(JWTClaimsSet claims) {
        var foreign = Boolean.TRUE.equals(claims.getClaim("signWithForeignKey"));
        var finalClaims = new JWTClaimsSet.Builder(claims).claim("signWithForeignKey", null).build();
        var jwt = new SignedJWT(
                new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(KEY_ID).type(JOSEObjectType.JWT).build(), finalClaims);
        try {
            jwt.sign(new RSASSASigner(foreign ? FOREIGN_KEY : SIGNING_KEY));
        } catch (JOSEException e) {
            throw new IllegalStateException("could not sign test token", e);
        }
        return jwt.serialize();
    }

    private static RSAKey generateRsaKey() {
        try {
            return new RSAKeyGenerator(2048).keyID(KEY_ID).generate();
        } catch (JOSEException e) {
            throw new IllegalStateException("could not generate test key", e);
        }
    }

    private static HttpServer startIdentityProvider() {
        var jwks = new JWKSet(SIGNING_KEY.toPublicJWK()).toString();
        try {
            var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/tenant/discovery/v2.0/keys", exchange -> respondJson(exchange, jwks));
            server.createContext("/tenant/oauth2/v2.0/token", exchange -> respondJson(exchange, NEXT_TOKEN_RESPONSE.get()));
            server.start();
            return server;
        } catch (IOException e) {
            throw new UncheckedIOException("could not start identity provider stub", e);
        }
    }

    private static void respondJson(HttpExchange exchange, String json) throws IOException {
        var body = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, body.length);
        try (var out = exchange.getResponseBody()) {
            out.write(body);
        }
    }

    private record LoginStart(Cookie session, String state, String nonce) {
    }

}
