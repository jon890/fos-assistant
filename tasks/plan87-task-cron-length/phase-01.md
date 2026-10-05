# Phase 01. cron 식 길이를 저장 전에 검사한다

**Execution profile**: fast

## 목표

`POST /api/v1/tasks` 와 `PUT /api/v1/tasks/{taskId}` 가 앞뒤 공백을 뗀 cron 식이 100자를 넘으면 400 `TASK_SCHEDULE_INVALID` 를 낸다. 지금은 저장에서 실패해 500 이 난다.

**범위 외**: cron 문법과 최소 간격 검사(이미 있다). `ONCE` 시각.

## 컨텍스트

- 저장 칸: `backend/src/main/java/com/bifos/assistant/task/domain/TaskTrigger.java` 의 `@Column(name = "cron_expr", length = 100) private String cronExpr`. 마이그레이션 `V68__task.sql` 의 `cron_expr VARCHAR(100) NULL`
- 이름 길이는 `Task.TITLE_MAX = 100` 상수를 엔티티 `@Column(length = TITLE_MAX)` 와 `TaskService.title` 검사가 함께 쓴다. 같은 모양을 따른다
- 요청 시각은 `backend/src/main/java/com/bifos/assistant/task/application/TaskService.java` 의 `parsed(ScheduleInput input)` 가 저장 모양으로 바꾼다. `CRON` 이면 `input.cron().strip()` 을 쓴다. `validated` 와 `sameSchedule` 이 모두 `parsed` 를 거친다
- 오류 코드 `ErrorCode.TASK_SCHEDULE_INVALID` 는 `HttpStatus.BAD_REQUEST` 다
- 화면: `web/src/components/task/task-form.tsx` 의 `id="task-cron"` 입력 칸. 이름 칸은 `maxLength={100}` 을 이미 단다

**근거 문서**: `docs/backend/task.md` 의 「시각」 표 `CRON` 줄(이 plan 이 이미 고쳤다)

## 작업 항목

### 1. 상수와 서버 검사

- `TaskTrigger` 에 `public static final int CRON_MAX = 100;` 을 두고 `@Column(name = "cron_expr", length = CRON_MAX)` 로 쓴다. 마이그레이션은 고치지 않는다
- `TaskService.parsed` 의 `CRON` 분기에서 `strip()` 한 값의 길이가 `TaskTrigger.CRON_MAX` 를 넘으면 `new ApiException(ErrorCode.TASK_SCHEDULE_INVALID, "a cron expression must have at most " + TaskTrigger.CRON_MAX + " characters")` 를 던진다. cron 을 읽기 전에 본다

### 2. 화면 입력 칸

`task-form.tsx` 의 `task-cron` `Input` 에 `maxLength={100}` 을 단다. 이름 칸과 같은 방식이다.

### 3. 이 phase 를 검증하는 검사

`backend/src/test/java/com/bifos/assistant/task/TaskControllerTest.java` 에 더한다. 같은 파일의 `send`, `request`, `cronSchedule`, `body` 를 쓴다.

| `@DisplayName` | 입력 | 기대 |
| --- | --- | --- |
| 100자를 넘는 cron 은 400 TASK_SCHEDULE_INVALID 다 | 만들기 요청의 cron 으로 `"0 9 1 * " + "1,".repeat(50) + "1"` 처럼 101자 이상인 글 | 400, 오류 코드 `TASK_SCHEDULE_INVALID`. 작업 줄이 생기지 않는다 |
| 고치기도 100자를 넘는 cron 을 거절한다 | 정상 작업을 만든 뒤 같은 긴 cron 으로 `PUT` | 400 `TASK_SCHEDULE_INVALID`. 저장된 cron 이 그대로다 |
| 앞뒤 공백을 뗀 100자 cron 은 길이로 거절하지 않는다 | 공백을 붙여 101자 이상이지만 `strip()` 하면 100자 이하인 유효 cron | 길이 오류가 아니다(유효하면 200) |

오류 응답에서 코드를 읽는 방법은 같은 파일의 기존 400 검사를 따른다.

## 검증

```bash
cd backend && ./gradlew test --tests 'com.bifos.assistant.task.*'
scripts/check-local.sh tasks
```

- 첫 줄: 새 검사 셋과 기존 작업 검사가 통과한다
- 둘째 줄: 전체 로컬 검사. 브라우저 검사는 `test/browser/tasks.spec.ts` 만 돈다

## 변경 파일

| 파일 | 변경 |
| --- | --- |
| `backend/src/main/java/com/bifos/assistant/task/domain/TaskTrigger.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/task/application/TaskService.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/task/TaskControllerTest.java` | 수정 |
| `web/src/components/task/task-form.tsx` | 수정 |
