-- 판단 피드백 사건에 근거 JSON 칸을 더한다(ADR-20261015 / memory-maintenance). 정비 루프의 규칙 버전과 모드, 그때의 신호 값을 담는다.
-- 기존 줄은 모두 비어 있다. 제목과 본문은 담지 않는다.
ALTER TABLE decision_feedback_event
    ADD COLUMN detail JSON NULL COMMENT '정비 사건의 근거. 규칙 버전, 모드, 그때의 신호 값, 확인 사건이면 확인한 후보 사건 번호. 제목과 본문은 담지 않는다';
