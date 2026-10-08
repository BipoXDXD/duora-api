package bipo.tech.duoraapi.connections.api;

import java.nio.charset.StandardCharsets;
import java.time.DateTimeException;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

import bipo.tech.duoraapi.connections.domain.ConnectedAccount;
import bipo.tech.duoraapi.identity.AccountId;

/**
 * O pageToken da lista de conexões: a última conexão vista (data e outra conta), em Base64 URL. Opaco para o
 * cliente, mas não cifrado: não carrega nada que ele não viu na página, e a consulta sempre filtra por quem
 * pede, então um token alterado só muda onde a página da própria pessoa começa.
 */
final class ConnectionPageToken {

    /** Folga sobre o maior token válido, que tem 88 caracteres. */
    static final int MAX_LENGTH = 120;

    /** Base64 URL-safe sem padding: o token vai na query string sem escape. */
    static final String PATTERN = "^[A-Za-z0-9_-]+$";

    /**
     * Nenhuma conexão existe fora de 1970 a 9999. O Instant aceita anos muito além do que o timestamptz
     * guarda, e um token adulterado com um deles viraria erro do banco (500) em vez de 400.
     */
    private static final Instant EARLIEST = Instant.EPOCH;
    private static final Instant LATEST = Instant.parse("9999-12-31T23:59:59.999999Z");

    private static final String SEPARATOR = " ";

    private ConnectionPageToken() {
    }

    static String encode(ConnectedAccount position) {
        String content = position.connectedAt() + SEPARATOR + position.account().value();
        return Base64.getUrlEncoder().withoutPadding().encodeToString(content.getBytes(StandardCharsets.UTF_8));
    }

    /** @throws InvalidRequestException se o token não foi gerado por {@link #encode} */
    static ConnectedAccount decode(String token) {
        if (token.isEmpty() || token.length() > MAX_LENGTH) {
            throw invalid();
        }
        try {
            String[] parts = new String(Base64.getUrlDecoder().decode(token), StandardCharsets.UTF_8)
                    .split(SEPARATOR, -1);
            if (parts.length != 2) {
                throw invalid();
            }
            UUID account = UUID.fromString(parts[1]);
            if (!account.toString().equals(parts[1])) {
                throw invalid();
            }
            Instant connectedAt = Instant.parse(parts[0]);
            if (connectedAt.isBefore(EARLIEST) || connectedAt.isAfter(LATEST)) {
                throw invalid();
            }
            return new ConnectedAccount(new AccountId(account), connectedAt);
        } catch (IllegalArgumentException | DateTimeException e) {
            throw invalid();
        }
    }

    private static InvalidRequestException invalid() {
        return new InvalidRequestException("pageToken is invalid");
    }

}
