# Phase 02. 가치 평가, 행동 정책, 자동 실행 동의, 매일 루프를 지운다

**Execution profile**: standard

## 목표

판단 층의 코드와 API, 화면, 테스트를 지운다. 테이블은 다음 plan 이 지운다.

**범위 외**: 테이블 DROP, 옛 실행·실패 이력 삭제.

## 컨텍스트

**근거 문서**: `docs/adr/ADR-20261101-proactive-suggestion.md` 의 「옮기는 순서」, `docs/features/proactive.md`

- 지울 하위 기능은 `backend/src/main/java/com/bifos/assistant/proactive/evaluation/`, `autonomy/`, `loop/`, `surface/` 와 `proactive/feedback/` 의 export 다
- 화면은 `web/src/components/agent/agent-value-evaluation-section.tsx`, `agent-proactive-loop-setting.tsx` 와 `web/src/lib/value-evaluation.ts` 다
- `hermes/plugins/dashboard-profile-api/profiles.py` 의 `decision-readiness` 는 판단 profile 이 없어지면 함께 지운다

## 의도 메모

- `AUTONOMY` 계기의 옛 살펴보기 줄과 실패 이력은 지우지 않는다. 읽는 곳만 없앤다
- 대체되는 ADR(value-evaluation, autonomy-policy, daily-loop)은 이 PR 에서 `superseded` 로 바꾸고 `archive/` 로 옮긴다

## PR 나누기

삭제만으로 운영 코드가 4000줄 안팎이라 크기 상한(`scripts/pr/pr-size.mjs`)을 넘는다. `규모:예외` 라벨은 쓰지 않고 아래 두 PR 로 나눈다. PR 마다 그 단계의 문서와 테스트를 함께 담는다. 계획서는 마지막 PR(C)에서 지운다.

| PR | 지우는 것 |
| --- | --- |
| B | `proactive/evaluation/**`, `proactive/autonomy/**`, `proactive/surface/**`, `proactive/feedback/` 의 export, 그 API 와 화면(가치 평가 절, 판정 반응, 「먼저 다룰 문제」 의 `problem` 칸과 반응 단추) |
| C | `proactive/loop/**`, 자동 실행 동의와 `ProactiveCheckService.startAutonomous`, 매일 루프 설정 화면, 대체되는 ADR 의 `superseded` 표시와 `archive/` 이동, hermes 의 `decision-readiness` 와 `decision-profile` |

**B 완료.** `evaluation`, `autonomy`, `surface`, `feedback` 의 export 와 그 API, 화면을 지웠다. 남은 호출은 C 가 지운다. 매일 루프를 잇던 `ProactiveLoopCoordinator` 와 `ProactiveCheckSettled` 사건은 B 에서 함께 지웠다. `surface` 에 있던 살펴보기 상태·시작 컨트롤러와 발견 반응 컨트롤러는 `check.presentation` 으로, 매일 루프 컨트롤러는 `loop.presentation` 으로 옮겼다.

C 가 지울 hermes 파일은 `plugins/dashboard-profile-api/profiles.py` 의 `_decision_readiness_response`, 같은 플러그인의 `routes.py`·`__init__.py` 의 연결, `decision-profile/`, `bundle.sh` 의 복사 줄, 그것을 읽는 `tests/test_dashboard_profile_api_profiles.py`·`tests/test_hermes_contract.py` 의 검사, `docs/hermes-contract.md` 의 절이다. 이 밖에 `ErrorCode` 의 `AUTONOMY_DECISION_NOT_FOUND`, `VALUE_EVALUATION_NOT_FOUND`, `VALUE_EVALUATION_STATE_CONFLICT` 와 화면의 `error-message.ts`, `feedback` 의 `FeedbackLabeler.label`(호출하는 곳이 테스트뿐이다)도 C 에서 정리한다.

B 와 C 의 경계에서 서로 부르는 코드가 남으면(예: `loop` 가 `autonomy` 를 부른다) B 에서 그 호출만 함께 걷어 낸다.

### 지시서 담당 밖이지만 고쳐야 하는 파일

phase 01 을 조사하며 찾은 참조다. 지시서의 담당 목록에 넣는다.

- backend: `admin/proactive/**`(`ValueEvaluationAdminController`, `ProactiveAdminDtos`), `task/application/TaskLoopSettings.java`, `task/application/ProactiveScheduleService.java`, `LivePropertiesConfig.java`, 에이전트 정리(`ProactiveAgentPurge` 를 쓰는 곳)
- backend 테스트: `testsupport/**`(`SurfacedProblemSeed`, `ProactiveLoopTestSupportController`, `BackendIntegrationTest`), `architecture/**`(`ArchitectureRules`, `StoredEnumNamesTest`, `JpaAuditingRules`, `SubFeatureOrder`), `CollationMixQueryMysqlTest`, `agent/AgentPurgeWriterTest`, `proactive/eval/**`, `attention/AttentionJudgeTest`(`AttentionProblem`)
- web: `components/agent/**`, `lib/value-evaluation.ts`, `lib/decision-reaction-api.ts`, `lib/attention.ts`, `app/api/admin/proactive-checks/**`, `app/api/autonomy-decisions/**`, `test/unit/attention.test.ts`, `test/browser/admin-value-evaluation.spec.ts`
- hermes: `plugins/dashboard-profile-api/profiles.py` 의 `decision-readiness`
- 문서: `backend/docs/code-architecture.md`, `docs/adr/` 와 `backend/docs/adr/` 의 대체 ADR(value-evaluation, autonomy-policy, daily-loop)

저장된 enum 값(`CheckTrigger.AUTONOMY`, `AttentionTrigger.PROBLEM_SURFACED`, `FeedbackSubjectType.AUTONOMY_DECISION`)은 옛 줄이 있어 테이블 DROP(plan228) 전까지 지우지 않는다.

## 작업 항목

### 1. 코드 삭제

위 하위 기능과 그 컨트롤러, DTO, enum, 설정 키를 지우고 `SubFeatureOrder` 의 목록을 줄인다. `ProactiveCheckService.startAutonomous` 와 `CheckTrigger.AUTONOMY` 를 새로 시작하는 경로를 지운다.

### 2. 이 phase 를 검증하는 테스트

`backend/src/test/java/com/bifos/assistant/proactive/` 의 판단 층 테스트를 지우고, 계약 테스트 셋이 그대로 통과하는지 본다. 사라진 이름을 `git grep` 으로 찾아 남지 않았는지 본다.

## 검증

```bash
cd backend && ./gradlew test --tests '*Wake*ContractTest' --tests '*ArchitectureRules*'
! git grep -n "ValueEvaluation\|AutonomyPolicy\|ProactiveLoopCoordinator" -- backend/src web/src
```

## 변경 파일

| 파일 | 변경 |
|---|---|
| `backend/src/main/java/com/bifos/assistant/proactive/evaluation/**` | 삭제 |
| `backend/src/main/java/com/bifos/assistant/proactive/autonomy/**` | 삭제 |
| `backend/src/main/java/com/bifos/assistant/proactive/loop/**` | 삭제 |
| `backend/src/main/java/com/bifos/assistant/proactive/surface/**` | 삭제 |
| `backend/src/test/java/com/bifos/assistant/architecture/SubFeatureOrder.java` | 수정 |
| `web/src/components/agent/agent-value-evaluation-section.tsx` | 삭제 |
| `web/src/components/agent/agent-proactive-loop-setting.tsx` | 삭제 |
| `web/src/lib/value-evaluation.ts` | 삭제 |
| `docs/features/proactive.md` | 수정 |
