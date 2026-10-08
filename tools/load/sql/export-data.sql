-- Os ids que os cenários não conseguem descobrir pela API (a conta não conhece o próprio id): vão para o
-- data.json que o k6 lê. Uma linha de JSON.
select json_build_object(
    'users', (select coalesce(json_agg(json_build_object('subject', subject, 'accountId', id) order by subject), '[]'::json)
              from account where subject like 'load-user-%'),
    'events', (select coalesce(json_agg(json_build_object(
                   'id', e.id,
                   'registrants', (select json_agg(r.account_id order by r.account_id)
                                   from registration r where r.event_id = e.id)) order by e.title, e.id), '[]'::json)
               from event e where e.title like 'Carga: sala %')
);
