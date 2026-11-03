-- 기억 연결의 옛 표 memory_link 를 지운다(ADR-20261102 / table-reduce-r2 의 「기억 연결(D4)」).
-- 효력 있는 대체 연결은 memory.superseded_by_memory_id 로 옮겼고, 직전 이미지가 새로 맺은 줄은 V20261103054422 가 다시 옮겼다.
-- RELATED 연결과 연결 제안은 옮기지 않기로 했으므로 남는 줄이 없다. 되돌리기 어려운 삭제라 이 파일 하나로 둔다.
DROP TABLE memory_link;
