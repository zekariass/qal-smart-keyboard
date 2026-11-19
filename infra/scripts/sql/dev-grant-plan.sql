-- LOCAL DEV ONLY. Used by infra/scripts/dev-grant-plan.sh (psql variables) and by DevGrantPlanSqlIT (placeholders replaced).
-- Variables: :'user_id' (uuid), :'plan_code', :'days' (integer text).
INSERT INTO user_subscriptions
    (user_id, plan_id, source_type, billing_mode, subscription_status,
     current_period_start, current_period_end, started_at, metadata)
SELECT :'user_id'::uuid, p.id, 'ADMIN_GRANT', 'GRANT', 'ACTIVE',
       now(), now() + (:'days')::int * INTERVAL '1 day', now(), '{"note":"local dev grant"}'::jsonb
FROM subscription_plans p
WHERE p.plan_code = :'plan_code'
RETURNING id, current_period_end;
