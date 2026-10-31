# Phase 01. 옛 테이블 열 개를 지운다

**Execution profile**: standard

## 목표

옛 읽기와 쓰기가 코드에 남지 않았음을 확인하고 테이블 열 개를 DROP 한다.

**범위 외**: 남은 기능의 동작 변경.

## 컨텍스트

**근거 문서**: `docs/adr/ADR-20261101-proactive-suggestion.md` 의 「옮기는 순서」, `backend/docs/data-schema.md` 의 「마이그레이션 작성 규칙」

- 지울 테이블은 `proactive_autonomy_decision`, `proactive_check`, `proactive_check_finding`, `proactive_check_problem`, `proactive_loop_run`, `proactive_loop_setting`, `proactive_value_evaluation`, `user_autonomy_preference`, `attention_control`, `attention_event` 다
- `task_run.proactive_check_id` 와 `decision_feedback_event.source_check_id`, `autonomy_decision_id` 는 FK 와 함께 먼저 지운다

## 의도 메모

- `proactive_check` 를 지우기 전에 실행 상태가 `agent_execution` 과 `task_run` 에 있는지, 보고 본문이 대화 메시지에 있는지 확인한다. 없으면 멈추고 코디네이터에게 묻는다
- 이미 적용된 마이그레이션 파일은 고치지 않는다

## 작업 항목

### 1. DROP 마이그레이션

`reserve-migration` 으로 받은 경로에 FK 와 컬럼, 테이블 순서로 지우는 DDL 을 둔다. 남은 엔티티와 리포지토리, 정리 SQL(사용자·대화·에이전트 삭제)을 함께 고친다.

### 2. 이 phase 를 검증하는 테스트

사용자, 대화, 에이전트 정리 계약 테스트가 지운 테이블 없이 통과하는지 본다.

## 검증

```bash
cd backend && ./gradlew test
scripts/checks/check-mysql-migration.sh
```

## 변경 파일

| 파일 | 변경 |
|---|---|
| `backend/src/main/java/com/bifos/assistant/proactive/check/**` | 삭제 |
| `backend/docs/data-schema.md` | 수정 |
