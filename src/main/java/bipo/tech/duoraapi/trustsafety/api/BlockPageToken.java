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

    private static final String SEPARATOR = "|";

    private BlockPageToken() {
    }

    static String encode(BlockPosition position) {
        String plain = position.blockedAt() + SEPARATOR + position.blocked().value();
        return Base64.getUrlEncoder().withoutPadding().encodeToString(plain.getBytes(StandardCharsets.UTF_8));
    }

    /** @throws InvalidPageTokenException se o token não foi gerado por {@link #encode} */
    static BlockPosition decode(String token) {
        try {
            String plain = new String(Base64.getUrlDecoder().decode(token), StandardCharsets.UTF_8);
            int separator = plain.indexOf(SEPARATOR);
            if (separator < 0) {
                throw new InvalidPageTokenException();
            }
            return new BlockPosition(Instant.parse(plain.substring(0, separator)),
                    new AccountId(UUID.fromString(plain.substring(separator + 1))));
        } catch (IllegalArgumentException | DateTimeParseException e) {
            throw new InvalidPageTokenException();
        }
    }

}
