package bipo.tech.duoraapi.waitlist.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import bipo.tech.duoraapi.config.SecurityConfiguration;
import bipo.tech.duoraapi.waitlist.application.WaitlistService;

@WebMvcTest(WaitlistController.class)
@Import(SecurityConfiguration.class)
class WaitlistSecurityTest {

    private static final String STATS_PATH = "/api/admin/waitlist/stats";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ApplicationContext context;

    @MockitoBean
    private WaitlistService waitlistService;

    @Test
    void anonymousCannotReadStats() throws Exception {
        mockMvc.perform(get(STATS_PATH))
                .andExpect(status().isUnauthorized())
                .andExpect(content().string(""));
    }

    @Test
    @WithMockUser(roles = "USER")
    void regularUserCannotReadStats() throws Exception {
        mockMvc.perform(get(STATS_PATH))
                .andExpect(status().isForbidden())
                .andExpect(content().string(""));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void adminReadsStats() throws Exception {
        given(waitlistService.countEntries()).willReturn(3L);

        mockMvc.perform(get(STATS_PATH))
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

}
