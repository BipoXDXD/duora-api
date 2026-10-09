package bipo.tech.duoraapi.config;

import java.net.URI;

import jakarta.servlet.http.HttpServletRequest;

import org.apache.tomcat.util.http.InvalidParameterException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/**
 * Entrada malformada em path ou query é 400 em ProblemDetail, sem ecoar o valor recebido e dentro dos
 * limites que a spec declara para o ProblemDetail (docs/adr/0012). Antes do tratamento padrão do Spring,
 * que repetia o valor no detail, e no lugar do 500 que a InvalidParameterException do Tomcat virava.
 */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
class MalformedRequestInputHandler {

    /** O Tomcat recusa a query string ({@code ?=null}, chave vazia) quando o handler lê um parâmetro. */
    @ExceptionHandler(InvalidParameterException.class)
    ProblemDetail handleMalformedQuery(HttpServletRequest request) {
        return badRequest("malformed query string", request);
    }

    /** Path ou query que não converte para o tipo do parâmetro (um id que não é UUID, por exemplo). */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    ProblemDetail handleTypeMismatch(MethodArgumentTypeMismatchException exception, HttpServletRequest request) {
        return badRequest(exception.getName() + " has an invalid value", request);
    }

    /** maxPageSize ou pageToken de uma lista paginada ({@link MaxPageSize}, {@link KeysetPageToken}). */
    @ExceptionHandler(InvalidPageParameterException.class)
    ProblemDetail handleInvalidPageParameter(InvalidPageParameterException exception, HttpServletRequest request) {
        return badRequest(exception.getMessage(), request);
    }

    /**
     * O instance é o path pedido, que traz o valor recusado: acima do teto, vai o path sem a parte
     * variável, que ainda identifica a rota sem repetir o valor.
     */
    private static ProblemDetail badRequest(String detail, HttpServletRequest request) {
        var problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, detail);
        String path = request.getRequestURI();
        problem.setInstance(URI.create(path.length() <= OpenApiConfiguration.PROBLEM_TEXT_MAX_LENGTH ? path : firstSegmentsOf(path)));
        return problem;
    }

    /** "/api/accounts/<valor>:block" vira "/api/accounts". */
    private static String firstSegmentsOf(String path) {
        int thirdSlash = path.indexOf('/', path.indexOf('/', 1) + 1);
        return thirdSlash < 0 ? "/" : path.substring(0, thirdSlash);
    }

}
