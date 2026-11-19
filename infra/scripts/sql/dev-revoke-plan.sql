-- LOCAL DEV ONLY. Revokes the dev grants made by dev-grant-plan.sql for one user (and plan). Variables: :'user_id', :'plan_code'.
UPDATE user_subscriptions s
SET subscription_status = 'REVOKED', revoked_at = now(), updated_at = now()
FROM subscription_plans p
WHERE p.id = s.plan_id
  AND s.user_id = :'user_id'::uuid
  AND p.plan_code = :'plan_code'
  AND s.source_type = 'ADMIN_GRANT'
  AND s.subscription_status = 'ACTIVE'
  AND s.metadata ->> 'note' = 'local dev grant'
RETURNING s.id;
