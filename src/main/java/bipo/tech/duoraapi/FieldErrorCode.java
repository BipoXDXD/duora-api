package bipo.tech.duoraapi;

/**
 * Por que um campo do corpo foi recusado: o {@code code} de cada item de {@code errors} nos 400 de
 * validação (docs/adr/0018). Lista fechada e parte do contrato: os nomes vão para a spec, então renomear
 * ou remover é breaking change. Os nomes seguem as palavras-chave do JSON Schema quando há uma.
 */
public enum FieldErrorCode {

    /** Ausente, null ou vazio onde o valor é obrigatório (inclusive apagar o que não pode ser apagado). */
    REQUIRED,
    /** Texto abaixo do mínimo de caracteres (minLength), como só espaços onde o mínimo é 1. */
    TOO_SHORT,
    /** Texto acima do máximo de caracteres (maxLength). */
    TOO_LONG,
    /** Número ou instante abaixo do mínimo (minimum): capacidade baixa, início no passado, idade implausível. */
    BELOW_MINIMUM,
    /** Número ou instante acima do máximo (maximum): capacidade alta, data de nascimento de menor de idade. */
    ABOVE_MAXIMUM,
    /** Não é do tipo ou do formato do campo: data fora do ISO 8601, UUID inválido, texto num número. */
    INVALID_FORMAT,
    /** Fora da lista fechada de valores do campo (enum). */
    UNSUPPORTED_VALUE,
    /** Texto com caractere de controle, invisível ou espaço que não é o comum. */
    FORBIDDEN_CHARACTER,
    /** Aponta para a própria conta de quem chama, onde isso não faz sentido (denunciar a si mesmo). */
    SELF_REFERENCE,
    /** Chave que o corpo não aceita; o servidor é quem define id, estado e dono. */
    UNKNOWN_FIELD,
    /** O corpo inteiro não pôde ser lido: JSON malformado, vazio ou que não é um objeto. Vem sem field. */
    MALFORMED_BODY

}
