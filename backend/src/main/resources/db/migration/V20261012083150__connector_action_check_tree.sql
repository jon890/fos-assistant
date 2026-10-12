-- 승인 줄이 먼저 살펴보기 트리의 실행에서 나왔는지다. 그 줄의 승인 카드는 대화 허락을 주지 않는다.
ALTER TABLE connector_action ADD COLUMN check_tree BOOLEAN NOT NULL DEFAULT FALSE;
