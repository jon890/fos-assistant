# Phase 02. 관리자 영역 에이전트 상세의 가치 평가 절

**Execution profile**: standard

## 목표

`/admin/agents/{code}` 에 「가치 평가」 절을 더한다. 내가 연 마지막 살펴보기를 「이 살펴보기 평가하기」 로 평가하고, 축별 선택과 행동 정책 판정을 읽기 전용으로 보인다.

**범위 외**: backend API(phase 01). 일반 상세 `/agents/{code}` 에는 그리지 않는다. 자동 실행 동의 화면은 만들지 않는다.

## 컨텍스트

**근거 문서**: `docs/frontend/structure.md` 의 「가치 평가 절」 과 관리자 영역 표, `docs/backend/value-evaluation.md` 의 「관리자 화면이 읽는 묶음」

- backend 경로(phase 01): `GET /api/v1/agents/{code}/proactive-check/evaluation`, `POST /api/v1/proactive-checks/{checkId}/evaluation-runs` 본문 `{ "provider": "hermes" }`. 응답 `{ check, evaluation, decisions }`
  - `check`: `{ id, trigger, finishedAt, acceptedCandidates }` 또는 null
  - `evaluation`: `{ id, replayOfId, outcome, failure, candidates: [{ id, problemKey, problem, actionType, sideEffect }], judgements: [{ candidateId, axes: [{ axis, choice, confidence, explanation, evidenceKeys }], confidence, explanation }], orderedCandidateIds, explanation }` 또는 null
  - `decisions`: `[{ id, candidateId, level, reasons, executionStatus }]`
- 서버 컴포넌트는 `callControlPlane` 으로 읽는다(`web/src/components/agent/agent-detail-loader.tsx`). 브라우저는 `app/api/` 서버 라우트만 부른다(`web/AGENTS.md`)
- 프록시 본보기: `web/src/app/api/proactive-checks/[checkId]/report/open/route.ts`(숫자 검사), `web/src/app/api/agents/[code]/proactive-check/runs/route.ts`
- 절 본보기: `web/src/components/agent/agent-proactive-check-section.tsx`. 오류 문구는 `describeError`, `describeFailure`(`web/src/components/error-message.ts`), 상자는 `Notice`, 상태는 `Badge`
- 화면 문구는 해요체. 결과 상태, 실패 코드, 행동 수준, 까닭 코드는 관리자 영역이므로 코드 그대로 그린다
- 브라우저 검사: 기본 사용자는 `ADMIN` 이고 공유 에이전트 코드는 `browser` 다. 살펴보기 준비는 `test/browser/proactive-check.spec.ts` 의 `prepare`, `restore`, `deleteCheckConversation` 과 `hermes.setProactiveOutput` 을 본다. 결과 블록 버전 3 의 `problemCandidates` 칸은 `docs/backend/proactive-check.md` 의 「문제 후보」 가 갖는다. 시험 Control Plane 은 `assistant.value-evaluation.enabled=false` 라 평가가 `FALLBACK`, 판정이 `IGNORE` 다

## 의도 메모

- provider 는 서버 라우트가 `hermes` 로 정한다. 브라우저 요청 본문은 읽지 않는다
- 모델이 쓴 설명은 평문으로 그린다. 마크다운으로도 그리지 않는다
- 판정 묶음이 바뀌면 응답으로 절 상태를 통째로 바꾼다. `router.refresh()` 에 기대지 않는다

## 작업 항목

### 1. `web/src/lib/value-evaluation.ts` 신규

응답 타입(`EvaluationOverview`, `ValueEvaluationView`, `AutonomyDecisionView` 등)과 `runValueEvaluation(checkId: number): Promise<Response>`(`POST /api/proactive-checks/{checkId}/evaluation-runs`).
축 이름 표: `GOAL_ALIGNMENT` 목표와 맞음, `URGENCY` 시급함, `EXPECTED_BENEFIT` 기대 효과, `COST` 비용, `RISK` 위험, `EVIDENCE_QUALITY` 근거의 질.
선택 표: `LOW` 낮음, `MEDIUM` 중간, `HIGH` 높음, `UNKNOWN` 모름. 확신도 같은 말을 쓴다.

