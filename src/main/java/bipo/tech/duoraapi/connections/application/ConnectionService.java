package bipo.tech.duoraapi.connections.application;

import java.util.List;

import org.springframework.stereotype.Service;

import bipo.tech.duoraapi.connections.domain.ConnectedAccount;
import bipo.tech.duoraapi.connections.domain.ConnectionRepository;
import bipo.tech.duoraapi.identity.AccountId;

/** As conexões de quem chama, paginadas por keyset (docs/adr/0019). Uma consulta só, sem transação própria. */
@Service
public class ConnectionService {

    private final ConnectionRepository connections;

    public ConnectionService(ConnectionRepository connections) {
        this.connections = connections;
    }

    /**
     * @param maxPageSize quantas conexões no máximo, já validado na fronteira
     * @param after onde a página começa, ou null na primeira
     */
    public ConnectionsPage connectionsOf(AccountId account, int maxPageSize, ConnectedAccount after) {
        int oneMoreToSeeIfThereIsANextPage = maxPageSize + 1;
        List<ConnectedAccount> found = after == null
                ? connections.findFirstOf(account, oneMoreToSeeIfThereIsANextPage)
                : connections.findOfAfter(account, after, oneMoreToSeeIfThereIsANextPage);
        if (found.size() <= maxPageSize) {
            return new ConnectionsPage(found, null);
        }
        List<ConnectedAccount> page = found.subList(0, maxPageSize);
        return new ConnectionsPage(page, page.getLast());
    }

}
