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

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import bipo.tech.duoraapi.chat.domain.Chat;
import bipo.tech.duoraapi.chat.domain.ChatKey;
import bipo.tech.duoraapi.chat.domain.ChatMessage;
import bipo.tech.duoraapi.chat.domain.ChatMessageText;
import bipo.tech.duoraapi.chat.domain.ChatRepository;
import bipo.tech.duoraapi.config.PostgresLocks;
import bipo.tech.duoraapi.identity.AccountId;

/** Chats e mensagens nas tabelas chat e chat_message, por SQL (docs/adr/0021). */
@Repository
class JdbcChatRepository implements ChatRepository {

    /**
     * Quanto um envio espera pelo outro no mesmo chat. A outra transação só grava duas linhas; passar do teto
     * indica algo preso, e a resposta é 503.
     */
    static final String LOCK_TIMEOUT = "2s";

    private static final String LOCK_TIMEOUT_MESSAGE = "timed out waiting for another message in the same chat";

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

    /**
     * Quem só lê não grava uma versão nova da linha: o chat que já existe sai de um select. Ausente, ele é criado
     * pelo mesmo comando que o trava e o devolve, sem intervalo em que o expurgo o apague.
     */
    @Override
    public Chat findOrAdd(ChatKey key, Instant purgeAfter, Instant createdAt) {
        return find(key).orElseGet(() -> addOrLock(key, purgeAfter, createdAt));
    }

    @Override
    public Chat lockOrAdd(ChatKey key, Instant purgeAfter, Instant createdAt) {
        PostgresLocks.limitWait(jdbcClient, LOCK_TIMEOUT);
        return addOrLock(key, purgeAfter, createdAt);
    }

    @Override
    public Optional<Chat> find(ChatKey key) {
        return jdbcClient.sql(SELECT_CHAT_BY_KEY)
                .params(keyParams(key))
                .query(chatMapper(key))
                .optional();
    }

    /**
     * Cria o chat ou trava o que existe, e devolve a linha, num comando só. O {@code do update} que não muda nada
     * existe pelo lock: com a linha travada, o expurgo a pula ({@code skip locked}). Se o expurgo já a travou, o
     * comando espera por ele e, com a linha apagada, cria outra. Com o mesmo chat sendo criado ou travado por
     * outra transação, espera ela terminar e devolve a última versão confirmada.
     */
    private Chat addOrLock(ChatKey key, Instant purgeAfter, Instant createdAt) {
        return PostgresLocks.translatingTimeout(() -> jdbcClient.sql("""
                        insert into chat (event_id, round_number, first_account_id, second_account_id, purge_after,
                                          created_at)
                        values (:eventId, :roundNumber, :first, :second, :purgeAfter, :createdAt)
                        on conflict (event_id, round_number, first_account_id, second_account_id)
                        do update set last_seq = chat.last_seq
                        returning id, last_seq
                        """)
                .params(keyParams(key))
                .param("purgeAfter", OffsetDateTime.ofInstant(purgeAfter, ZoneOffset.UTC))
                .param("createdAt", OffsetDateTime.ofInstant(createdAt, ZoneOffset.UTC))
                .query(chatMapper(key))
                .single(), LOCK_TIMEOUT_MESSAGE);
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

}
