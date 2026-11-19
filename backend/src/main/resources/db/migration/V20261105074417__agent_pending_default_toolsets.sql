-- Control Plane 이 기본 비서에 켜 주기로 했지만 아직 켜지 못한 기본 도구를 에이전트 줄에 둔다(ADR-20261119 / image-gen-defaults, 스펙 generated-image REQ11, REQ13).
-- 켜지거나 관리자가 그 도구를 끄면 비운다. 값을 넣는 일은 다음 파일이 한다.
ALTER TABLE agent ADD COLUMN pending_default_toolsets VARCHAR(255) NULL COMMENT 'Control Plane 이 스스로 켜려고 기다리는 기본 도구 이름을 쉼표로 이은 글. 켜졌거나 관리자가 끄면 NULL';
