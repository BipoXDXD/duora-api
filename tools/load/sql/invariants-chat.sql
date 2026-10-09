-- Invariantes do chat conferidas direto no banco, depois do cenário (50 pares, 100 participantes). Uma linha
-- por invariante: nome|esperado|encontrado.
-- :seen vem das linhas `chatvu|<subject>|<maior seq lido>|<envios aceitos>` que cada participante imprime no
-- fim, no formato `subject:maior seq:envios;...`. É o único dado do lado do k6; o resto sai das tabelas.
with reported as (
    select split_part(item, ':', 1) as subject,
           split_part(item, ':', 2)::int as max_seq_read,
           split_part(item, ':', 3)::int as accepted
    from unnest(string_to_array(:'seen', ';')) as item
    where item <> ''
),
sender as (
    select a.subject, count(m.seq) as stored
    from account a
             left join chat_message m on m.sender_account_id = a.id
    where a.subject like 'load-user-%'
    group by a.subject
)
select 'chats (um por par)', :rooms, count(*) from chat
union all
select 'participantes que reportaram ao fim', :rooms * 2, count(*) from reported
union all
select 'chats com lacuna ou repetição em 1..last_seq', 0, count(*)
from (select c.id
      from chat c
               left join chat_message m on m.chat_id = c.id
      group by c.id, c.last_seq
      having count(m.seq) <> c.last_seq
          or coalesce(max(m.seq), 0) <> c.last_seq
          or coalesce(min(m.seq), 1) <> 1) as broken
union all
select 'mensagens duplicadas por (remetente, chave)', 0, count(*)
from (select sender_account_id, idempotency_key
      from chat_message
      group by sender_account_id, idempotency_key
      having count(*) > 1) as repeated
union all
select 'mensagens de quem não é do par do chat', 0, count(*)
from chat_message m
         join chat c on c.id = m.chat_id
where m.sender_account_id not in (c.first_account_id, c.second_account_id)
union all
select 'mensagens gravadas = envios únicos aceitos (201 do k6)', (select coalesce(sum(accepted), 0) from reported), count(*)
from chat_message
union all
select 'contas cujas mensagens gravadas diferem dos envios aceitos', 0, count(*)
from reported r
         join sender s on s.subject = r.subject
where s.stored <> r.accepted
union all
select 'participantes que leram até o last_seq do chat', (select count(*) from reported), count(*)
from reported r
         join account a on a.subject = r.subject
         join chat c on a.id in (c.first_account_id, c.second_account_id)
where r.max_seq_read = c.last_seq;
