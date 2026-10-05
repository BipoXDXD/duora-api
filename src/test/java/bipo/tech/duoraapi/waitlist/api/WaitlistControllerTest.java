package bipo.tech.duoraapi.waitlist.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import bipo.tech.duoraapi.config.SecurityConfiguration;
import bipo.tech.duoraapi.waitlist.application.WaitlistService;
import bipo.tech.duoraapi.waitlist.domain.EmailAddress;

@WebMvcTest(WaitlistController.class)
@Import(SecurityConfiguration.class)
class WaitlistControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private WaitlistService waitlistService;

    @Test
    void joinAcceptsValidEmail() throws Exception {
        mockMvc.perform(post("/api/waitlist")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email": "Ana@Example.com"}
                                """))
                .andExpect(status().isAccepted());

        then(waitlistService).should().join(new EmailAddress("ana@example.com"));
    }

    @Test
    void joinRejectsMalformedEmail() throws Exception {
        mockMvc.perform(post("/api/waitlist")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email": "not-an-email"}
                                """))
                .andExpect(status().isBadRequest());

        then(waitlistService).should(never()).join(any());
    }

    @Test
    void joinRejectsUnknownField() throws Exception {
        mockMvc.perform(post("/api/waitlist")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email": "ana@example.com", "joinedAt": "2020-01-01T00:00:00Z"}
                                """))
                .andExpect(status().isBadRequest());

        then(waitlistService).should(never()).join(any());
    }

}
