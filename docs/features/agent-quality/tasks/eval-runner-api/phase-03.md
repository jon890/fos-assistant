# Phase 03. 관리자 API 로 평가를 시작하고 결과를 읽는다

**Execution profile**: standard

## 목표

관리자가 평가 묶음 목록을 보고, 묶음과 모델 단계를 골라 평가를 시작하고, 목록과 한 줄, 남겨 둔 평가 대화의 메시지를 읽는다. 가짜 Hermes e2e 로 평가 한 번을 끝까지 돌린다(REQ5, REQ9, REQ15, REQ16, REQ17).

**범위 외**: 화면은 다음 계획이다. 코디네이터가 평가를 시작하고 읽는 스크립트는 이 PR 에서 `test/model-eval/answer/run.ts score-eval` 로 답변 품질 채점만 둔다.

## 컨텍스트

- API 표의 원본은 `docs/features/agent-quality/design.md` 의 「평가 실행 API」 다.
- 관리자 컨트롤러 관례: `admin/quality/presentation/QualityAdminController.java` 는 `currentUser.requireAdmin()` 뒤 `quality.application` 만 부른다(rule-5 의 `admin` 규칙, `ADMIN_USES_ONLY_FEATURE_APPLICATION`). 조회 값은 `quality/application/dto` 의 record 다.
- PR1 의 확인 API(`DeployCheckService`, `DeployCheckApiTest`)와 오류 코드(`EVALUATION_USER_MISSING`, `shared/error/ErrorCode.java`)를 따른다.
- e2e: `test/e2e/run.ts` 의 맨 끝 등록 관례(PR1 의 `test/e2e/scenarios/deploy-check.ts`), 승인 카드 처리는 평가 사용자에게 플러그인을 연결할 길이 e2e 에 없어 `EvalRunnerTest` 가 본다(design 「검증 전략」 REQ16).

**요구사항**: REQ5, REQ9, REQ15, REQ16, REQ17

**근거 문서**: `docs/features/agent-quality/design.md` 의 「평가 실행 API」, 「검증 전략」

## 의도 메모

- 대화 메시지 조회는 평가 사용자의 대화만 연다. 다른 사용자의 대화는 없는 것과 같게 404 다(DECISION8 과 같은 이유).
- e2e 의 묶음은 테스트 폴더에 두고 `ASSISTANT_QUALITY_BUNDLE_LOCATION` 으로 바꾼다. 운영 묶음 파일을 테스트 때문에 고치지 않는다.

## 작업 항목

### 1. `quality/application/EvalService.java` 와 dto

`bundles(admin)`, `start(admin, bundleIds, tier)` → 새로 만들었는지와 줄(평가 사용자 없으면 409 `EVALUATION_USER_MISSING`, 모르는 묶음은 400 `QUALITY_EVAL_BUNDLE_UNKNOWN`, 모르는 단계는 기존 단계 검사의 400), `get`, `list(cursor)`, `messages(admin, conversationPublicId)`. `messages` 는 평가 사용자로 `ChatService.conversationOf(evaluation, publicId)` 와 `history(evaluation, id)` 를 부른다. 평가 사용자의 대화가 아니면 404. dto `EvalBundleView`, `EvalView`, `EvalPage`.

### 2. `ErrorCode`

`QUALITY_EVAL_BUNDLE_UNKNOWN`(400) 를 더한다. 도는 평가가 있을 때는 오류가 아니다. PR1 의 `QualityAdminController.started` 처럼 새 줄이면 202, 도는 줄이면 200 과 그 줄을 돌려준다.

### 3. `QualityAdminController`, `QualityAdminDtos`

`GET /api/v1/admin/quality/eval-bundles`, `POST /api/v1/admin/quality/evals`, `GET /api/v1/admin/quality/evals`, `GET /api/v1/admin/quality/evals/{publicId}`, `GET /api/v1/admin/quality/conversations/{publicId}/messages`.

### 4. 테스트

- `EvalApiTest`(통합): `MEMBER` 는 403, 관리자는 202 와 줄, 두 번째 시작은 200 과 같은 `publicId`, 묶음을 비우면 전체 묶음이 고른 묶음으로 적힘, 모르는 묶음 400, 모르는 단계 400, 고른 단계가 줄에 적힘, 평가 사용자 없으면 409, 다른 사용자의 대화 메시지는 404, 목록이 최근 순.
- e2e `test/e2e/scenarios/eval-run.ts`: 테스트 묶음 `test/e2e/fixtures/quality-bundles/e2e.json`(통과 문항 하나, 실패 문항 하나)으로 평가를 시작해 끝날 때까지 읽는다. 결과 상태, 문항 결과, 통과 문항의 대화가 결과에서 빠지고 실패 대화는 관리자가 읽는지, 다른 사용자의 기억·할 일·예약 작업·대화 수가 전후로 같은지 본다. `test/e2e/run.ts` 의 맨 끝에 등록하고 러너가 묶음 위치(`ASSISTANT_QUALITY_BUNDLE_LOCATION`)와 문항 상한(`ASSISTANT_QUALITY_EVAL_TIMEOUT_OVERRIDE`) 환경 변수를 넘긴다.

## 검증

```bash
cd backend && ./gradlew test --tests 'com.bifos.assistant.admin.quality.EvalApiTest'
cd backend && ./gradlew test
node --test test/repo/quality-bundles.test.ts
node test/e2e/run.ts
node test/model-eval/answer/run.ts --help
```

## 변경 파일

| 파일 | 변경 |
|---|---|
| `backend/src/main/java/com/bifos/assistant/quality/application/EvalService.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/quality/application/dto/EvalBundleView.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/quality/application/dto/EvalView.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/quality/application/dto/EvalPage.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/quality/application/dto/StartedEval.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/quality/application/dto/EvalMessageView.java` | 신규 |
| `test/model-eval/control-plane.ts` | 수정 |
| `test/model-eval/answer/run.ts` | 수정 |
| `backend/src/main/java/com/bifos/assistant/shared/error/ErrorCode.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/admin/quality/presentation/QualityAdminController.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/admin/quality/presentation/QualityAdminDtos.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/admin/quality/EvalApiTest.java` | 신규 |
| `test/e2e/scenarios/eval-run.ts` | 신규 |
| `test/e2e/fixtures/quality-bundles/e2e.json` | 신규 |
| `test/e2e/run.ts` | 수정 |
