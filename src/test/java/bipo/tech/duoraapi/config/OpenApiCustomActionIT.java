package bipo.tech.duoraapi.config;

import static bipo.tech.duoraapi.TestIdentities.adminWithoutIdentity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import bipo.tech.duoraapi.TestcontainersConfiguration;
import bipo.tech.testfixtures.routing.ThingActionsController;

/**
 * As ações {@code POST /x/{id}:verbo} (docs/adr/0005) aparecem na spec com o ":" no path e o id como
 * parâmetro de path tipado; sem isso, o cliente gerado do front chamaria a rota errada.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles(ApiDocsAccessIT.API_DOCS_PROFILE)
@Import({TestcontainersConfiguration.class, ThingActionsController.class})
class OpenApiCustomActionIT {

    @Autowired
    private MockMvc mockMvc;

    @ParameterizedTest
    @ValueSource(strings = {"cancel", "accept"})
    void actionKeepsTheColonAndTypesTheId(String verb) throws Exception {
        String operation = "$.paths['/things/{id}:" + verb + "'].post";

        mockMvc.perform(get(ApiDocsAccessIT.SPEC_PATH).with(adminWithoutIdentity()))
                .andExpect(status().isOk())
                .andExpect(jsonPath(operation + ".parameters[0].name").value("id"))
                .andExpect(jsonPath(operation + ".parameters[0].in").value("path"))
                .andExpect(jsonPath(operation + ".parameters[0].required").value(true))
                .andExpect(jsonPath(operation + ".parameters[0].schema.format").value("uuid"));
    }

}
