package bipo.tech.duoraapi.chat.adapter;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

import org.springframework.dao.CannotAcquireLockException;
import org.springframework.jdbc.UncategorizedSQLException;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import bipo.tech.duoraapi.chat.domain.Chat;
import bipo.tech.duoraapi.chat.domain.ChatKey;
import bipo.tech.duoraapi.chat.domain.ChatMessage;
import bipo.tech.duoraapi.chat.domain.ChatMessageText;
import bipo.tech.duoraapi.chat.domain.ChatRepository;
import bipo.tech.duoraapi.identity.AccountId;

/** Chats e mensagens nas tabelas chat e chat_message, por SQL (docs/adr/0021). */
@Repository
class JdbcChatRepository implements ChatRepository {

    /**
     * Quanto um envio espera pelo outro no mesmo chat. A outra transação só grava duas linhas; passar do teto
     * indica algo preso, e a resposta é 503.
     */
    static final String LOCK_TIMEOUT = "2s";

    /** lock_not_available, na tabela de códigos de erro do PostgreSQL: o lock_timeout estourou. */
    private static final String LOCK_NOT_AVAILABLE = "55P03";

    private static final String SELECT_CHAT_BY_KEY = """
            select id, event_id, round_number, first_account_id, second_account_id, last_seq
              from chat
             where event_id = :eventId and round_number = :roundNumber
               and first_account_id = :first and second_account_id = :second
            """;

    private static final String SELECT_MESSAGES = """
            select chat_id, seq, sender_account_id, body, idempotency_key, sent_at
              from chat_message
            """;

    private final JdbcClient jdbcClient;

    JdbcChatRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    @Override
    public Chat findOrAdd(ChatKey key, Instant purgeAfter, Instant createdAt) {
        addIfAbsent(key, purgeAfter, createdAt);
        return find(key).orElseThrow(JdbcChatRepository::chatVanished);
    }

    @Override
    public Chat lockOrAdd(ChatKey key, Instant purgeAfter, Instant createdAt) {
        limitLockWait();
        addIfAbsent(key, purgeAfter, createdAt);
        return translatingLockTimeout(() -> jdbcClient.sql(SELECT_CHAT_BY_KEY + " for update")
                .params(keyParams(key))
                .query(chatMapper(key))
                .optional())
                .orElseThrow(JdbcChatRepository::chatVanished);
    }

    @Override
    public Optional<Chat> find(ChatKey key) {
        return jdbcClient.sql(SELECT_CHAT_BY_KEY)
                .params(keyParams(key))
                .query(chatMapper(key))
                .optional();
    }

    /** Até o fim da transação: o teto vale também para o que ela grava depois de travar o chat. */
    private void limitLockWait() {
        jdbcClient.sql("select set_config('lock_timeout', :timeout, true)")
                .param("timeout", LOCK_TIMEOUT)
                .query(String.class)
                .single();
    }

    /**
     * {@code on conflict do nothing} na chave natural: com o mesmo chat sendo criado por outra transação, o insert
     * espera ela terminar e, se ela confirmar, não faz nada.
     */
    private void addIfAbsent(ChatKey key, Instant purgeAfter, Instant createdAt) {
        translatingLockTimeout(() -> jdbcClient.sql("""
                        insert into chat (event_id, round_number, first_account_id, second_account_id, purge_after,
                                          created_at)
                        values (:eventId, :roundNumber, :first, :second, :purgeAfter, :createdAt)
                        on conflict (event_id, round_number, first_account_id, second_account_id) do nothing
                        """)
                .params(keyParams(key))
                .param("purgeAfter", OffsetDateTime.ofInstant(purgeAfter, ZoneOffset.UTC))
                .param("createdAt", OffsetDateTime.ofInstant(createdAt, ZoneOffset.UTC))
                .update());
    }

    /**
     * Só o expurgo apaga um chat, e só o vencido: sumir entre a criação e a leitura é a corrida com ele num chat de
     * evento acabado há mais de 24 h.
     */
    private static IllegalStateException chatVanished() {
        return new IllegalStateException("the chat was deleted between its creation and its read");
    }

