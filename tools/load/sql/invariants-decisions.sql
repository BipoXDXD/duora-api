-- Invariantes da decisão conferidas direto no banco. O esperado das conexões é recalculado das próprias
-- decisões gravadas (pares em que os dois disseram sim), de forma independente do código sob teste.
-- Uma linha por invariante: nome|esperado|encontrado.
with mutual_yes as (
    select a.account_id as first_account_id, a.partner_account_id as second_account_id
    from round_decision a
             join round_decision b
                  on b.event_id = a.event_id
                      and b.round_number = a.round_number
                      and b.account_id = a.partner_account_id
                      and b.partner_account_id = a.account_id
    where a.account_id < a.partner_account_id
      and a.interested
      and b.interested
)
select 'decisões gravadas (200 pessoas)', 200, count(*) from round_decision
union all
select 'conexões gravadas = pares com dois sim', (select count(*) from mutual_yes), count(*) from connection
union all
select 'conexões sem dois sim por trás', 0, count(*)
from connection c
where not exists (select 1
                  from mutual_yes m
                  where m.first_account_id = c.first_account_id
                    and m.second_account_id = c.second_account_id)
union all
select 'pares com dois sim sem conexão', 0, count(*)
from mutual_yes m
where not exists (select 1
                  from connection c
                  where c.first_account_id = m.first_account_id
                    and c.second_account_id = m.second_account_id);
