package bipo.tech.duoraapi.config;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import com.fasterxml.jackson.annotation.JsonInclude;

import bipo.tech.duoraapi.FieldErrorCode;
import bipo.tech.duoraapi.InvalidFieldException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.exc.MismatchedInputException;
import tools.jackson.databind.exc.UnrecognizedPropertyException;

/**
 * Os 400 de validação do corpo levam, além do detail, a lista {@code errors} com o campo e o motivo
 * (docs/adr/0018), para o cliente apontar o campo sem ler o texto em inglês. Um ponto só para as três
 * origens: o corpo que o Jackson não lê, o bean validation do {@code @Valid} e as exceções de valor
 * inválido dos módulos ({@link InvalidFieldException}). Nenhuma repete o valor recebido.
 *
 * <p>Substitui o handler de ProblemDetail do Boot (spring.mvc.problemdetails.enabled), que só existe sem
 * outro {@link ResponseEntityExceptionHandler}; o resto do tratamento padrão continua o mesmo, na mesma ordem.
 */
@ControllerAdvice
@Order(0)
class RequestBodyProblemHandler extends ResponseEntityExceptionHandler {

    /** O membro de extensão do ProblemDetail (RFC 9457, seção 3.2). */
    static final String ERRORS_PROPERTY = "errors";

    /** Teto de itens em errors, o mesmo da spec. Os corpos da API têm bem menos campos que isso. */
    static final int MAX_ERRORS = 20;

    /** Nome de propriedade JSON da API. A chave desconhecida só volta se tiver esse formato. */
    static final String FIELD_NAME_PATTERN = "^[A-Za-z][A-Za-z0-9]*$";
    static final int FIELD_NAME_MAX_LENGTH = 64;

    private static final Pattern FIELD_NAME = Pattern.compile(FIELD_NAME_PATTERN);

    /** O detail padrão do Spring para o corpo que não pôde ser lido, mantido por compatibilidade. */
    private static final String UNREADABLE_BODY_DETAIL = "Failed to read request";

    private static final String NOT_NULL_CONSTRAINT = "NotNull";

    @ExceptionHandler(InvalidFieldException.class)
    ResponseEntity<Object> handleInvalidField(InvalidFieldException exception, WebRequest request) {
        var problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, exception.getMessage());
        problem.setProperty(ERRORS_PROPERTY, List.of(new FieldError(exception.field(), exception.code())));
        return handleExceptionInternal(exception, problem, new HttpHeaders(), HttpStatus.BAD_REQUEST, request);
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException exception,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        exception.getBody().setProperty(ERRORS_PROPERTY, errorsOf(exception.getBindingResult()));
        return super.handleMethodArgumentNotValid(exception, headers, status, request);
    }

    @Override
    protected ResponseEntity<Object> handleHttpMessageNotReadable(HttpMessageNotReadableException exception,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        ProblemDetail problem = createProblemDetail(exception, status, UNREADABLE_BODY_DETAIL, null, null, request);
        problem.setProperty(ERRORS_PROPERTY, List.of(errorOf(exception)));
        return handleExceptionInternal(exception, problem, headers, status, request);
    }

    /** Ordenados por campo: o bean validation não garante ordem, e a resposta fica estável. */
    private static List<FieldError> errorsOf(BindingResult result) {
        return result.getFieldErrors().stream()
                .map(error -> new FieldError(error.getField(), codeOf(error.getCode())))
                .sorted(Comparator.comparing(FieldError::field).thenComparing(FieldError::code))
                .distinct()
                .limit(MAX_ERRORS)
                .toList();
    }

    /**
     * Só as constraints que a API usa têm code. Uma nova sem tradução falha alto, no teste dela, em vez
     * de responder um code que não está na lista fechada.
     */
    private static FieldErrorCode codeOf(String constraint) {
        if (NOT_NULL_CONSTRAINT.equals(constraint)) {
            return FieldErrorCode.REQUIRED;
        }
        throw new IllegalStateException("constraint without a FieldErrorCode: " + constraint);
    }

    private static FieldError errorOf(HttpMessageNotReadableException exception) {
        if (!(exception.getCause() instanceof JacksonException cause)) {
            return FieldError.ofBody();
        }
        Optional<String> field = topLevelFieldOf(cause);
        if (cause instanceof UnrecognizedPropertyException unknown) {
            return new FieldError(nameIfSafe(unknown.getPropertyName()).orElse(null), FieldErrorCode.UNKNOWN_FIELD);
        }
        if (cause instanceof MismatchedInputException mismatch && field.isPresent()) {
            return new FieldError(field.get(), isEnum(mismatch) ? FieldErrorCode.UNSUPPORTED_VALUE
                    : FieldErrorCode.INVALID_FORMAT);
        }
        return FieldError.ofBody();
    }

    /** A primeira propriedade do caminho até o erro: os corpos da API são planos. */
    private static Optional<String> topLevelFieldOf(JacksonException exception) {
        return exception.getPath().stream()
                .findFirst()
                .map(JacksonException.Reference::getPropertyName)
                .flatMap(RequestBodyProblemHandler::nameIfSafe);
    }

    /** A chave vem do cliente: só volta se for um nome de propriedade, nunca texto livre ou longo. */
    private static Optional<String> nameIfSafe(String name) {
        boolean safe = name != null && name.length() <= FIELD_NAME_MAX_LENGTH && FIELD_NAME.matcher(name).matches();
        return safe ? Optional.of(name) : Optional.empty();
    }

    private static boolean isEnum(MismatchedInputException exception) {
        return exception.getTargetType() != null && exception.getTargetType().isEnum();
    }

    /** Um item de errors. Sem field quando o erro é do corpo inteiro (MALFORMED_BODY) ou a chave não volta. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record FieldError(String field, FieldErrorCode code) {

        static FieldError ofBody() {
            return new FieldError(null, FieldErrorCode.MALFORMED_BODY);
        }

    }

}
