package bipo.tech.duoraapi.events;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * Object Mother dos testes de eventos: quem chama, o corpo de um evento válido e os passos que os
 * testes repetem pela própria API (criar e publicar um evento, completar um perfil). O jwt() pula a
 * validação do token, o que basta aqui: ela está em BearerTokenValidationIT.
 */
final class EventFixtures {

    static final String ADMIN_EVENTS_PATH = "/api/admin/events";
    static final String EVENTS_PATH = "/api/events";
    static final String MY_REGISTRATIONS_PATH = "/api/me/registrations";
    static final String STARTS_AT = "2026-11-01T22:00:00Z";
    static final String ENDS_AT = "2026-11-02T01:00:00Z";
    static final int CAPACITY = 10;

    private static final String ISSUER = "https://tenant-id.ciamlogin.example/tenant-id/v2.0";

    private EventFixtures() {
    }

    static String eventJson() {
        return eventJson(STARTS_AT, ENDS_AT, CAPACITY);
    }

    static String eventJson(String startsAt, String endsAt, int capacity) {
        return """
                {"title": "Noite de jogos", "description": "Jogos de tabuleiro em dupla.",
                 "startsAt": "%s", "endsAt": "%s", "capacity": %d}
                """.formatted(startsAt, endsAt, capacity);
    }

    static RequestPostProcessor admin() {
        return jwt().jwt(token -> token.issuer(ISSUER).claim("oid", "oid-admin"))
                .authorities(new SimpleGrantedAuthority("ROLE_ADMIN"));
    }

    static RequestPostProcessor user(String name) {
        return jwt().jwt(token -> token.issuer(ISSUER).claim("oid", "oid-" + name).claim("name", name));
    }

    static String eventPath(String id) {
        return EVENTS_PATH + "/" + id;
    }

    static String registrationPath(String eventId) {
        return eventPath(eventId) + "/registration";
    }

    static String adminEventPath(String id) {
        return ADMIN_EVENTS_PATH + "/" + id;
    }

    /** Cria um rascunho pela API e devolve o id, lido do Location. */
    static String createDraft(MockMvc mockMvc, String body) throws Exception {
        String location = mockMvc.perform(post(ADMIN_EVENTS_PATH).with(admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getHeader(HttpHeaders.LOCATION);
        return location.substring(location.lastIndexOf('/') + 1);
    }

    static String createPublishedEvent(MockMvc mockMvc) throws Exception {
        return createPublishedEvent(mockMvc, eventJson());
    }

    static String createPublishedEvent(MockMvc mockMvc, String body) throws Exception {
        String id = createDraft(mockMvc, body);
        mockMvc.perform(post(adminEventPath(id) + ":publish").with(admin())).andExpect(status().isOk());
        return id;
    }

    /** Nome, data de nascimento de maior e região: o que o módulo profiles exige para o perfil completo. */
    static void completeProfile(MockMvc mockMvc, RequestPostProcessor user) throws Exception {
        mockMvc.perform(patch("/api/me/profile").with(user)
                        .header(HttpHeaders.IF_MATCH, "\"0\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"displayName": "Pessoa", "birthDate": "1990-05-10", "region": "BR-SP"}
                                """))
                .andExpect(status().isOk());
    }

    /** Inscrições, eventos, perfis e contas, nessa ordem por causa das FKs. */
    static void cleanDatabase(JdbcClient jdbcClient) {
        jdbcClient.sql("delete from registration").update();
        jdbcClient.sql("delete from event").update();
        jdbcClient.sql("delete from profile").update();
        jdbcClient.sql("delete from account").update();
    }

    static String randomId() {
        return UUID.randomUUID().toString();
    }

}
