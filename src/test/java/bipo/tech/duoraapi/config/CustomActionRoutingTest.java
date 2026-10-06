package bipo.tech.duoraapi.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.net.URI;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpMethod;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import bipo.tech.testfixtures.routing.ThingActionsController;

/**
 * Ações de estado são {@code POST /recurso/{id}:verbo} (docs/adr/0005). O ":" fica no mesmo segmento
 * do id, então o Spring MVC e o matcher do Spring Security precisam separar os dois do mesmo jeito.
 */
class CustomActionRoutingTest {

    private static final String ID = "01966c4e-7d1a-7c3e-9b5f-3f2a1c0d9e8b";

    private final MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new ThingActionsController()).build();

    @Test
    void routesActionToItsHandlerWithTheId() throws Exception {
        mockMvc.perform(post("/things/" + ID + ":cancel"))
                .andExpect(status().isOk())
                .andExpect(content().string("cancel " + ID));
    }

    @Test
    void routesEachVerbToItsOwnHandler() throws Exception {
        mockMvc.perform(post("/things/" + ID + ":accept"))
                .andExpect(status().isOk())
                .andExpect(content().string("accept " + ID));
    }

    @ParameterizedTest
    @ValueSource(strings = {"/things/" + ID, "/things/" + ID + ":", "/things/" + ID + ":unknown",
            "/things/" + ID + ":cancel/extra"})
    void rejectsPathsThatAreNotADeclaredAction(String path) throws Exception {
        mockMvc.perform(post(path)).andExpect(status().isNotFound());
    }

    /** O padrão {id}:verbo aceita id vazio; quem barra é o tipo UUID do parâmetro. */
    @ParameterizedTest
    @ValueSource(strings = {"/things/:cancel", "/things/not-a-uuid:cancel"})
    void rejectsActionWithoutAValidId(String path) throws Exception {
        mockMvc.perform(post(path)).andExpect(status().isBadRequest());
    }

    @Test
    void securityMatcherSeesTheSameActionAsTheController() {
        var cancelMatcher = PathPatternRequestMatcher.withDefaults().matcher(HttpMethod.POST, "/things/{id}:cancel");

        assertThat(cancelMatcher.matches(postRequest("/things/" + ID + ":cancel"))).isTrue();
        assertThat(cancelMatcher.matches(postRequest("/things/" + ID + ":accept"))).isFalse();
    }

    /** Se só um dos dois decodificasse o "%3A", a regra de autorização da ação poderia ser contornada. */
    @Test
    void encodedColonIsTreatedAlikeByControllerAndSecurityMatcher() throws Exception {
        var encodedPath = "/things/" + ID + "%3Acancel";
        var cancelMatcher = PathPatternRequestMatcher.withDefaults().matcher(HttpMethod.POST, "/things/{id}:cancel");

        int status = mockMvc.perform(post(URI.create(encodedPath))).andReturn().getResponse().getStatus();
        boolean controllerRouted = status == 200;

        assertThat(cancelMatcher.matches(postRequest(encodedPath))).isEqualTo(controllerRouted);
    }

    private static MockHttpServletRequest postRequest(String path) {
        return new MockHttpServletRequest("POST", path);
    }

}
