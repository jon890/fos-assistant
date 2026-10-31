# Phase 01. 정비 루프를 제안 모드로 돌리고 사람이 후보를 확인하는 화면을 만든다

**Execution profile**: standard

## 목표

하루 한 번 도는 정비 작업이 정해진 규칙으로 기억의 정비 후보를 만들고, 동작은 적용하지 않은 채 후보와 근거만 `decision_feedback_event` 의 사건으로 남긴다. 새 테이블을 만들지 않는다(2026-11-01 사용자 결정).
사람(항목의 주인, 그룹 항목은 `ADMIN`)이 후보를 「맞음」, 「아님」 으로 확인하고, 동작마다 확인한 정밀도를 운영 집계로 읽는다.
`/memory` 에서 자동 보관 허락 표시(`archivable`)를 보이고 고친다.

**범위 외**: 동작을 자동으로 적용하는 것, 하루치 요약 알림, 규칙 버전 되돌리기, 검색 설정 바꾸기, 먼저 살펴보기 연결. 모두 단계 4 다.

## 컨텍스트

**근거 문서**: `docs/adr/ADR-20261015-memory-maintenance.md` 의 「결정」 과 「적용 범위」, `docs/features/memory.md` 의 「자동 기억 2단계」, 「참고한 기억의 단추」, 「기억 사이의 연결」, `backend/docs/data-schema.md` 의 「memory」 와 「decision_feedback_event」

- 단계 3 의 첫 PR 이 만든 것: `memory.archivable`(기본 거짓), `memory_remember` 의 선택 인자 `archivable`, 「참고한 기억」 단추 사건 `MARKED_IRRELEVANT`, `MARKED_WRONG`(열쇠 `memory:<번호>`). 기억 내용은 단추로 바뀌지 않는다
- 신호의 자리는 ADR 의 신호 표가 갖는다. 실린 횟수와 마지막으로 실린 때는 `execution_context_source` 의 `MEMORY_*` 줄, 읽힌 횟수는 `MEMORY_READ` 줄, 정정은 `memory_revision` 과 `memory_capture`, 단추는 `decision_feedback_event` 다
- 보관은 `memory.retrieval` 을 `ARCHIVE` 로 바꾸는 것이다(`MemoryRetrieval.ARCHIVE`). 주입과 검색이 `ARCHIVE` 를 고르지 않는다
- 하루 한 번 도는 작업의 모양은 `DecisionFeedbackCleaner`(`@Scheduled(cron = ...)`, 검사에서는 `-` 로 끔)를 따른다
- 대체 후보는 단계 2 의 `SupersedeCandidates` 와 `memory_link` 의 `PROPOSED` 가 이미 만든다. 이 계획은 그것을 다시 만들지 않고 후보 확인 화면에 함께 보이기만 한다

## 의도 메모

- **판단의 분류를 컬럼이나 enum 으로 굳히지 않는다(2026-10-16 사용자 결정).** 구조로 두는 것은 Control Plane 이 되돌리는 방법이 다른 동작 종류(`action`)와 모드, 확인 결과뿐이다. 규칙이 본 신호 값은 `evidence` JSON 하나에 둔다. 규칙이 바뀌어도 스키마는 그대로다
- 제목과 본문을 사건에 남기지 않는다. 화면은 지금 제목을 `memory` 에서 읽는다. 지운 항목의 후보는 화면에서 빠진다
- 기준 정밀도와 확인 표본 수(ADR 이 이 계획에서 정하라고 한 값): 동작마다 확인한 후보가 30건 이상이고 「맞음」 비율이 0.9 이상이면 그 동작을 자동 전환 후보로 본다. 전환 자체는 단계 4 이고, 이 계획은 그 수치를 읽는 운영 집계만 만든다. 값은 설정(`assistant.memory.maintenance.*`)으로 두어 단계 4 가 바꿀 수 있게 한다
- 규칙 버전(`rule_version`)은 정수로 두고 규칙 클래스의 상수가 갖는다. 같은 대상과 같은 동작의 열린 후보가 있으면 새 후보를 만들지 않는다
- 첫 규칙은 둘이다. 숫자는 첫 값이고 4주 동안 확인 결과를 보고 고친다
  - `ARCHIVE`: `ACCEPTED` 이고 `retrieval` 이 `ARCHIVE` 가 아니며, 90일 동안 실리지도 읽히지도 않았고, `MARKED_IRRELEVANT` 나 `MARKED_WRONG` 이 1건 이상인 항목. `archivable` 이 거짓이어도 후보는 만들고 근거에 그 값을 적는다. 거짓인 항목은 단계 4 에서도 자동 보관하지 않는다
  - `DEDUP`: 같은 주인과 같은 지문(제목과 본문의 해시)을 가진 `ACCEPTED` 항목이 둘 이상이면 오래된 쪽을 남기고 새 쪽을 후보로 둔다
- 사람이 「맞음」 을 눌러도 이 단계에서는 동작을 적용하지 않는다. 확인 결과만 남긴다. 적용은 사람이 지금의 `/memory` 고치기로 한다

## 작업 항목

### 1. 정비 사건과 근거 컬럼

