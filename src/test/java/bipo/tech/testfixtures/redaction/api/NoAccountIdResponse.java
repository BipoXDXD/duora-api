package bipo.tech.testfixtures.redaction.api;

import java.util.UUID;

/** DTO fictício com um id que não é de conta, no toString gerado: a regra não se aplica a ele. */
public record NoAccountIdResponse(UUID eventId) {
}
