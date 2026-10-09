package bipo.tech.duoraapi.config;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;

import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import org.springframework.http.HttpMethod;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.RequestMappingInfoHandlerMapping;

/**
 * As rotas que o Spring MVC registrou, como "MÉTODO /padrão", para os testes que varrem a API inteira: uma
 * rota nova entra na varredura sem que ninguém precise lembrar de listá-la.
 */
final class RegisteredRoutes {

    private static final String PATH_VARIABLE_VALUE = "01966c4e-7d1a-7c3e-9b5f-3f2a1c0d9e8b";
    private static final String WILDCARD_SEGMENT_VALUE = "any";

    private RegisteredRoutes() {
    }

    /** Mapeamento sem método declarado aceita todos e entra como GET. */
    static Set<String> of(List<RequestMappingInfoHandlerMapping> handlerMappings) {
        var routes = new TreeSet<String>();
        for (var mapping : handlerMappings) {
            for (RequestMappingInfo info : mapping.getHandlerMethods().keySet()) {
                Collection<RequestMethod> methods = info.getMethodsCondition().getMethods();
                Collection<RequestMethod> declared = methods.isEmpty() ? List.of(RequestMethod.GET) : methods;
                for (String pattern : info.getPatternValues()) {
                    declared.forEach(method -> routes.add(method + " " + pattern));
                }
            }
        }
        return routes;
    }

    /** Variáveis de rota viram um UUID qualquer, e curingas, um segmento qualquer. */
    static MockHttpServletRequestBuilder requestFor(String route) {
        String[] methodAndPattern = route.split(" ", 2);
        String path = methodAndPattern[1]
                .replaceAll("\\{[^}]*}", PATH_VARIABLE_VALUE)
                .replace("**", WILDCARD_SEGMENT_VALUE)
                .replace("*", WILDCARD_SEGMENT_VALUE);
        return request(HttpMethod.valueOf(methodAndPattern[0]), path);
    }

    static String methodOf(String route) {
        return route.substring(0, route.indexOf(' '));
    }

    static String patternOf(String route) {
        return route.substring(route.indexOf(' ') + 1);
    }

}
