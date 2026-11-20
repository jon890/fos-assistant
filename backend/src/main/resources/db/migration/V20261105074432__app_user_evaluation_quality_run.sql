-- 배포 뒤 확인과 평가에 쓰는 평가 전용 사용자 표시와, 확인·평가 한 번의 기록이다(docs/features/agent-quality/design.md).
-- quality_run 은 테이블을 늘리지 않는다는 결정(2026-11-01)의 예외로 사용자가 승인했다. 확인 한 번은 예약 작업과 수명과 뜻이 다르다.
ALTER TABLE app_user
    ADD COLUMN evaluation BOOLEAN NOT NULL DEFAULT FALSE COMMENT '평가 전용 사용자인가. 참이면 로그인하지 않고 일반 화면과 집계에서 빠진다. 전용 UPDATE 로만 바꾼다';

CREATE TABLE quality_run (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '확인·평가 한 번의 내부 번호',
    public_id BINARY(16) NOT NULL COMMENT '화면과 API 가 쓰는 공개 식별자',
    group_id BIGINT NOT NULL COMMENT '그룹 번호',
    kind VARCHAR(32) NOT NULL COMMENT '종류. DEPLOY_CHECK 는 배포 뒤 기능별 확인이다',
    trigger_type VARCHAR(32) NOT NULL COMMENT '시작 계기. PIPELINE 은 배포 파이프라인, ADMIN 은 관리자',
    status VARCHAR(16) NOT NULL COMMENT 'RUNNING, PASSED, FAILED. 끝난 줄은 다시 바뀌지 않는다',
    started_at DATETIME(6) NOT NULL COMMENT '시작한 시각(UTC)',
    finished_at DATETIME(6) NULL COMMENT '끝난 시각(UTC). 도는 동안 비어 있다',
    item_count INT NOT NULL DEFAULT 0 COMMENT '돈 문항 수',
    model_call_count INT NOT NULL DEFAULT 0 COMMENT '모델 호출 수(실행 줄 수)',
    elapsed_ms BIGINT NULL COMMENT '걸린 시간(밀리초). 끝날 때 채운다',
    result_json JSON NULL COMMENT '기능·문항별 결과 배열. 고정 코드와 숫자, 공개 식별자만 담고 질문·답 본문은 담지 않는다',
    requested_by_user_id BIGINT NULL COMMENT '시작한 관리자(app_user.id). 사용자를 지우면 비운다',
    created_at DATETIME(6) NOT NULL COMMENT '줄을 처음 넣은 시각',
    CONSTRAINT uk_quality_run_public_id UNIQUE (public_id),
    CONSTRAINT fk_quality_run_requested_by FOREIGN KEY (requested_by_user_id) REFERENCES app_user (id) ON DELETE SET NULL,
    INDEX idx_quality_run_group_kind_started (group_id, kind, started_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '배포 뒤 확인과 품질 평가 한 번의 기록. 지우지 않는다';
