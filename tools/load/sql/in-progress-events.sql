-- Eventos já em andamento, com inscritos, para os cenários de sorteio e de decisão.
--
-- Por que SQL e não a API: a API só cria evento que começa no futuro (ADR 0016), e o sorteio só aceita
-- evento em andamento (ADR 0017); esperar o início real ou injetar um relógio na imagem de produção
-- mudaria o que se mede. Aqui o banco de teste, descartável, recebe o estado que um evento no meio da
-- noite teria: publicado, começou há 30 minutos, acaba em 3 horas (duração de 3h30, abaixo do teto de
-- 12 h do CHECK). As inscrições são as que a API gravaria.
--
-- Variáveis (psql -v): rooms = quantos eventos; people = inscritos por evento. As contas são as sintéticas
-- (subject load-user-*), em ordem, `people` por evento.
begin;

create temporary table room on commit drop as
select g as number, uuidv7() as id from generate_series(0, :rooms - 1) as g;

insert into event (id, title, description, starts_at, ends_at, capacity, status, created_at)
select id, 'Carga: sala ' || number, 'Evento sintético em andamento do teste de carga.',
       now() - interval '30 minutes', now() + interval '3 hours', :people, 'PUBLISHED', now()
from room;

insert into registration (event_id, account_id, registered_at)
select room.id, person.id, now() - interval '1 hour'
from (select id, row_number() over (order by subject) - 1 as position
      from account where subject like 'load-user-%') as person
         join room on room.number = person.position / :people;

commit;
