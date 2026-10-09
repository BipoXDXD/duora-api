package bipo.tech.testfixtures.redaction.api;

import java.util.UUID;

/** DTO fictício com o id de outra conta e o toString declarado, sem o id: a regra deve aceitá-lo. */
public record RedactedResponse(UUID partnerAccountId) {

    @Override
    public String toString() {
        return "RedactedResponse[partnerAccountId=redacted]";
    }

}
