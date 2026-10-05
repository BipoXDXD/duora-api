package bipo.tech.duoraapi.waitlist;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import bipo.tech.duoraapi.TestcontainersConfiguration;
import bipo.tech.duoraapi.waitlist.domain.WaitlistEntryRepository;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class JoinWaitlistIT {

    /** Cada teste usa o próprio IP: o filtro de rate limit sobrevive entre testes no contexto em cache. */
    private static final String CLIENT_A = "198.51.100.1";
    private static final String CLIENT_B = "198.51.100.2";
    private static final String CLIENT_C = "198.51.100.3";
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
    }

    @Test
    void joiningTwiceKeepsSingleEntry() throws Exception {
        join("Ana@Example.com", CLIENT_A).andExpect(status().isAccepted());
        join("ana@example.com", CLIENT_A).andExpect(status().isAccepted());

        assertThat(repository.count()).isEqualTo(1);
        assertThat(repository.findByEmail("ana@example.com")).isPresent();
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void adminSeesEntriesInStats() throws Exception {
        join("ana@example.com", CLIENT_B).andExpect(status().isAccepted());
        join("bruno@example.com", CLIENT_B).andExpect(status().isAccepted());

        mockMvc.perform(get("/api/admin/waitlist/stats"))
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
