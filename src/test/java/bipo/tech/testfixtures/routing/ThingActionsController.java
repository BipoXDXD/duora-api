package bipo.tech.testfixtures.routing;

import java.util.UUID;

import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Controller de exemplo para CustomActionRoutingTest. Fica fora de bipo.tech.duoraapi para o
 * component scan dos testes de integração não registrar estas rotas.
 */
@RestController
public class ThingActionsController {

    @PostMapping("/things/{id}:cancel")
    String cancel(@PathVariable UUID id) {
        return "cancel " + id;
    }

    @PostMapping("/things/{id}:accept")
    String accept(@PathVariable UUID id) {
        return "accept " + id;
    }

}
