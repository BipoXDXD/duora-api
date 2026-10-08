package bipo.tech.duoraapi.connections.adapter;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import bipo.tech.duoraapi.connections.domain.ConnectedAccount;
import bipo.tech.duoraapi.connections.domain.Connection;
import bipo.tech.duoraapi.connections.domain.ConnectionRepository;
import bipo.tech.duoraapi.identity.AccountId;

/** Conexões na tabela connection, uma linha por par normalizado (docs/adr/0019). */
@Repository
class JdbcConnectionRepository implements ConnectionRepository {

    /**
     * As conexões da conta vistas do lado dela: a outra conta e a data. Cada metade usa o próprio índice (a
     * chave primária e connection_second_account_id_idx), em vez de um {@code or} que os juntaria.
     */
    private static final String CONNECTIONS_OF = """
            select other_account_id, connected_at from (
                select second_account_id as other_account_id, connected_at
                  from connection where first_account_id = :account
                union all
                select first_account_id, connected_at
                  from connection where second_account_id = :account
            ) as mine
            """;

    private final JdbcClient jdbcClient;

    JdbcConnectionRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    /** {@code on conflict do nothing} na chave primária: dois "sim" simultâneos nunca gravam duas linhas. */
    @Override
    public void addIfAbsent(Connection connection) {
        jdbcClient.sql("""
                        insert into connection (first_account_id, second_account_id, connected_at)
                        values (:first, :second, :connectedAt)
                        on conflict (first_account_id, second_account_id) do nothing
                        """)
                .param("first", connection.pair().first().value())
                .param("second", connection.pair().second().value())
                .param("connectedAt", OffsetDateTime.ofInstant(connection.connectedAt(), ZoneOffset.UTC))
                .update();
    }

    @Override
    public List<ConnectedAccount> findFirstOf(AccountId account, int limit) {
        return jdbcClient.sql(CONNECTIONS_OF + """
                        order by connected_at desc, other_account_id desc
                        limit :limit
                        """)
                .param("account", account.value())
                .param("limit", limit)
                .query(JdbcConnectionRepository::toConnectedAccount)
                .list();
    }

    /** Keyset: compara a linha (data, outra conta) com a posição, sem OFFSET. */
    @Override
    public List<ConnectedAccount> findOfAfter(AccountId account, ConnectedAccount after, int limit) {
        return jdbcClient.sql(CONNECTIONS_OF + """
                        where (connected_at, other_account_id) < (:afterConnectedAt, :afterAccount)
                        order by connected_at desc, other_account_id desc
                        limit :limit
                        """)
                .param("account", account.value())
                .param("afterConnectedAt", OffsetDateTime.ofInstant(after.connectedAt(), ZoneOffset.UTC))
                .param("afterAccount", after.account().value())
                .param("limit", limit)
                .query(JdbcConnectionRepository::toConnectedAccount)
                .list();
    }

    private static ConnectedAccount toConnectedAccount(ResultSet row, int rowNumber) throws SQLException {
        return new ConnectedAccount(new AccountId(row.getObject("other_account_id", UUID.class)),
                row.getObject("connected_at", OffsetDateTime.class).toInstant());
    }

}
