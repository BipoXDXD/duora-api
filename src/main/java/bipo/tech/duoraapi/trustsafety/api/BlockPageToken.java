package bipo.tech.duoraapi.trustsafety.api;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Base64;
import java.util.UUID;

import bipo.tech.duoraapi.identity.AccountId;
import bipo.tech.duoraapi.trustsafety.domain.BlockPosition;

/**
 * O pageToken da lista de bloqueios: a posição do último item visto, em Base64 URL. É opaco para o
 * cliente, mas não cifrado; não precisa ser, porque não autoriza nada: a consulta sempre filtra por
 * quem pede, e um token alterado só muda onde a página da própria pessoa começa.
 */
final class BlockPageToken {

    /** Folga sobre os cerca de 90 caracteres de um token gerado. */
    static final int MAX_LENGTH = 200;
    static final String PATTERN = "^[A-Za-z0-9_-]+$";

    /**
     * Nenhum bloqueio existe fora de 1970 a 9999. O Instant aceita anos muito além desses, e um token
     * adulterado com um deles devolveria uma página em vez do 400 de token que a API não gerou.
     */
    private static final Instant EARLIEST = Instant.EPOCH;
    private static final Instant LATEST = Instant.parse("9999-12-31T23:59:59.999999Z");

    private static final String SEPARATOR = "|";

    private BlockPageToken() {
    }

    static String encode(BlockPosition position) {
        String plain = position.blockedAt() + SEPARATOR + position.blocked().value();
        return Base64.getUrlEncoder().withoutPadding().encodeToString(plain.getBytes(StandardCharsets.UTF_8));
    }

    /** @throws InvalidPageTokenException se o token não foi gerado por {@link #encode} */
    static BlockPosition decode(String token) {
        if (token.length() > MAX_LENGTH) {
            throw new InvalidPageTokenException();
        }
        try {
            String plain = new String(Base64.getUrlDecoder().decode(token), StandardCharsets.UTF_8);
            int separator = plain.indexOf(SEPARATOR);
            if (separator < 0) {
                throw new InvalidPageTokenException();
            }
            Instant blockedAt = Instant.parse(plain.substring(0, separator));
            if (blockedAt.isBefore(EARLIEST) || blockedAt.isAfter(LATEST)) {
                throw new InvalidPageTokenException();
            }
            return new BlockPosition(blockedAt, new AccountId(UUID.fromString(plain.substring(separator + 1))));
        } catch (IllegalArgumentException | DateTimeParseException e) {
            throw new InvalidPageTokenException();
        }
    }

}
