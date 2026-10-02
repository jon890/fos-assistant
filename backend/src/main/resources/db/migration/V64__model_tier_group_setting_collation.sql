-- model_tier_group_setting 표의 정렬 규칙을 다른 표와 같은 utf8mb4_0900_ai_ci 로 맞춘다. 표를 다시 만드는 DDL 이라 표마다 파일을 나눈다.
-- 까닭은 docs/backend/schema/README.md 의 「마이그레이션 작성 규칙」 에 있다.
ALTER TABLE model_tier_group_setting CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
