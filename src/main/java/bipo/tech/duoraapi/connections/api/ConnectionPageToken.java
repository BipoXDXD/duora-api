package bipo.tech.duoraapi.connections.api;

import bipo.tech.duoraapi.config.KeysetPageToken;
import bipo.tech.duoraapi.connections.domain.ConnectedAccount;
import bipo.tech.duoraapi.identity.AccountId;

/** O pageToken da lista de conexões: a data e a outra conta da última conexão vista. */
final class ConnectionPageToken {

    /** Folga sobre o maior token válido, que tem 88 caracteres. */
    static final int MAX_LENGTH = 120;

    static final String PATTERN = KeysetPageToken.PATTERN;

    private ConnectionPageToken() {
    }

    static String encode(ConnectedAccount position) {
        return KeysetPageToken.encode(position.connectedAt(), position.account().value());
    }

    /** @throws bipo.tech.duoraapi.config.InvalidPageParameterException se a API não gerou o token */
    static ConnectedAccount decode(String token) {
        var position = KeysetPageToken.decode(token, MAX_LENGTH);
        return new ConnectedAccount(new AccountId(position.id()), position.instant());
    }

}
