-- Invariantes da inscrição conferidas direto no banco, depois do cenário. Uma linha por invariante:
-- nome|esperado|encontrado. run.sh falha se esperado e encontrado diferirem.
select 'inscritas no evento (esperado: a capacidade)', :capacity, count(*)
from registration
where event_id = (select id from event where title = 'Carga: inscrição concorrente')
union all
select 'eventos com mais inscritas que a capacidade', 0, count(*)
from (select e.id
      from event e
               join registration r on r.event_id = e.id
      group by e.id, e.capacity
      having count(*) > e.capacity) as overbooked
union all
select 'contas inscritas mais de uma vez no mesmo evento', 0, count(*)
from (select event_id, account_id from registration group by event_id, account_id having count(*) > 1) as repeated;
