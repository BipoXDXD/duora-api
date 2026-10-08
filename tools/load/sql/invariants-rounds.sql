-- Invariantes do sorteio conferidas direto no banco, depois das duas ondas (rodadas 1 e 2 em 50 eventos
-- de 4 inscritos). Uma linha por invariante: nome|esperado|encontrado.
select 'rodadas gravadas (50 eventos x 2)', 100, count(*) from round
union all
select 'eventos que não têm exatamente as rodadas 1 e 2', 0, count(*)
from (select event_id from round group by event_id having count(*) <> 2 or min(number) <> 1 or max(number) <> 2) as wrong
union all
select 'assentos gravados (200 pessoas x 2 rodadas)', 400, count(*) from round_seat
union all
select 'assentos de quem ficou de fora', 0, count(*) from round_seat where partner_account_id is null
union all
select 'assentos cujo par não aponta de volta', 0, count(*)
from round_seat a
where not exists (select 1
                  from round_seat b
                  where b.event_id = a.event_id
                    and b.round_number = a.round_number
                    and b.account_id = a.partner_account_id
                    and b.partner_account_id = a.account_id)
union all
select 'pares repetidos dentro do mesmo evento', 0, count(*)
from (select event_id, least(account_id, partner_account_id), greatest(account_id, partner_account_id)
      from round_seat
      group by 1, 2, 3
      having count(*) > 2) as repeated
union all
select 'pessoas em mais de um lugar na mesma rodada', 0, count(*)
from (select event_id, round_number, account_id from round_seat group by 1, 2, 3 having count(*) > 1) as twice;