새 테이블을 만들지 않는다. `decision_feedback_event` 에 사건 종류와 JSON 컬럼 하나를 더한다.
마이그레이션 파일 이름은 직접 정하지 않고 orch 의 `reserve-migration` 으로 받는다. 새 컬럼에 한국어 COMMENT 를 단다(`SchemaCommentsMigrationTest`).

| 사건 종류 | 주체 | 뜻 |
| --- | --- | --- |
| `MAINTENANCE_PROPOSED` | `SYSTEM` | 정비 작업이 후보를 만들었다 |
| `MAINTENANCE_CONFIRMED` | `USER` | 사람이 후보를 「맞음」 으로 확인했다 |
| `MAINTENANCE_DENIED` | `USER` | 사람이 후보를 「아님」 으로 확인했다 |

- 열쇠는 `memory:<번호>`, 동작 종류(`ARCHIVE`, `DEDUP`)는 `reason_code`, 그때의 revision 번호는 `subject_version` 이다
- 새 컬럼 `detail`(JSON, 비어 있을 수 있음)에 규칙 버전, 모드(`PROPOSE`), 그때의 신호 값을 담는다. 확인 사건은 어느 후보 사건을 확인했는지 그 번호를 담는다
- 단계 4 가 `MAINTENANCE_APPLIED`, `MAINTENANCE_REVERTED` 를 더한다
- 「참고한 기억」 단추 사건처럼 반응 읽기(`FeedbackLabeler`)와 replay 읽기 모델(`DecisionFeedbackExporter`), 대화 삭제의 열쇠 확장에서 뺀다. `FeedbackEventType.marksMemoryUse` 를 정비 사건까지 넓혀 한 판정으로 둔다
- `user_id` 는 개인 항목이면 주인이다. 그룹 항목은 그 그룹의 `ADMIN` 마다 후보 사건을 하나씩 남기고, 한 사람이 확인하면 같은 열쇠와 동작의 다른 사람 후보도 확인한 것으로 본다. 사건 기록이 사용자의 것이라 `user_id` 가 비지 않게 하려는 배치다
- 열린 후보는 같은 열쇠와 동작의 `MAINTENANCE_PROPOSED` 뒤에 확인 사건이 없는 것이다. 인덱스 `idx_decision_feedback_event_subject` 로 찾는다
- 보관 기간 정리(마지막 사건 뒤 365일)는 그대로 적용된다. 확인 결과를 집계하는 기간(4주)보다 길다
- `backend/docs/data-schema.md` 의 「decision_feedback_event」 에 새 컬럼과 사건을 적는다

### 2. 정비 작업 `memory.application.MemoryMaintenanceJob`

- 하루 한 번(`assistant.memory.maintenance.cron`) 규칙 둘을 돌려 후보를 남긴다. 기억과 링크는 바꾸지 않는다
- 로그에는 동작별 후보 수만 남긴다

### 3. 후보 확인 API 와 화면

- `GET /api/v1/memory/maintenance-candidates`: 요청자가 고칠 수 있는 항목의 확인 전 후보를 지금 제목, 동작, 근거 요약과 함께 낸다
- `PUT /api/v1/memory/maintenance-candidates/{id}/review`(`{id}` 는 후보 사건 번호) 본문 `{ "review": "CORRECT" | "INCORRECT" }`. 고칠 수 없는 사람과 없는 후보는 같은 404 다
- `/memory` 에 「정비 후보」 절을 더한다. 줄마다 제목과 동작, 「맞음」, 「아님」 단추와 그 항목을 여는 링크를 둔다
- `/memory` 의 항목 줄에 「자동 보관 허락」 표시를 보이고, 고칠 수 있는 사람이 「고치기」 에서 켜고 끈다
- 「참고한 기억」 의 「틀림」 뒤 링크가 그 항목을 바로 열게 한다(`/memory?memory=<번호>` 를 받아 그 줄을 펼치고 스크롤한다)

### 4. 운영 집계

`docs/features/memory.md` 의 「운영 집계」 에 동작별 확인 수와 「맞음」 비율을 더한다. 기준(30건, 0.9)을 넘은 동작을 표시한다.

### 5. 문서

- `docs/features/memory.md` 의 단계 표와 새 절 「정비 루프」
- ADR 의 「감당할 것」 에 정한 기준 값을 적는다

## 검증

```bash
# cwd: backend/
./gradlew test --tests '*MemoryMaintenance*' --tests '*SchemaCommentsMigrationTest'
# cwd: 저장소 root
scripts/dev/check-local.sh --fast
```

테스트가 확인할 것:

- 정비 작업이 후보만 남기고 `memory` 의 칸(제목, 본문, 판, `retrieval`, `archivable`)과 `memory_link` 를 바꾸지 않는다
- `archivable` 이 거짓인 항목도 `ARCHIVE` 후보가 되고 근거에 그 값이 남는다
- 같은 대상과 동작의 열린 후보가 있으면 두 번째 실행이 새 줄을 만들지 않는다
- 남의 후보는 읽지도 확인하지도 못하고 없는 후보와 같은 404 다
- 확인 결과를 남겨도 기억은 바뀌지 않는다
