-- Rodadas de pareamento de um evento (módulo matching, docs/adr/0017). As FKs para event e account são só
-- integridade: matching não lê nem escreve essas tabelas, pergunta aos módulos donos.

-- Uma rodada por número e evento: a chave primária é o que deixa duas chamadas simultâneas com uma rodada
-- só (insert ... on conflict do nothing; quem chega depois espera a primeira e devolve a dela).
-- A sequência também é do banco: a rodada N aponta para a N-1 pela FK da própria tabela, e o CHECK exige
-- que previous_number seja exatamente N-1 (nulo só na primeira). "is not distinct from" porque um CHECK
-- com resultado nulo passaria. seed guarda a semente do sorteio, para reproduzi-lo numa investigação.
-- on delete restrict, como em registration (docs/adr/0016): apagar um evento exige decidir antes o destino
-- das rodadas.
create table round (
    event_id        uuid        not null references event (id) on delete restrict,
    number          integer     not null check (number between 1 and 100),
    previous_number integer,
    seed            bigint      not null,
    started_at      timestamptz not null,
    primary key (event_id, number),
    constraint round_previous_fk foreign key (event_id, previous_number)
        references round (event_id, number) on delete restrict,
    constraint round_sequence_check check (previous_number is not distinct from nullif(number - 1, 0))
);

-- Índice da FK da sequência.
create index round_previous_idx on round (event_id, previous_number);

-- Um assento por pessoa e rodada, simétrico: Ana com Bruno grava Ana -> Bruno e Bruno -> Ana. Parceiro
-- nulo é quem ficou de fora na rodada.
-- - A chave primária deixa cada pessoa num lugar só por rodada.
-- - O unique de (evento, pessoa, parceiro) impede o mesmo par duas vezes no evento. Os nulos de quem ficou
--   de fora não colidem entre si (nulls distinct, o padrão), e é o que se quer: ficar de fora em várias
--   rodadas é permitido.
-- - A FK do parceiro aponta para o assento dele com o parceiro trocado, então o par é sempre recíproco. É
--   deferrable porque os dois lados entram na mesma transação; o unique de quatro colunas existe só para
--   servir de alvo a essa FK.
create table round_seat (
    event_id           uuid    not null,
    round_number       integer not null,
    account_id         uuid    not null references account (id) on delete restrict,
    partner_account_id uuid,
    primary key (event_id, round_number, account_id),
    constraint round_seat_round_fk foreign key (event_id, round_number)
        references round (event_id, number) on delete restrict,
    constraint round_seat_pair_once_per_event unique (event_id, account_id, partner_account_id),
    constraint round_seat_seat_and_partner unique (event_id, round_number, account_id, partner_account_id),
    constraint round_seat_reciprocal_fk foreign key (event_id, round_number, partner_account_id, account_id)
        references round_seat (event_id, round_number, account_id, partner_account_id)
        on delete restrict deferrable initially deferred,
    constraint round_seat_not_self check (partner_account_id <> account_id)
);

-- Índices das FKs que a chave primária não cobre.
create index round_seat_account_id_idx on round_seat (account_id);
create index round_seat_partner_idx on round_seat (event_id, round_number, partner_account_id, account_id);
