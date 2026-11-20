-- 기억마다 매 실행에 들어가는 요약 한 줄을 둔다(docs/features/memory/spec.md 의 REQ1). 제목, 본문과 같은 주인 데이터 key 로 암호화한다.
-- NULL 은 이 칸 전에 만든 줄이라 기동 이관(MemorySummaryBackfill)이 아직 채우지 않은 것이고, 빈 글은 요약이 없는 것이다.
ALTER TABLE memory ADD COLUMN summary TEXT NULL COMMENT '요약 한 줄. data_key_id 가 있으면 암호문. NULL 은 이관 전, 빈 글은 요약 없음';
ALTER TABLE memory_revision ADD COLUMN summary TEXT NULL COMMENT '그 revision 의 요약 한 줄. data_key_id 가 있으면 암호문. NULL 은 요약 기능 전 revision 이라 요약 없음으로 읽는다';
