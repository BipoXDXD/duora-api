package bipo.tech.duoraapi;

import org.springframework.test.json.JsonAssert;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.json.JsonComparator;

import com.jayway.jsonpath.Configuration;
import com.jayway.jsonpath.JsonPath;
import com.jayway.jsonpath.Option;

/**
 * Como os testes comparam o corpo {@code application/problem+json}. O contrato que o cliente lê é {@code status},
 * {@code reason}, {@code errors} e {@code instance}; o {@code detail} é texto para gente, em inglês, e muda
 * quando o texto melhora ou quando o Spring troca a mensagem num upgrade. Por isso, o teste que não é do texto
 * compara o resto em STRICT, que ainda falha se aparecer um campo a mais, e deixa o detail de fora.
 */
public final class ProblemJson {

    private static final JsonComparator STRICT = JsonAssert.comparator(JsonCompareMode.STRICT);
    private static final Configuration TOLERATE_MISSING = Configuration.defaultConfiguration()
            .addOptions(Option.SUPPRESS_EXCEPTIONS);

    private ProblemJson() {
    }

    /** STRICT em tudo, menos no {@code detail}: o campo pode existir na resposta e não precisa estar no esperado. */
    public static JsonComparator strictIgnoringDetail() {
        return (expected, actual) -> STRICT.compare(expected, withoutDetail(actual));
    }

    private static String withoutDetail(String problem) {
        return JsonPath.using(TOLERATE_MISSING).parse(problem).delete("$.detail").jsonString();
    }

}
