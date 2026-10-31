# Phase 02. 매일 루프의 사용자별 설정을 CHECK task 로 옮긴다

**Execution profile**: standard

## 목표

`proactive_loop_setting` 의 켜기와 쉬기를 사용자와 에이전트마다 하나인 CHECK `task` 가 갖게 한다. 루프 설정 테이블을 읽는 곳을 없애 순서 7 에서 지울 수 있게 한다.

**범위 외**: 매일 루프 자체(가치 평가와 정책 잇기)의 제거, 테이블 DROP.

## 컨텍스트

**근거 문서**: `docs/adr/ADR-20261101-proactive-suggestion.md` 의 「저장 모델」, `docs/features/proactive.md` 의 「사용자 설정」, `docs/features/schedule.md`

- 지금 설정은 `backend/src/main/java/com/bifos/assistant/proactive/loop/domain/ProactiveLoopSetting.java` 와 `ProactiveLoopSettingService` 가 갖는다. 사용자와 에이전트마다 한 줄이고 줄이 없으면 꺼짐이다
- 매일 깨우기 일정은 `backend/src/main/java/com/bifos/assistant/task/application/ProactiveScheduleService.java` 가 CHECK `task` 로 갖는다. 사용자와 에이전트마다 하나다
- 이 plan 은 `task-trigger-merge` 작업이 머지된 뒤 시작한다. 그 작업이 `task` 와 `task_trigger` 를 고친다

## 의도 메모

- 옮길 값은 켜짐과 `snoozed_until` 둘이다. `task` 에 nullable `snoozed_until` 만 더한다
- 옛 루프 동의만으로 꺼진 CHECK task 를 켜지 않는다. 사용자가 켠 일정과 루프 동의는 다른 동의다
- 공유 에이전트는 사용자마다 task 가 따로라 사용자별 설정이 그대로 남는다. 에이전트 컬럼으로 옮기지 않는다

## 작업 항목

### 1. 마이그레이션과 엔티티

`reserve-migration` 으로 받은 경로에 DDL(`task.snoozed_until`)과 DML(켜진 루프 설정의 `snoozed_until` 을 같은 사용자·에이전트의 CHECK task 로 복사)을 나눠 둔다. `backend/src/main/java/com/bifos/assistant/task/domain/Task.java` 에 칸을 더한다.

### 2. 읽는 곳 바꾸기

`ProactiveLoopSettingService` 의 조회와 저장이 `task` 를 읽고 쓰게 한다. `ProactiveLoopCoordinator` 의 「설정 줄이 없거나 꺼져 있으면」 판정도 같은 값을 본다. 응답 모양은 바꾸지 않는다.

### 3. 이 phase 를 검증하는 테스트

`backend/src/test/java/com/bifos/assistant/proactive/ProactiveLoopSettingTest.java` 를 고쳐 확인한다.

- 같은 사용자·에이전트의 설정은 하나다
- 같은 공유 에이전트를 쓰는 두 사용자의 설정이 서로 바뀌지 않는다
- 옛 동의만 있고 일정이 꺼진 사용자는 옮긴 뒤에도 꺼져 있다

## 검증

```bash
cd backend && ./gradlew test --tests '*ProactiveLoopSettingTest' --tests '*ProactiveLoopCoordinatorTest' --tests '*WakeRunContractTest'
```

## 변경 파일

| 파일 | 변경 |
|---|---|
| `backend/src/main/java/com/bifos/assistant/task/domain/Task.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/proactive/loop/application/ProactiveLoopSettingService.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/proactive/loop/application/ProactiveLoopCoordinator.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/proactive/ProactiveLoopSettingTest.java` | 수정 |
| `backend/docs/data-schema.md` | 수정 |
