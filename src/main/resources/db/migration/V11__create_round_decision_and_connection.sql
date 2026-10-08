-- Decisão privada depois de cada rodada e conexão por interesse mútuo (módulo connections, docs/adr/0019).
-- As FKs para account são só integridade: connections não lê nem escreve essa tabela. Não há FK para round
-- nem round_seat: quem pode decidir vem da API publicada do matching (matching.Pairings), e uma FK
-- acoplaria este módulo ao esquema de outro.

-- Uma decisão por pessoa e rodada: a chave primária garante, inclusive com pedidos simultâneos
-- (insert ... on conflict do nothing). A decisão é final; não há update.
-- on delete restrict, como nas outras tabelas que apontam para account (docs/adr/0011).
create table round_decision (
    event_id           uuid        not null,
    round_number       integer     not null check (round_number between 1 and 100),
    account_id         uuid        not null references account (id) on delete restrict,
    partner_account_id uuid        not null references account (id) on delete restrict,
    interested         boolean     not null,
    decided_at         timestamptz not null,
    primary key (event_id, round_number, account_id),
    constraint round_decision_not_self check (account_id <> partner_account_id)
);

-- Índices das FKs para account: a chave primária começa pelo evento e não serve a nenhuma das duas.
create index round_decision_account_id_idx on round_decision (account_id);
create index round_decision_partner_account_id_idx on round_decision (partner_account_id);

-- Uma conexão por par normalizado: o menor id primeiro (a ordem do tipo uuid), garantido pelo CHECK, e a
-- chave primária é o par. Dois "sim" simultâneos nunca gravam duas linhas (insert ... on conflict do
-- nothing); a ausência de conexão é evitada por um advisory lock por par e rodada (docs/adr/0019).
create table connection (
    first_account_id  uuid        not null references account (id) on delete restrict,
    second_account_id uuid        not null references account (id) on delete restrict,
    connected_at      timestamptz not null,
    primary key (first_account_id, second_account_id),
    constraint connection_normalized_pair check (first_account_id < second_account_id)
);

-- A chave primária começa pelo primeiro lado; a FK e a lista de quem está no segundo lado precisam deste.
create index connection_second_account_id_idx on connection (second_account_id);
