-- O expurgo do chat (docs/adr/0021) busca os chats vencidos em ordem de purge_after, em lotes com
-- for update skip locked, e a métrica conta os que ainda faltam: os dois percorrem este índice em vez da tabela.
create index chat_purge_after_idx on chat (purge_after);
