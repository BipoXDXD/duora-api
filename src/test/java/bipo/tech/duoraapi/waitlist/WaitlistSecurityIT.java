package bipo.tech.duoraapi.waitlist;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.OffsetDateTime;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.servlet.MockMvc;

import bipo.tech.duoraapi.TestcontainersConfiguration;

/**
 * Autorização das rotas da waitlist. O jwt() do MockMvc pula a validação do token, o que basta aqui:
 * a validação de tokens reais fica em BearerTokenValidationIT.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class WaitlistSecurityIT {

    private static final String STATS_PATH = "/api/admin/waitlist/stats";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ApplicationContext context;

    @Autowired
    private JdbcClient jdbcClient;

    @BeforeEach
    void cleanDatabase() {
        jdbcClient.sql("delete from waitlist_entry").update();
    }

    @Test
    void anonymousCannotReadStats() throws Exception {
        mockMvc.perform(get(STATS_PATH))
                .andExpect(status().isUnauthorized())
                .andExpect(content().string(""));
    }

    @Test
    void regularUserCannotReadStats() throws Exception {
        mockMvc.perform(get(STATS_PATH).with(jwt()))
                .andExpect(status().isForbidden())
                .andExpect(content().string(""));
    }

    @Test
    void adminReadsStats() throws Exception {
        insertEntry("ana@example.com");
        insertEntry("bruno@example.com");
        insertEntry("carla@example.com");

        mockMvc.perform(get(STATS_PATH).with(jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))))
                .andExpect(status().isOk())
                .andExpect(content().json("""
                        {"total": 3}
                        """, JsonCompareMode.STRICT));
    }

    @Test
    void noDefaultUserIsProvisioned() {
        assertThat(context.getBeanNamesForType(UserDetailsService.class)).isEmpty();
    }

    @Test
    void anonymousIsRejectedOnRoutesOutsideAllowlist() throws Exception {
        mockMvc.perform(get("/api/anything"))
                .andExpect(status().isUnauthorized());
    }

    private void insertEntry(String email) {
        jdbcClient.sql("insert into waitlist_entry (email, joined_at) values (?, ?)")
                .params(email, OffsetDateTime.parse("2026-10-05T12:00:00Z"))
                .update();
    }

}
