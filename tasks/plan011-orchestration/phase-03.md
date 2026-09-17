# Phase 03. 흐름이 도는 것을 화면에서 본다

**Execution profile**: standard

## 목표

여러 에이전트가 도는 동안 무엇이 어디까지 갔는지 화면에서 보이게 한다.
끝난 뒤에는 plan010 이 만든 나무 화면으로 이어진다.

**범위 외**:
흐름을 화면에서 만들거나 고치는 기능을 만들지 않는다.
DAG 편집기를 만들지 않는다.

## Blocked 조건

plan010 의 phase-03 이 만든 `/executions/[id]` 화면이 없으면
`PHASE_BLOCKED: 실행 나무 화면이 아직 없다` 를 내고 멈춘다.
이 phase 는 그 화면으로 이어지는 것을 만든다.

## 컨텍스트

phase-02 가 흐름 하나를 만들었다.
그 흐름은 실행 넷을 남기고 그중 둘이 나란히 돈다.

지금 대화 화면은 답이 흐르는 것과 도구 이름 한 줄을 보인다.
흐름이 돌면 그것만으로는 무엇이 도는지 알 수 없다.
Researcher 가 도는지 Engineer 가 도는지, 하나가 끝났는지가 보이지 않는다.

한편 그 흐름은 15분 넘게 걸릴 수 있다.
실측한 포지션 추천 하나가 입력 346만 토큰에 그만큼 걸렸고,
그것을 넷으로 나누면 더 걸릴 수도 있다.

**근거 문서**: `docs/adr/ADR-011-실행은-시작할-때-기록하고-끝날-때-갱신한다.md`,
`docs/adr/ADR-008-스트리밍은-보여주기용이고-저장은-실행-결과로-한다.md`,
`docs/code-architecture.md` 의 「web 화면 구조」 절

## 의도 메모

- 네 실행의 답을 모두 화면에 흘리지 않는다.
  Chief 와 Synthesizer 의 답만 사용자에게 쓸모가 있고,
  Researcher 와 Engineer 의 중간 산출물까지 흘리면 읽을 수 없다.
  그 둘은 「도는 중」 과 「끝남」 만 보인다.
- 진행을 따로 조회하지 않는다.
  이미 열려 있는 스트림으로 보낸다. 새 연결을 만들면 그것도 끊기고 재연결을 다뤄야 한다.

## 작업 항목

### 1. 흐름 사건을 스트림으로 보낸다

`ChatEvent` 에 단계 사건을 더한다.

```java
/** 흐름의 한 단계가 시작되거나 끝났다. */
public static ChatEvent step(String stepName, String state);
```

`state` 는 `started` 와 `completed` 와 `failed` 셋이다.
`stepName` 은 `chief`, `researcher`, `engineer`, `synthesizer` 다.

`ResearchAndBuildFlow` 가 단계를 지날 때마다 이 사건을 낸다.
자식 실행 안의 도구 사건은 그대로 흘려보낸다. 다만 어느 단계의 것인지 함께 적는다.

### 2. 대화 화면이 단계를 보인다

`web/src/components/chat/` 아래다.
답을 기다리는 동안 보이는 자리에 단계 목록을 둔다.

```
┌────────────────────────────┐
│ ✓ 정리                      │
│ ⟳ 조사      · web_search    │
│ ⟳ 구현                      │
│   합치기                    │
└────────────────────────────┘
```

| 상태 | 표시 |
| --- | --- |
| 아직 | 흐리게 |
| 도는 중 | 돌아가는 표시와 그 단계의 도구 이름 |
| 끝남 | 완료 표시 |
| 실패 | 오류 표시 |

단계 이름은 한국어로 보인다. `chief` 를 그대로 보이지 않는다.

| 이름 | 화면 |
| --- | --- |
| `chief` | 정리 |
| `researcher` | 조사 |
| `engineer` | 구현 |
| `synthesizer` | 합치기 |

**답이 흘러나오기 시작하면 이 목록을 접는다.**
글자가 늘어나는 것 자체가 진행이고, 그 옆에 진행 표시를 함께 두면
둘 중 무엇을 봐야 할지 모른다. 기존 화면 규칙이 그렇다.

### 3. 끝난 뒤 나무로 가는 길

답이 끝나면 그 답 아래에 「이 답이 어떻게 만들어졌는지 보기」 를 한 줄로 둔다.
누르면 `/executions/{rootExecutionId}` 로 간다.

흐름으로 돈 답에만 보인다. 실행 하나로 끝난 답에는 보이지 않는다.
`ChatEvent.done` 이 `executionId` 를 이미 싣고 있고,
그것이 자식을 가졌는지는 plan010 의 phase-02 가 더한 `hasChildren` 으로 안다.

### 4. 오래 걸리는 것을 알린다

흐름이 시작되고 2분이 지나면 「오래 걸릴 수 있다」 를 한 줄로 알린다.
그 뒤로는 다시 알리지 않는다.

**기다리는 동안 화면을 떠나도 된다는 것을 함께 적는다.**
실행은 Hermes 쪽에서 계속 돌고, 끝난 뒤 다시 열면 저장된 답이 보인다.
ADR-008 이 그렇게 정했다.

### 5. 이 phase 를 검증하는 테스트

`test/browser/flow-progress.spec.ts` 를 새로 만든다.

- **정상 경로**: 흐름으로 도는 대화에서 네 단계가 보이고,
  단계가 끝날 때마다 표시가 바뀐다
- 답이 흘러나오기 시작하면 단계 목록이 접힌다
- **이 phase 가 다루는 실패**: 한 단계가 실패하면 그 단계에 오류 표시가 남고
  나머지 단계가 흐린 상태로 멈춘다
- 흐름이 아닌 대화에는 단계 목록이 보이지 않는다
- `mobile` 과 `desktop` 두 폭에서 가로로 넘치지 않는다

`test/e2e/scenarios/orchestration.ts` 에 더한다.

- 흐름 실행의 스트림에 네 단계의 `step` 사건이 순서대로 들어 있다

## 검증

```bash
# cwd: 저장소 root
cd web && pnpm typecheck && pnpm build
cd web && pnpm test:browser
node test/e2e/run.ts
cd backend && ./gradlew test
```

```bash
# cwd: 저장소 root
grep -rn 'style={{' web/src/components/chat/ | grep -iE 'background|color|border' && echo "실패: 색 인라인 스타일" || echo "통과"
```

브라우저에서 390px 과 1280px 을 열어 본 결과를 보고에 적는다.

## Critical Files

| 파일 | 변경 |
|---|---|
| `backend/src/main/java/com/bifos/assistant/chat/application/ChatEvent.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/orchestration/application/ResearchAndBuildFlow.java` | 수정 |
| `web/src/components/chat/` 의 진행 표시 부품 | 수정 또는 신규 |
| `web/src/components/chat-panel.tsx` | 수정 |
| `test/browser/flow-progress.spec.ts` | 신규 |
| `test/e2e/scenarios/orchestration.ts` | 수정 |

## 끝낸 뒤

`tasks/plan011-orchestration/index.json` 의 이 phase 를 `completed` 로 바꾸고
plan 의 `status` 를 `completed` 로 바꾼다.
