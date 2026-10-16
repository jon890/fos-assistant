-- 옛 두 표의 줄을 앞 파일이 더한 컬럼으로 옮긴다. 옛 표가 비어 있으면 아무 줄도 바꾸지 않는다.

UPDATE app_user
SET limit_order_amount_krw = (SELECT f.order_amount_krw FROM financial_order_limit f WHERE f.user_id = app_user.id),
    limit_order_amount_usd = (SELECT f.order_amount_usd FROM financial_order_limit f WHERE f.user_id = app_user.id),
    limit_order_quantity = (SELECT f.order_quantity FROM financial_order_limit f WHERE f.user_id = app_user.id),
    limit_daily_amount_krw = (SELECT f.daily_amount_krw FROM financial_order_limit f WHERE f.user_id = app_user.id),
    limit_daily_amount_usd = (SELECT f.daily_amount_usd FROM financial_order_limit f WHERE f.user_id = app_user.id),
    limit_updated_at = (SELECT f.updated_at FROM financial_order_limit f WHERE f.user_id = app_user.id)
WHERE id IN (SELECT user_id FROM financial_order_limit);

UPDATE connector_action
SET limit_kind = (SELECT c.limit_kind FROM financial_limit_change c WHERE c.action_id = connector_action.id),
    limit_requested = (SELECT c.requested_value FROM financial_limit_change c WHERE c.action_id = connector_action.id),
    limit_previous = (SELECT c.previous_value FROM financial_limit_change c WHERE c.action_id = connector_action.id)
WHERE id IN (SELECT action_id FROM financial_limit_change);
