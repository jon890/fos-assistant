-- revision 의 사진 참조 칸을 NOT NULL 로 바꾼다(ADR-20261102 / table-reduce-r2 의 원고 D1).
-- 앞 파일(V20261102074320)이 직전 이미지가 칸 없이 쓴 revision 을 content_draft_asset 에서 다시 채웠다. 비어 있는 줄이 남았으면 이 문장이 실패해 배포가 멈춘다.
-- MODIFY COLUMN 은 COMMENT 를 새로 적어야 하므로 NULL 을 허용한다는 문구를 뺀 설명으로 다시 적는다.
ALTER TABLE content_draft_revision MODIFY COLUMN assets_json LONGTEXT NOT NULL COMMENT '본문의 사진 블록이 가리키는 첨부의 JSON 배열 문자열(암호문 아님). 항목은 position(블록 순서), attachmentId, sourceFingerprint(첨부 원본 SHA-256 16진수), observationId, observationRevision(관찰을 가리키지 않으면 둘 다 null). 사진이 없으면 []';
