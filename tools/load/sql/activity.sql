-- Uma amostra das conexões da aplicação com o banco: total, ativas, esperando lock e ociosas em transação.
select count(*) as total,
       count(*) filter (where state = 'active') as active,
       count(*) filter (where wait_event_type = 'Lock') as waiting_for_lock,
       count(*) filter (where state = 'idle in transaction') as idle_in_transaction
from pg_stat_activity
where datname = 'duora'
  and usename = 'duora'
  and pid <> pg_backend_pid();
