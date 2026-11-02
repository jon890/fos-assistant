-- 먼저 살펴보기를 줄이며 쓰지 않게 된 테이블 일곱 개를 지운다(ADR-20261101 / proactive-suggestion 의 옮기는 순서 7).
-- 읽고 쓰는 코드는 모두 지웠다. 숨김과 미루기의 현재값과 사건은 반응 기록(decision_feedback_event)으로 먼저 옮겼다.
-- 되돌리기 어려운 삭제라 다른 변경과 섞지 않고 이 파일 하나로 둔다. 외래 키가 가리키는 쪽을 나중에 지운다.
DROP TABLE proactive_autonomy_decision;
DROP TABLE proactive_loop_run;
DROP TABLE proactive_loop_setting;
DROP TABLE proactive_value_evaluation;
DROP TABLE user_autonomy_preference;
DROP TABLE attention_control;
DROP TABLE attention_event;