### 2. `web/src/app/api/proactive-checks/[checkId]/evaluation-runs/route.ts` 신규

`POST`. `checkId` 가 `^\d+$` 가 아니면 400 `VALIDATION_FAILED`. Control Plane 에 `{ provider: "hermes" }` 를 보내고 상태와 본문을 그대로 넘긴다.

### 3. `web/src/components/error-message.ts`

`VALUE_EVALUATION_NOT_FOUND`: 「평가할 살펴보기를 찾지 못했어요.」, `VALUE_EVALUATION_STATE_CONFLICT`: 「아직 평가할 수 없는 살펴보기예요. 끝난 뒤 다시 눌러 주세요.」 를 더한다.

### 4. `agent-detail-loader.tsx`, `agent-detail-body.tsx`

- loader: `admin` 이고 살펴보기 상태 조회가 성공했을 때만 위 GET 을 읽는다. 실패하면 `{ ok: false, message }`
- body: `valueEvaluation?: Loaded<EvaluationOverview> | null` prop 을 받아, 먼저 살펴보기 절 바로 뒤에 새 절을 그린다. null 이면 그리지 않는다

### 5. `web/src/components/agent/agent-value-evaluation-section.tsx` 신규

`section aria-label="가치 평가"`, 제목 「가치 평가」. `docs/frontend/structure.md` 의 「가치 평가 절」 표대로 그린다.
- 살펴보기 없음: 「평가할 살펴보기가 없어요.」
- 있음: 끝난 시각(`formatWhen`), 「받아들인 문제 후보 N개」, 주 단추 「이 살펴보기 평가하기」. 누르는 동안 단추를 끄고 「평가하는 중이에요」
- 평가: `outcome` 과 `failure` 배지, 비교 설명, 추천 순서. 후보마다 문제 글, 행동 종류와 부작용 힌트, 축 표(축, 선택, 확신, 설명), 판정의 `level`, `reasons`, `executionStatus`
- 오류: 단추 아래 `Notice variant="error"`

### 6. `test/browser/admin-value-evaluation.spec.ts` 신규

- 준비: 점검 대화를 지우고 `proactive-check` 스킬과 `skills` 도구를 켠다. 결과 블록 버전 3 에 지금 확인한 발견 하나와 그 `topicKey` 를 근거로 든 문제 후보 하나(`relatedGoal`, `proposedAction`, `confidence: "HIGH"`, `expectedBenefit`, `sideEffect: "NONE"`)를 넣는다. 문제 키와 행동 글은 시험마다 다르게 만든다
- `POST /api/agents/browser/proactive-check/runs` 뒤 상태 API 가 새 `SUCCEEDED` 를 줄 때까지 폴링한다
- `/admin/agents/browser` 의 「가치 평가」 절에 「받아들인 문제 후보 1개」 와 단추가 보인다. 누르고 응답을 기다린 뒤 `FALLBACK`, `PROVIDER_UNAVAILABLE`, 준비한 문제 글, `IGNORE`, `EVALUATION_NOT_USABLE` 이 보인다
- 화면을 다시 열어도 같은 결과가 보인다
- 일반 상세 `/agents/browser` 에는 「가치 평가」 절이 없다
- 끝나면 `restore` 처럼 스킬, 도구, 점검 대화를 되돌린다

## 검증

```bash
# cwd: 저장소 root
cd web && pnpm lint && pnpm typecheck && pnpm format:check
! grep -rn 'style={{' web/src/components/agent/agent-value-evaluation-section.tsx
cd web && pnpm test:browser admin-value-evaluation.spec.ts
```

모두 종료 코드 0 이어야 한다.

## 변경 파일

| 파일 | 변경 |
|---|---|
| `web/src/lib/value-evaluation.ts` | 신규 |
| `web/src/app/api/proactive-checks/[checkId]/evaluation-runs/route.ts` | 신규 |
| `web/src/components/error-message.ts` | 수정 |
| `web/src/components/agent/agent-detail-loader.tsx` | 수정 |
| `web/src/components/agent/agent-detail-body.tsx` | 수정 |
| `web/src/components/agent/agent-value-evaluation-section.tsx` | 신규 |
| `test/browser/admin-value-evaluation.spec.ts` | 신규 |
| `docs/frontend/structure.md` | 수정 |
