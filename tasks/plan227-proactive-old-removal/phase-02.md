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
