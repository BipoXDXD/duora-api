-- Chat temporário do par de cada rodada (módulo chat, docs/adr/0021). As FKs para account são só
-- integridade: chat não lê nem escreve essa tabela. Não há FK para event, round nem round_seat: quem pode
-- conversar vem das APIs publicadas do matching e do events, e uma FK acoplaria este módulo ao esquema de
-- outro (como em round_decision, docs/adr/0019).

-- Um chat por par normalizado e rodada: o menor id primeiro (a ordem do tipo uuid), garantido pelo CHECK.
-- Criado na primeira leitura ou mensagem (insert ... on conflict do nothing). last_seq é a sequência da
-- última mensagem: quem envia trava a linha (select ... for update), então a ordem da sequência é a ordem de
-- commit e não há lacunas. purge_after é o fim agendado do evento mais 24 h, quando o conteúdo é apagado.
create table chat (
    id                uuid        primary key default uuidv7(),
    event_id          uuid        not null,
    round_number      integer     not null check (round_number between 1 and 100),
    first_account_id  uuid        not null references account (id) on delete restrict,
    second_account_id uuid        not null references account (id) on delete restrict,
    last_seq          integer     not null default 0 check (last_seq between 0 and 300),
    purge_after       timestamptz not null,
    created_at        timestamptz not null,
    constraint chat_one_per_pair_and_round unique (event_id, round_number, first_account_id, second_account_id),
    constraint chat_normalized_pair check (first_account_id < second_account_id)
);

-- Índices das FKs para account: a chave natural começa pelo evento e não serve a nenhuma das duas.
create index chat_first_account_id_idx on chat (first_account_id);
create index chat_second_account_id_idx on chat (second_account_id);

-- Uma linha por mensagem, na posição seq do chat. A Idempotency-Key é única por remetente no chat: o
-- reenvio com a mesma chave nunca grava duas. O texto chega normalizado (NFC, sem espaço nas pontas) e
-- tem de 1 a 500 caracteres; o CHECK é a última defesa. Apagar o chat apaga as mensagens: é o mesmo
-- agregado, e o expurgo apaga de verdade (plano, seção 4).
create table chat_message (
    chat_id           uuid        not null references chat (id) on delete cascade,
    seq               integer     not null check (seq between 1 and 300),
    sender_account_id uuid        not null references account (id) on delete restrict,
    body              text        not null check (char_length(body) between 1 and 500),
    idempotency_key   uuid        not null,
    sent_at           timestamptz not null,
    primary key (chat_id, seq),
    constraint chat_message_idempotency_key_once unique (chat_id, sender_account_id, idempotency_key)
);

-- Índice da FK para account; a chave primária e a única começam pelo chat e não servem a ela.
create index chat_message_sender_account_id_idx on chat_message (sender_account_id);
