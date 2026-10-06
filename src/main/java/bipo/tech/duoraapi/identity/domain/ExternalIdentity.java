package bipo.tech.duoraapi.identity.domain;

/**
 * Quem a pessoa é para o provedor de identidade: o emissor do token e o sujeito, que no Entra
 * External ID é a claim {@code oid} (docs/adr/0002). Nunca o e-mail, que a pessoa pode trocar.
 * Os dois chegam de um token já validado; um valor fora das regras é defeito, e não entrada inválida.
 */
public record ExternalIdentity(String issuer, String subject) {

    public static final int MAX_ISSUER_LENGTH = 2048;
    public static final int MAX_SUBJECT_LENGTH = 255;

    public ExternalIdentity {
        requireText("issuer", issuer, MAX_ISSUER_LENGTH);
        requireText("subject", subject, MAX_SUBJECT_LENGTH);
    }

    private static void requireText(String name, String value, int maxLength) {
        if (value == null || value.isBlank() || value.length() > maxLength) {
            throw new IllegalArgumentException(name + " must have 1 to " + maxLength + " characters");
        }
    }

}
