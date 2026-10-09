package bipo.tech.testfixtures.redaction.api;

import java.util.UUID;

/** DTO fictício com o id de outra conta e o toString que o compilador gera: a regra deve recusá-lo. */
public record GeneratedToStringResponse(UUID partnerAccountId) {
}
