package bipo.tech.testfixtures.routing;

import org.apache.tomcat.util.http.InvalidParameterException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Simula o que o Tomcat faz ao ler uma query string malformada ({@code ?=null}, chave vazia): lança
 * InvalidParameterException na resolução de um {@code @RequestParam}. O MockMvc não usa o parser do
 * Tomcat, então o fixture lança a mesma exceção. Fora de bipo.tech.duoraapi, como ThingActionsController.
 */
@RestController
public class MalformedQueryController {

    @GetMapping("/fixtures/malformed-query")
    String malformedQuery() {
        throw new InvalidParameterException("Invalid chunk starting at byte [0] and ending at byte [5]");
    }

}
