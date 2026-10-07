package bipo.tech.duoraapi;

import java.util.Objects;

/**
 * Um campo de entrada fora das regras, com o nome da propriedade JSON e o motivo, para a API devolver os
 * dois em {@code errors} (docs/adr/0018). Cada módulo estende esta classe com a própria exceção, e a
 * camada web traduz todas num ponto só. A mensagem vai ao cliente como detail: nunca leva o valor recebido.
 */
public abstract class InvalidFieldException extends RuntimeException {

    private final String field;
    private final FieldErrorCode code;

    protected InvalidFieldException(String field, FieldErrorCode code, String message) {
        super(message);
        this.field = Objects.requireNonNull(field, "field");
        this.code = Objects.requireNonNull(code, "code");
    }

    /** O nome da propriedade no corpo JSON, como o cliente a enviou (camelCase). */
    public String field() {
        return field;
    }

    public FieldErrorCode code() {
        return code;
    }

}
