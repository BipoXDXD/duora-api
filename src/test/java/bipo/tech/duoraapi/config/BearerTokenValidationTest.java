package bipo.tech.duoraapi.config;

import static org.hamcrest.Matchers.startsWith;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.PlainJWT;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpServer;

import bipo.tech.duoraapi.waitlist.application.WaitlistService;

/**
 * Valida tokens de verdade contra a configuração de produção (issuer, audience, algoritmo, roles),
 * com um JWKS local no lugar do Entra External ID. Teste de fronteira: ver docs/adr/0001.
 */
@WebMvcTest
@Import(SecurityConfiguration.class)
class BearerTokenValidationTest {

    private static final String ISSUER = "https://tenant-id.ciamlogin.example/tenant-id/v2.0";
    private static final String AUDIENCE = "duora-api-client-id";
    private static final String KEY_ID = "signing-key";
    private static final String ADMIN_ONLY_PATH = "/api/admin/waitlist/stats";

    private static final RSAKey SIGNING_KEY = generateRsaKey();
    /** Mesmo kid da chave publicada, mas outro par: simula um emissor forjado. */
    private static final RSAKey FOREIGN_KEY = generateRsaKey();
    private static final HttpServer JWKS_SERVER = startJwksServer();

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private WaitlistService waitlistService;

    @DynamicPropertySource
    static void authenticationProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.security.oauth2.resourceserver.jwt.issuer-uri", () -> ISSUER);
        registry.add("duora.auth.audience", () -> AUDIENCE);
        registry.add("spring.security.oauth2.resourceserver.jwt.jwk-set-uri",
                () -> "http://127.0.0.1:" + JWKS_SERVER.getAddress().getPort() + "/keys");
    }

    @AfterAll
    static void stopJwksServer() {
        JWKS_SERVER.stop(0);
    }

    @Test
    void acceptsValidTokenWithAdminRole() throws Exception {
        given(waitlistService.countEntries()).willReturn(3L);

        mockMvc.perform(get(ADMIN_ONLY_PATH).header(HttpHeaders.AUTHORIZATION, bearer(signed(adminClaims().build()))))
                .andExpect(status().isOk())
                .andExpect(content().json("""
                        {"total": 3}
                        """));
    }

    @Test
    void forbidsValidTokenWithoutAdminRole() throws Exception {
        given(waitlistService.countEntries()).willReturn(3L);

        mockMvc.perform(get(ADMIN_ONLY_PATH).header(HttpHeaders.AUTHORIZATION, bearer(signed(userClaims().build()))))
                .andExpect(status().isForbidden())
                .andExpect(content().string(""));
    }

    @Test
    void rejectsRequestWithoutToken() throws Exception {
        mockMvc.perform(get(ADMIN_ONLY_PATH))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, startsWith("Bearer")))
                .andExpect(content().string(""));
    }

    @ParameterizedTest
    @MethodSource("invalidTokens")
    void rejectsInvalidToken(String token) throws Exception {
        given(waitlistService.countEntries()).willReturn(3L);

        mockMvc.perform(get(ADMIN_ONLY_PATH).header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isUnauthorized())
                .andExpect(content().string(""));
    }

    static Stream<Named<String>> invalidTokens() {
        var now = Instant.now();
        return Stream.of(
                Named.of("expirado", signed(adminClaims()
                        .issueTime(Date.from(now.minus(Duration.ofHours(2))))
                        .expirationTime(Date.from(now.minus(Duration.ofHours(1))))
                        .build())),
                // Um ID token do app cliente tem como audience o client id do front, não a API.
                Named.of("audience de outro app", signed(adminClaims().audience("frontend-client-id").build())),
                Named.of("issuer de outro tenant", signed(adminClaims()
                        .issuer("https://other-tenant.ciamlogin.example/other-tenant/v2.0").build())),
                Named.of("sem expiração", signed(adminClaims().expirationTime(null).build())),
                Named.of("assinado por outra chave com o mesmo kid", signedWith(FOREIGN_KEY, adminClaims().build())),
                Named.of("payload adulterado", tamperedToAdmin()),
                Named.of("alg none", new PlainJWT(adminClaims().build()).serialize()),
                Named.of("alg nonE", unsignedWithHeader("{\"alg\":\"nonE\",\"typ\":\"JWT\"}")),
                Named.of("HS256 com a chave pública como segredo", hmacSignedWithPublicKey()),
                Named.of("lixo", "not-a-jwt"));
    }

    private static JWTClaimsSet.Builder userClaims() {
        var now = Instant.now();
        return new JWTClaimsSet.Builder()
                .issuer(ISSUER)
                .audience(AUDIENCE)
                .subject("user-object-id")
                .issueTime(Date.from(now.minus(Duration.ofMinutes(1))))
                .expirationTime(Date.from(now.plus(Duration.ofMinutes(10))));
    }

    private static JWTClaimsSet.Builder adminClaims() {
        return userClaims().claim("roles", List.of("ADMIN"));
    }

    private static String signed(JWTClaimsSet claims) {
        return signedWith(SIGNING_KEY, claims);
    }

    private static String signedWith(RSAKey key, JWTClaimsSet claims) {
        var header = new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(KEY_ID).type(JOSEObjectType.JWT).build();
        var jwt = new SignedJWT(header, claims);
        try {
            jwt.sign(new RSASSASigner(key));
        } catch (JOSEException e) {
            throw new IllegalStateException("could not sign test token", e);
        }
        return jwt.serialize();
    }

    /** Assinatura válida de um token sem papel, com o payload trocado por um que tem ADMIN. */
    private static String tamperedToAdmin() {
        var userParts = signed(userClaims().build()).split("\\.");
        var adminParts = signed(adminClaims().build()).split("\\.");
        return userParts[0] + "." + adminParts[1] + "." + userParts[2];
    }

    private static String unsignedWithHeader(String headerJson) {
        var encoder = Base64.getUrlEncoder().withoutPadding();
        var header = encoder.encodeToString(headerJson.getBytes(StandardCharsets.UTF_8));
        var payload = encoder.encodeToString(adminClaims().build().toString().getBytes(StandardCharsets.UTF_8));
        return header + "." + payload + ".";
    }

    /** Ataque de confusão de algoritmo: HMAC usando como segredo a chave pública publicada. */
    private static String hmacSignedWithPublicKey() {
        var header = new JWSHeader.Builder(JWSAlgorithm.HS256).keyID(KEY_ID).type(JOSEObjectType.JWT).build();
        var jwt = new SignedJWT(header, adminClaims().build());
        try {
            jwt.sign(new MACSigner(SIGNING_KEY.toRSAPublicKey().getEncoded()));
        } catch (JOSEException e) {
            throw new IllegalStateException("could not sign test token", e);
        }
        return jwt.serialize();
    }

    private static String bearer(String token) {
        return "Bearer " + token;
    }

    private static RSAKey generateRsaKey() {
        try {
            return new RSAKeyGenerator(2048).keyID(KEY_ID).generate();
        } catch (JOSEException e) {
            throw new IllegalStateException("could not generate test key", e);
        }
    }

    private static HttpServer startJwksServer() {
        var jwks = new JWKSet(SIGNING_KEY.toPublicJWK()).toString().getBytes(StandardCharsets.UTF_8);
        try {
            var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/keys", exchange -> {
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, jwks.length);
                try (var body = exchange.getResponseBody()) {
                    body.write(jwks);
                }
            });
            server.start();
            return server;
        } catch (IOException e) {
            throw new UncheckedIOException("could not start JWKS server", e);
        }
    }

}