    @Override
    public Optional<ChatMessage> findBySenderAndIdempotencyKey(UUID chatId, AccountId sender, UUID idempotencyKey) {
        return jdbcClient.sql(SELECT_MESSAGES + """
                         where chat_id = :chatId and sender_account_id = :sender
                           and idempotency_key = :idempotencyKey
                        """)
                .param("chatId", chatId)
                .param("sender", sender.value())
                .param("idempotencyKey", idempotencyKey)
                .query(JdbcChatRepository::toMessage)
                .optional();
    }

    /**
     * O update confere a sequência anterior: com o chat travado ela sempre bate, e uma linha a menos aqui
     * seria uma gravação fora do lock, que não pode seguir.
     */
    @Override
    public void record(ChatMessage message) {
        int advanced = jdbcClient.sql("update chat set last_seq = :seq where id = :chatId and last_seq = :seq - 1")
                .param("seq", message.seq())
                .param("chatId", message.chatId())
                .update();
        if (advanced != 1) {
            throw new IllegalStateException("the chat sequence moved without the chat lock");
        }
        jdbcClient.sql("""
                        insert into chat_message (chat_id, seq, sender_account_id, body, idempotency_key, sent_at)
                        values (:chatId, :seq, :sender, :body, :idempotencyKey, :sentAt)
                        """)
                .param("chatId", message.chatId())
                .param("seq", message.seq())
                .param("sender", message.sender().value())
                .param("body", message.text().value())
                .param("idempotencyKey", message.idempotencyKey())
                .param("sentAt", OffsetDateTime.ofInstant(message.sentAt(), ZoneOffset.UTC))
                .update();
    }

    @Override
    public List<ChatMessage> findAfter(UUID chatId, int afterSeq, int limit) {
        return jdbcClient.sql(SELECT_MESSAGES + """
                         where chat_id = :chatId and seq > :afterSeq
                         order by seq
                         limit :limit
                        """)
                .param("chatId", chatId)
                .param("afterSeq", afterSeq)
                .param("limit", limit)
                .query(JdbcChatRepository::toMessage)
                .list();
    }

    @Override
    public Optional<ChatMessage> findMessage(UUID chatId, int seq) {
        return jdbcClient.sql(SELECT_MESSAGES + " where chat_id = :chatId and seq = :seq")
                .param("chatId", chatId)
                .param("seq", seq)
                .query(JdbcChatRepository::toMessage)
                .optional();
    }

    private static Map<String, Object> keyParams(ChatKey key) {
        return Map.of(
                "eventId", key.eventId(),
                "roundNumber", key.roundNumber(),
                "first", key.pair().first().value(),
                "second", key.pair().second().value());
    }

    /** A chave já veio de quem pergunta; a linha só acrescenta o id e a sequência. */
    private static RowMapper<Chat> chatMapper(ChatKey key) {
        return (row, rowNumber) -> new Chat(row.getObject("id", UUID.class), key, row.getInt("last_seq"));
    }

    private static ChatMessage toMessage(ResultSet row, int rowNumber) throws SQLException {
        return new ChatMessage(
                row.getObject("chat_id", UUID.class),
                row.getInt("seq"),
                new AccountId(row.getObject("sender_account_id", UUID.class)),
                new ChatMessageText(row.getString("body")),
                row.getObject("idempotency_key", UUID.class),
                row.getObject("sent_at", OffsetDateTime.class).toInstant());
    }

    /** O JdbcClient não traduz o lock_timeout (55P03); o resto da aplicação o trata como lock não obtido. */
    private static <T> T translatingLockTimeout(Supplier<T> statement) {
        try {
            return statement.get();
        } catch (UncategorizedSQLException e) {
            if (LOCK_NOT_AVAILABLE.equals(e.getSQLException().getSQLState())) {
                throw new CannotAcquireLockException("timed out waiting for another message in the same chat", e);
            }
            throw e;
        }
    }

}
