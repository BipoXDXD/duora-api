package bipo.tech.duoraapi.trustsafety.adapter;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import bipo.tech.duoraapi.identity.AccountId;
import bipo.tech.duoraapi.trustsafety.domain.Block;
import bipo.tech.duoraapi.trustsafety.domain.BlockPosition;
import bipo.tech.duoraapi.trustsafety.domain.BlockRepository;
import bipo.tech.duoraapi.trustsafety.domain.UnknownAccountException;

/** Bloqueios na tabela account_block, por SQL: são pares com data, sem estado que justifique o JPA. */
@Repository
class JdbcBlockRepository implements BlockRepository {

    private static final String COLUMNS = "blocker_account_id, blocked_account_id, created_at";

    private final JdbcClient jdbcClient;

    JdbcBlockRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    @Override
    public void addIfAbsent(Block block) {
        try {
            jdbcClient.sql("""
                            insert into account_block (blocker_account_id, blocked_account_id, created_at)
                            values (:blocker, :blocked, :createdAt)
                            on conflict (blocker_account_id, blocked_account_id) do nothing
                            """)
                    .param("blocker", block.blocker().value())
                    .param("blocked", block.blocked().value())
                    .param("createdAt", OffsetDateTime.ofInstant(block.blockedAt(), ZoneOffset.UTC))
                    .update();
        } catch (DataIntegrityViolationException e) {
            // Quem bloqueia é a conta autenticada, que existe: a FK que falha é a da conta bloqueada.
            throw SqlStates.isForeignKeyViolation(e) ? new UnknownAccountException() : e;
        }
    }

    @Override
    public void remove(AccountId blocker, AccountId blocked) {
        jdbcClient.sql("""
                        delete from account_block
                        where blocker_account_id = :blocker and blocked_account_id = :blocked
                        """)
                .param("blocker", blocker.value())
                .param("blocked", blocked.value())
                .update();
    }

    @Override
    public boolean existsEitherWay(AccountId first, AccountId second) {
        return jdbcClient.sql("""
                        select exists (
                            select 1 from account_block
                            where (blocker_account_id = :first and blocked_account_id = :second)
                               or (blocker_account_id = :second and blocked_account_id = :first))
                        """)
                .param("first", first.value())
                .param("second", second.value())
                .query(Boolean.class)
                .single();
    }

    @Override
    public List<Block> findFirstByBlocker(AccountId blocker, int limit) {
        return jdbcClient.sql("""
                        select %s from account_block
                        where blocker_account_id = :blocker
                        order by created_at desc, blocked_account_id desc
                        limit :limit
                        """.formatted(COLUMNS))
                .param("blocker", blocker.value())
                .param("limit", limit)
                .query(JdbcBlockRepository::toBlock)
                .list();
    }

    /** Keyset: compara a linha (data, conta) com a posição, sem OFFSET. */
    @Override
    public List<Block> findByBlockerAfter(AccountId blocker, BlockPosition after, int limit) {
        return jdbcClient.sql("""
                        select %s from account_block
                        where blocker_account_id = :blocker
                          and (created_at, blocked_account_id) < (:afterBlockedAt, :afterBlocked)
                        order by created_at desc, blocked_account_id desc
                        limit :limit
                        """.formatted(COLUMNS))
                .param("blocker", blocker.value())
                .param("afterBlockedAt", OffsetDateTime.ofInstant(after.blockedAt(), ZoneOffset.UTC))
                .param("afterBlocked", after.blocked().value())
                .param("limit", limit)
                .query(JdbcBlockRepository::toBlock)
                .list();
    }

    private static Block toBlock(ResultSet row, int rowNumber) throws SQLException {
        return new Block(
                new AccountId(row.getObject("blocker_account_id", UUID.class)),
                new AccountId(row.getObject("blocked_account_id", UUID.class)),
                row.getObject("created_at", OffsetDateTime.class).toInstant());
    }

}
