package bipo.tech.duoraapi.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.health.actuate.endpoint.HealthEndpointGroups;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import bipo.tech.duoraapi.TestcontainersConfiguration;

/**
 * Probes do Azure Container Apps (infra/azure): a plataforma chama sem credencial, então liveness e
 * readiness são públicas e respondem só o estado. A readiness inclui o banco, a liveness não
 * (docs/adr/0011).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class HealthProbesIT {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private HealthEndpointGroups healthGroups;

    @ParameterizedTest
    @ValueSource(strings = {"/actuator/health/liveness", "/actuator/health/readiness"})
    void probeAnswersWithoutCredentialsAndShowsOnlyTheStatus(String probe) throws Exception {
        var response = mockMvc.perform(get(probe)).andReturn().getResponse();

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getContentAsString()).isEqualTo("{\"status\":\"UP\"}");
    }

    @Test
    void readinessDependsOnTheDatabaseAndLivenessDoesNot() {
        assertThat(healthGroups.get("readiness").isMember("db")).isTrue();
        assertThat(healthGroups.get("liveness").isMember("db")).isFalse();
    }

}
