# Phase 03. 어느 모델로 돌았고 무엇이 막혔는지 화면이 보인다

**Execution profile**: standard

## 목표

관리자가 에이전트의 모델 목록을 화면에서 고치고,
사용자가 대화에서 모델이 바뀐 것을 알고,
막힌 provider 가 무엇인지 볼 수 있다.

**범위 외**:
막힘을 손으로 푸는 단추를 만들지 않는다. 시간이 지나면 풀리고 성공하면 풀린다.
모델을 대화마다 고르는 화면을 만들지 않는다. 에이전트가 정한다.

## 컨텍스트

phase-01 이 에이전트마다 모델 목록을 만들고 API 를 열었다.
phase-02 가 막혔을 때 넘기고 `PROVIDER_SWITCHED` 사건을 남긴다.

**지금은 그 둘이 화면에 없다.**
관리자는 목록을 API 로만 고칠 수 있고,
사용자는 모델이 바뀐 것을 알 길이 없다.

화면은 다섯이고 이 phase 는 그중 셋을 건드린다.

| 경로 | 무엇을 더하나 |
| --- | --- |
| `/admin/agents` | 모델 목록을 고치는 자리 |
| `/` | 모델이 바뀐 것을 알리는 한 줄 |
| `/usage` | 실패한 시도와 넘어간 것 |

**근거 문서**: `docs/code-architecture.md` 의 「web 화면 구조」 절.

## 의도 메모

- 대화 화면에 provider 이름을 그대로 쓰지 않는 안을 검토했다가 버렸다.
  `nvidia` 나 `openai-codex` 는 사용자가 볼 이름이 아닌 것 같았지만,
  쓰는 사람이 개발자이고 어느 모델로 돌았는지가 판단에 쓰인다.
- 막힘을 관리 화면에 크게 보이는 안을 버렸다.
  평소에는 아무것도 막히지 않아 빈 자리가 대부분이다.
  막힌 것이 있을 때만 그린다.

## 작업 항목

### 1. 관리 화면에서 모델 목록을 고친다

`/admin/agents` 의 에이전트마다 모델 목록을 보이고 고칠 수 있게 한다.

| 무엇 | 어떻게 |
| --- | --- |
| 목록 | 순위 순서로. 1순위가 위 |
| 더하기 | 줄을 하나 더한다 |
| 지우기 | 줄을 지운다 |
| 순서 바꾸기 | 위아래로 옮긴다 |
| 저장 | `PUT` 으로 목록 전체를 보낸다 |

**마지막 한 줄을 지우지 못하게 막는다.** 빈 목록은 서버가 거절하는데,
화면에서 먼저 막아야 저장을 누른 뒤에 알게 되지 않는다.

`provider` 와 `model` 은 글자로 받는다. 고르는 목록을 만들지 않는다.
Hermes 가 어떤 provider 와 모델을 아는지 우리가 알 방법이 없고,
목록을 만들면 새 모델이 나올 때마다 화면을 고쳐야 한다.

### 2. 모델이 바뀐 것을 대화에서 알린다

`PROVIDER_SWITCHED` 사건이 있으면 그 답 위에 한 줄을 그린다.

```
── 여기부터 nvidia/nemotron-3-super-120b-a12b 로 돈다 ──
```

**답을 읽는 흐름을 끊지 않게 작게 그린다.**
경고가 아니다. 사실을 알리는 것이다.

**막힌 쪽의 오류 문장을 그리지 않는다.** 상류가 보낸 문장이라 무엇이 올지 모른다.
넘어간 곳만 적는다.

### 3. 사용량 화면이 실패한 시도를 구분한다

실행 목록에 `PROVIDER_BLOCKED` 로 실패한 실행이 나온다.
그것이 그냥 실패와 같아 보이면 무엇이 일어난 것인지 알 수 없다.

| 상황 | 화면 |
| --- | --- |
| `PROVIDER_BLOCKED` 로 실패 | 「막혀서 다음 모델로 넘어감」 |
| `NO_MODEL_AVAILABLE` 로 실패 | 「쓸 수 있는 모델이 없음」 |
| `retry_of_execution_id` 가 있음 | 앞 실행에서 이어진 것임을 보인다 |

**넘어가서 실패한 실행을 합계에서 빼지 않는다.**
토큰을 쓰지 않았으므로 금액이 0 이고, 목록에는 남아야 무엇이 있었는지 안다.

### 4. 막힌 provider 를 관리 화면에 보인다

`/admin/agents` 위쪽에 지금 막힌 provider 를 보인다.

```
막힌 provider: openai-codex (12분 남음)
```

**막힌 것이 없으면 이 줄을 그리지 않는다.**
평소에는 비어 있고, 빈 자리를 두면 무엇을 봐야 하는지 흐려진다.

읽는 경로를 하나 연다.

| 메서드와 경로 | 하는 일 |
| --- | --- |
| `GET /api/v1/admin/providers/blocked` | 지금 막힌 것만 준다 |

### 5. 이 phase 를 검증하는 테스트

`test/browser/` 에 더한다.

- **이 phase 가 고치는 것**: 관리 화면에서 모델 목록을 고쳐 저장하면 그 순서로 남는다
- 마지막 한 줄은 지울 수 없다
- 넘어간 대화에 「여기부터 ... 로 돈다」 한 줄이 보인다
- 막힌 provider 가 없으면 그 줄이 그려지지 않는다
- `PROVIDER_BLOCKED` 실패와 보통 실패가 다르게 보인다
- 두 폭 모두에서 모델 목록이 가로로 넘치지 않는다

## 검증

```bash
# cwd: 저장소 root
cd backend && ./gradlew test
cd web && pnpm typecheck && pnpm build
cd web && pnpm test:browser
node test/e2e/run.ts
scripts/check-public-safe.sh
```

**배포한 뒤 관리 화면에서 목록을 한 번 고쳐 본다.**
`bifos` 에 `nvidia` 를 1순위로, `openai-codex` 를 2순위로 두고 저장해 그대로 남는지 본다.

## Critical Files

| 파일 | 변경 |
|---|---|
| `web/src/components/admin/` 의 에이전트 화면 | 수정 |
| `web/src/components/admin/agent-model-list.tsx` | 신규 |
| `web/src/components/chat/` 의 답 목록 | 수정 |
| `web/src/components/usage/execution-list.tsx` | 수정 |
| `web/src/app/api/admin/agents/[code]/models/route.ts` | 신규 |
| `web/src/app/api/admin/providers/blocked/route.ts` | 신규 |
| `backend/src/main/java/com/bifos/assistant/agent/presentation/AgentAdminController.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/agent/presentation/AgentDtos.java` | 수정 |
| `test/browser/admin.spec.ts` | 수정 |
| `test/browser/chat.spec.ts` | 수정 |

## 끝낸 뒤

`tasks/plan013-model-selection/index.json` 의 이 phase 를 `completed` 로,
plan 의 `status` 를 `completed` 로 바꾼다.
