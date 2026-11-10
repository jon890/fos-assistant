-- 사람이 없는 실행(먼저 살펴보기, 예약 작업)을 turn 을 열 때 실행 줄에 표시한다. 도구 판정이 attended_only 도구를 거절할 때 트리 루트의 이 값을 본다.
-- 뜻은 docs/features/naver-shopping/design.md 의 「사람이 없는 실행의 표식」 이 갖는다. 새 테이블을 만들지 않는다.
ALTER TABLE agent_execution ADD COLUMN unattended BOOLEAN NOT NULL DEFAULT FALSE COMMENT '사람이 없는 실행인가. 예약 작업과 먼저 살펴보기 turn, 그 결과를 전하는 turn 이 참이고 줄을 만들 때 정한 뒤 바꾸지 않는다';
-- 거절 사유에 ATTENDED_ONLY 가 더해져 설명을 다시 적는다. 타입과 NULL 여부는 V46 의 정의 그대로다.
ALTER TABLE connector_action MODIFY COLUMN deny_reason VARCHAR(40) NULL COMMENT '거절 사유 POLICY_UNAVAILABLE 정책 없음, NOT_READY 미준비, UNDECLARED 미선언, RISK_NOT_OPEN 위험 미개방, ATTENDED_ONLY 사람이 없는 실행, ARGS_TOO_LARGE 인자 초과, READ_ONLY_RUN 읽기 전용, 거절이 아니면 NULL';
