-- 기억 벡터의 옛 표 memory_embedding 을 지운다(ADR-20261102 / table-reduce-r2).
-- 벡터는 memory 의 embedding_* 칸이 갖는다. 옛 벡터는 옮기지 않고 채우는 작업이 다시 계산하므로 다시 옮길 줄이 없다.
-- 되돌리기 어려운 삭제라 이 파일 하나로 둔다.
DROP TABLE memory_embedding;
