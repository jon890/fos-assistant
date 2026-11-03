-- 기억 항목의 의미 검색용 벡터를 memory 줄의 칸으로 옮긴다(ADR-20261102 / table-reduce-r2 의 「기억 벡터」).
-- 벡터는 옮기지 않는다. 배포 뒤 채우는 작업이 다시 계산한다. 옛 memory_embedding 은 직전 이미지가 쓰도록 남기고 DROP PR 이 지운다.
-- 세 칸은 함께 채우고 함께 비운다. 엔티티는 이 칸을 매핑하지 않고 채우는 작업과 캐시가 전용 쿼리로 읽고 쓴다.
-- H2 가 한 문장의 여러 ADD COLUMN 을 받지 않아 칸마다 문장을 나눈다.
ALTER TABLE memory ADD COLUMN embedding_model VARCHAR(128) NULL COMMENT '벡터를 만든 임베딩 모델 이름. NULL 이면 벡터가 없다';
ALTER TABLE memory ADD COLUMN embedding_fingerprint BINARY(32) NULL COMMENT '모델 이름과 임베딩한 제목·본문의 SHA-256. 지금 글과 다르면 낡은 벡터다. NULL 이면 벡터가 없다';
ALTER TABLE memory ADD COLUMN embedding_vector BLOB NULL COMMENT 'float32 값을 little-endian 으로 이어 붙인 벡터. 길이는 차원 수의 4배 바이트다. NULL 이면 벡터가 없다';
