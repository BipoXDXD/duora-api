-- Eventos e inscrições (módulo events, docs/adr/0016). Os CHECK repetem os limites do domínio como
-- última defesa; o que depende do relógio (começar no futuro, até um ano à frente) fica no domínio.
-- status é um conjunto fixo do código, por isso CHECK e não tabela de referência. "Em andamento" e
-- "encerrado" não são guardados: vêm de starts_at e ends_at, intervalo semiaberto [starts_at, ends_at).
create table event (
    id          uuid        primary key default uuidv7(),
    title       text        not null check (length(title) between 1 and 80),
    description text        not null check (length(description) between 1 and 500),
    starts_at   timestamptz not null,
    ends_at     timestamptz not null,
    capacity    integer     not null check (capacity between 2 and 200),
    status      text        not null check (status in ('DRAFT', 'PUBLISHED', 'CANCELLED')),
    created_at  timestamptz not null,
    version     bigint      not null default 0 check (version >= 0),
    constraint event_schedule_check check (ends_at > starts_at and ends_at - starts_at <= interval '12 hours')
);

-- Uma inscrição por pessoa e evento: a chave primária é o par, e é ela que torna a inscrição
-- idempotente mesmo com pedidos simultâneos. Ela também serve de índice à FK de event_id.
-- A capacidade não cabe numa constraint (é uma contagem): a inscrição trava a linha do evento
-- (select ... for update) antes de contar, então as inscrições de um mesmo evento entram uma por vez.
-- on delete restrict nas duas FKs, como em profile (docs/adr/0011): apagar conta ou evento exige
-- decidir antes o que fazer com as inscrições.
create table registration (
    event_id      uuid        not null references event (id) on delete restrict,
    account_id    uuid        not null references account (id) on delete restrict,
    registered_at timestamptz not null,
    primary key (event_id, account_id)
);

-- Índice da FK de account_id, que também atende "as minhas inscrições".
create index registration_account_id_idx on registration (account_id);
