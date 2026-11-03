-- 기억 항목을 대신한 새 항목의 번호를 memory 줄의 칸으로 옮길 자리를 만든다(ADR-20261102 / table-reduce-r2 의 「기억 연결(D4)」).
-- 코드는 이 칸이 비어 있는 항목만 실린다고 본다. 옛 memory_link 는 직전 이미지가 읽도록 남기고 DROP PR 이 지운다.
-- 양 끝에 외래 키를 걸지 않는다. 항목을 지우거나 대신한 새 항목이 승인을 벗어나면 서비스가 같은 트랜잭션에서 이 칸을 비운다.
ALTER TABLE memory ADD COLUMN superseded_by_memory_id BIGINT NULL COMMENT '이 항목을 대신한 새 항목의 번호. NULL 이면 대체되지 않은 항목이고, 값이 있으면 어디에도 실리지 않는다';

CREATE INDEX idx_memory_superseded_by ON memory (superseded_by_memory_id);
