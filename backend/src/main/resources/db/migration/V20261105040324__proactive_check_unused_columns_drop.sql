-- 먼저 살펴보기 줄에서 읽는 곳이 없는 칸 다섯 개를 지운다(docs/features/proactive/design.md 의 DECISION4).
-- 코드는 이 칸들을 읽고 쓰지 않는다. 비용은 root_execution_id 로 실행 줄을 합쳐 얻는다. tool_calls 와 delegations 는 남긴다.
-- H2 가 한 문장에서 칸 여럿을 지우는 문법을 받지 않아 칸마다 문장을 나눈다.
ALTER TABLE proactive_check DROP COLUMN new_findings;
ALTER TABLE proactive_check DROP COLUMN reference_findings;
ALTER TABLE proactive_check DROP COLUMN tree_input_tokens;
ALTER TABLE proactive_check DROP COLUMN tree_cached_input_tokens;
ALTER TABLE proactive_check DROP COLUMN tree_output_tokens;
