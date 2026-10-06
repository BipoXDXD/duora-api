package bipo.tech.duoraapi.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.net.URI;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import bipo.tech.duoraapi.TestcontainersConfiguration;
import bipo.tech.testfixtures.routing.MalformedQueryController;

/**
 * Entrada malformada em path ou query responde 400 em ProblemDetail, sem ecoar o valor e dentro dos
 * limites que a spec declara para o ProblemDetail (docs/adr/0012). O Schemathesis achou os dois casos:
 * {@code ?=null} virava 500, e um id inválido longo voltava inteiro no detail e no instance.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, MalformedQueryController.class})
class MalformedRequestInputIT {

    private static final String ISSUER = "https://tenant-id.ciamlogin.example/tenant-id/v2.0";
    private static final int PROBLEM_TEXT_MAX_LENGTH = 1_000;

    @Autowired
    private MockMvc mockMvc;

    @Test
    void malformedQueryStringIsABadRequest() throws Exception {
        mockMvc.perform(get("/fixtures/malformed-query").with(user()))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(content().json("""
                        {"title": "Bad Request", "status": 400, "detail": "malformed query string",
                         "instance": "/fixtures/malformed-query"}
                        """, JsonCompareMode.STRICT));
    }

    @Test
    void invalidPathValueIsNotEchoed() throws Exception {
        mockMvc.perform(post("/api/accounts/{id}:block", "not-a-uuid-CANARY").with(user()))
                .andExpect(status().isBadRequest())
                .andExpect(content().json("""
                        {"title": "Bad Request", "status": 400, "detail": "accountId has an invalid value",
                         "instance": "/api/accounts/not-a-uuid-CANARY:block"}
                        """, JsonCompareMode.STRICT));
    }

    /** O instance é o path pedido; acima do teto da spec, fica de fora em vez de estourá-lo. */
    @Test
    void overlongInvalidPathStaysWithinTheProblemLimits() throws Exception {
        var response = mockMvc.perform(post(URI.create("/api/accounts/" + "a".repeat(1_100) + ":block")).with(user()))
                .andExpect(status().isBadRequest())
                .andReturn().getResponse().getContentAsString();

        assertThat(response).doesNotContain("a".repeat(PROBLEM_TEXT_MAX_LENGTH));
        assertThat(response.length()).isLessThan(PROBLEM_TEXT_MAX_LENGTH);
    }

    private static RequestPostProcessor user() {
        return jwt().jwt(token -> token.issuer(ISSUER).claim("oid", "oid-malformed-input"));
    }

}
