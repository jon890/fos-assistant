-- 옛 자동 실행(AUTONOMY)이 연 살펴보기 줄을 지운다(docs/features/proactive/design.md 의 DECISION3).
-- 새로 여는 경로가 없고 읽는 곳도 없다. 줄을 지우면 외래 키의 ON DELETE CASCADE 로 그 살펴보기의 발견과 문제 후보가 함께 지워진다.
-- 그래서 발견·문제 후보를 제안 줄로 옮기는 기동 이관을 마지막으로 돌린 뒤인 이 PR 에서 지운다. 같은 PR 의 다음 파일이 표를 지운다.
-- 반응 기록의 점검 참조(source_check_id)는 ON DELETE SET NULL 로 비워진다. DML 이라 DDL 과 파일을 나눈다.
DELETE FROM proactive_check WHERE trigger_type = 'AUTONOMY';
