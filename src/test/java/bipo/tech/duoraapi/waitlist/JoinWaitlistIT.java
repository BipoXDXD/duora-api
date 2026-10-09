package bipo.tech.duoraapi.waitlist;

import static bipo.tech.duoraapi.ConcurrentCalls.sameCallTogether;
import static bipo.tech.duoraapi.ConcurrentCalls.statusCodeOf;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Locale;
import java.util.Set;
import java.util.stream.Stream;

import jakarta.servlet.http.Cookie;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import bipo.tech.duoraapi.TestcontainersConfiguration;
import bipo.tech.duoraapi.waitlist.domain.WaitlistEntryRepository;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class JoinWaitlistIT {

    /** Cada teste usa o próprio IP: um teste que esgota o limite não afeta os outros. */
    private static final String CLIENT_A = "198.51.100.1";
    private static final String CLIENT_B = "198.51.100.2";
    private static final String CLIENT_C = "198.51.100.3";
    private static final String CLIENT_D = "198.51.100.5";
    private static final String CLIENT_E = "198.51.100.6";
    private static final String CLIENT_F = "198.51.100.7";
    private static final String CLIENT_G = "198.51.100.8";
    /** Mudam a cada resposta por natureza, e não pelo e-mail: o id da requisição e o token CSRF novo. */
    private static final Set<String> PER_RESPONSE_HEADERS = Set.of("x-request-id", "set-cookie");
    private static final int SIMULTANEOUS_JOINS = 4;
    private static final int CAPACITY = 10;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private WaitlistEntryRepository repository;

    @Autowired
    private JdbcClient jdbcClient;

    @BeforeEach
    void cleanDatabase() {
        jdbcClient.sql("delete from waitlist_entry").update();
        jdbcClient.sql("delete from rate_limit_bucket").update();
    }

    @Test
    void joiningTwiceKeepsSingleEntry() throws Exception {
        join("Ana@Example.com", CLIENT_A).andExpect(status().isAccepted());
        join("ana@example.com", CLIENT_A).andExpect(status().isAccepted());

        assertThat(repository.count()).isEqualTo(1);
        assertThat(repository.findByEmail("ana@example.com")).isPresent();
    }

    /** A resposta não revela se o e-mail já estava na lista: mesmo status, headers e corpo vazio. */
    @Test
    void joiningWithAnEmailAlreadyListedLooksLikeJoiningWithANewOne() throws Exception {
        join("ana@example.com", CLIENT_G).andExpect(status().isAccepted());

        var listed = join("ana@example.com", CLIENT_G).andReturn().getResponse();
        var fresh = join("bruno@example.com", CLIENT_G).andReturn().getResponse();

        assertThat(listed.getStatus()).isEqualTo(fresh.getStatus()).isEqualTo(202);
        assertThat(listed.getContentAsString()).isEqualTo(fresh.getContentAsString()).isEmpty();
        assertThat(listed.getHeaderNames()).containsExactlyInAnyOrderElementsOf(fresh.getHeaderNames());
        assertThat(listed.getCookies()).extracting(Cookie::getName)
                .containsExactlyInAnyOrderElementsOf(Stream.of(fresh.getCookies()).map(Cookie::getName).toList());
        for (String name : listed.getHeaderNames()) {
            if (!PER_RESPONSE_HEADERS.contains(name.toLowerCase(Locale.ROOT))) {
                assertThat(listed.getHeaders(name)).as(name).isEqualTo(fresh.getHeaders(name));
            }
        }
    }

    /** A unicidade é do banco (on conflict), e não de um "existe? então insere" no código. */
    @Test
    void simultaneousJoinsWithTheSameEmailKeepSingleEntry() throws Exception {
        var statuses = sameCallTogether(SIMULTANEOUS_JOINS,
                statusCodeOf(() -> join("carla@example.com", CLIENT_F)));

        assertThat(statuses).containsOnly(202);
        assertThat(repository.count()).isEqualTo(1);
    }

    @Test
    void joiningWithMalformedEmailIsRejectedWithoutWriting() throws Exception {
        join("not-an-email", CLIENT_D)
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON));

        assertThat(repository.count()).isZero();
    }

    /** Escapes do JSON: o NUL chegava ao PostgreSQL, que o recusa com exceção (500). */
    @ParameterizedTest
    @ValueSource(strings = {"a\\u0000b@example.com", "a\\u0001b@example.com", "a\\u00a0b@example.com"})
    void joiningWithControlOrInvisibleCharacterIsRejectedWithoutWriting(String jsonEscapedEmail) throws Exception {
        join(jsonEscapedEmail, CLIENT_D)
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON));

        assertThat(repository.count()).isZero();
    }

    @Test
    void joiningWithUnknownFieldIsRejectedWithoutWriting() throws Exception {
        mockMvc.perform(post("/api/waitlist")
                        .with(request -> {
                            request.setRemoteAddr(CLIENT_E);
                            return request;
                        })
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email": "ana@example.com", "joinedAt": "2020-01-01T00:00:00Z"}
                                """))
                .andExpect(status().isBadRequest());

        assertThat(repository.count()).isZero();
    }

    @Test
    void adminSeesEntriesInStats() throws Exception {
        join("ana@example.com", CLIENT_B).andExpect(status().isAccepted());
        join("bruno@example.com", CLIENT_B).andExpect(status().isAccepted());

        mockMvc.perform(get("/api/admin/waitlist/stats").with(jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))))
                .andExpect(status().isOk())
                .andExpect(content().json("""
                        {"total": 2}
                        """));
    }

    @Test
    void joiningAboveRateLimitIsRejectedWithoutWriting() throws Exception {
        for (int i = 0; i < CAPACITY; i++) {
            join("user" + i + "@example.com", CLIENT_C).andExpect(status().isAccepted());
        }

        join("one-too-many@example.com", CLIENT_C)
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"));

        assertThat(repository.count()).isEqualTo(CAPACITY);
        assertThat(repository.findByEmail("one-too-many@example.com")).isEmpty();
    }

    /** Nenhuma grafia alternativa da rota pode chegar ao controller sem passar pelo limite. */
    @ParameterizedTest
    @ValueSource(strings = {"/api/waitlist/", "/api//waitlist", "/api/waitlist;x=1", "/API/waitlist",
            "/api/./waitlist", "/api/x/../waitlist", "/api/waitlist%2F", "/api/%77aitlist"})
    void alternativeSpellingsOfRouteDoNotBypassRateLimit(String path) throws Exception {
        var clientIp = "198.51.100.4";
        for (int i = 0; i < CAPACITY; i++) {
            join("spelling" + i + "@example.com", clientIp);
        }

        var status = mockMvc.perform(post(path)
                        .with(request -> {
                            request.setRemoteAddr(clientIp);
                            return request;
                        })
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email": "bypass@example.com"}
                                """))
                .andReturn().getResponse().getStatus();

        assertThat(status).as("status para %s", path).isNotEqualTo(202);
        assertThat(repository.findByEmail("bypass@example.com")).isEmpty();
    }

    private ResultActions join(String email, String clientIp) throws Exception {
        return mockMvc.perform(post("/api/waitlist")
                .with(request -> {
                    request.setRemoteAddr(clientIp);
                    return request;
                })
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"email": "%s"}
                        """.formatted(email)));
    }

}
