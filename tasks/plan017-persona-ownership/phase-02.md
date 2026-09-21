# Phase 02. 화면에서 자기 에이전트의 성격을 고친다

**Execution profile**: standard

## 목표

가족 구성원이 화면에서 자기 에이전트의 성격을 읽고 고친다.
그 사이에 홈서버를 만지지 않는다.

**범위 외**:
화면에서 에이전트를 새로 만드는 것은 이 plan 이 하지 않는다.
에이전트를 등록하는 경로는 지금처럼 관리 화면에 있다.
성격 말고 모델과 공개 범위를 고치는 것도 지금 자리에 그대로 둔다.

## 컨텍스트

phase-01 이 읽고 쓰는 경로를 만들었다. 이 phase 가 화면을 붙인다.

지금 화면에는 `/admin/agents` 밖에 없고 그것은 `ADMIN` 만 연다.
**성격은 `MEMBER` 도 고쳐야 하므로 관리 화면에 둘 수 없다.**

**근거 문서**:
`docs/code-architecture.md` 의 「페르소나」 절과 「web 화면 구조」 절,
`docs/flow.md` 의 「페르소나를 고칠 때」 절,
`docs/adr/ADR-019-페르소나는-hermes-가-갖고-control-plane-은-화면만-준다.md`.

### 목록에 성격 상태를 담지 않는다

정본이 하나라 「반영됨」 과 「미반영」 이 갈리지 않는다.
`AgentView` 에 칸을 더하지 않고, 목록이 성격을 읽으려고 에이전트마다 대시보드를 부르지도 않는다.
목록은 지금 그대로 두고 각 줄에서 성격 화면으로 가는 길만 낸다.

## 의도 메모

- 성격 편집을 `/admin/agents` 에 두는 안을 버렸다. 그 화면은 `ADMIN` 만 연다.
- 대화 화면 안에서 고치는 안을 버렸다.
  대화 도중에 성격이 바뀌면 그 대화의 앞뒤가 다른 성격으로 답한 글이 된다.
  화면을 나눠 고치러 가는 걸음을 남긴다.
- 저장할 때 확인을 한 번 받는다. 앞 본문이 사라지고 돌아갈 자리가 없다.
  ADR-019 의 「옛 글로 돌아갈 길이 없다」 가 그 까닭을 갖는다.

## 작업 항목

### 1. 서버 라우트

`web/src/app/api/agents/` 아래에 둔다.
같은 디렉터리의 `route.ts` 가 Control Plane 을 부르는 방식을 그대로 따른다.
브라우저가 Control Plane 을 직접 부르지 않는다. `web/AGENTS.md` 가 그렇게 정한다.

| 파일 | 부르는 것 |
| --- | --- |
| `[code]/persona/route.ts` | `GET` 과 `PUT /api/v1/agents/{code}/persona` |

**`web/src/app/api/agents/route.ts` 는 이미 있다.** 그대로 둔다.

### 2. `/agents` 화면

내가 쓸 수 있는 에이전트 목록이다.
`web/src/app/agents/page.tsx` 와 `web/src/components/agent/` 에 부품을 둔다.

한 줄에 이름과 모델을 보이고, 그 줄에서 `/agents/{code}` 로 간다.

빈 상태는 `components/ui/empty-state.tsx` 를 쓴다.
쓸 수 있는 에이전트가 없으면 「쓸 수 있는 에이전트가 없습니다」 를 보인다.

### 3. `/agents/{code}` 화면

그 에이전트의 성격을 보고 고치는 곳이다.

- 본문 편집창. 아래에 남은 글자 수를 보인다. 상한은 응답의 `maxChars` 가 준다
- 저장 단추. `editable` 이 거짓이면 그리지 않고 편집창을 읽기 전용으로 둔다
- **저장을 누르면 확인을 한 번 받는다.** 앞 본문이 사라지고 돌아갈 자리가 없다

응답의 `bodyHash` 를 들고 있다가 저장할 때 `baseHash` 로 돌려보낸다.
화면이 그 값을 만들지 않는다. 받은 것을 그대로 보낸다.

상태를 넷 보인다.

| 상태 | 무엇을 보이나 |
| --- | --- |
| 본문이 비어 있다 | 빈 편집창과 「아직 성격을 쓰지 않았습니다」 |
| 저장 중 | 단추를 잠그고 도는 표시 |
| 저장됨 | 「저장되었습니다」 |
| 읽지 못했다 | 편집창 대신 까닭을 보인다. 저장 단추를 그리지 않는다 |

오류를 셋 갈라 보인다.

| 오류 | 문장 |
| --- | --- |
| `PERSONA_STALE` | 그 사이 다른 사람이 고쳤다고 알리고 본문을 다시 읽어 보인다 |
| 길이 초과 | 몇 자를 줄여야 하는지 보인다 |
| `HERMES_UNAVAILABLE` | 닿지 못했다고 알린다. 쓴 글이 편집창에 그대로 남아 다시 누를 수 있다 |

**남은 글자 수의 표시 형식을 하나로 정한다.**
상한을 넘으면 음수가 드러나야 하므로 「남은 N자」 로 적고, 넘으면 저장 단추를 잠근다.
「N / 8000자」 로 적으면 넘긴 것이 표시에 드러나지 않는다.

색과 간격은 테마 토큰만 쓴다. `web/AGENTS.md` 가 그것을 정한다.

### 4. 들어가는 길

`web/src/components/ui/site-nav.tsx` 의 `LINKS` 에
`{ href: "/agents", label: "에이전트" }` 를 더한다.
`ADMIN` 에게만 붙는 「에이전트 관리」 는 그대로 둔다.

`web/src/app/admin/agents` 의 각 줄에서 그 에이전트의 `/agents/{code}` 로 가는 링크를 둔다.

### 5. 이 phase 를 검증하는 테스트

`test/browser` 에 화면 검사를 더한다.
기존 검사 파일의 짜임을 따르고 `mobile` 과 `desktop` 두 폭에서 돈다.

| 무엇 | 기대 |
| --- | --- |
| 자기 에이전트의 성격 화면을 연다 | 편집창과 저장 단추가 보인다 |
| 성격을 저장한다 | 확인을 받은 뒤 「저장되었습니다」 가 보인다 |
| 확인에서 취소한다 | 저장 경로가 불리지 않는다 |
| 8000자를 넘겨 적는다 | 저장 단추가 잠기고 남은 글자 수가 음수로 보인다 |
| 고칠 수 없는 에이전트를 연다 | 편집창이 읽기 전용이고 저장 단추가 없다 |
| 본문이 비어 있는 에이전트를 연다 | 「아직 성격을 쓰지 않았습니다」 가 보인다 |

`test/e2e/` 의 Hermes 대역에 `GET` 과 `PUT /api/profiles/{이름}/soul` 을 더한다.
`fake-hermes.ts` 의 `handleDashboard` 가 다른 대시보드 경로를 흉내 내는 방식을 그대로 따른다.
**대역이 받은 본문을 되읽을 수 있게 둔다.** 쓴 본문이 맞는지 시나리오가 보게 한다.

`test/e2e/scenarios/` 에 하나 더한다. 기존 시나리오 파일의 짜임을 따른다.

| 무엇 | 기대 |
| --- | --- |
| 성격을 저장한다 | 대역이 받은 본문이 저장한 것과 같다 |
| 저장한 뒤 다시 읽는다 | 저장한 본문이 온다 |
| 낡은 `baseHash` 로 저장한다 | 409 이고 대역의 본문이 바뀌지 않았다 |

## 검증

```bash
# cwd: 저장소 root
cd backend && ./gradlew test
cd web && pnpm typecheck && pnpm build
cd web && pnpm test:browser
node test/e2e/run.ts
scripts/check-public-safe.sh
```

아래가 아무것도 내지 않아야 한다. 인라인 스타일을 쓰지 않는다.

```bash
# cwd: 저장소 root
grep -rn 'style={{' web/src/
```

## Critical Files

| 파일 | 변경 |
|---|---|
| `web/src/app/agents/page.tsx` | 신규 |
| `web/src/app/agents/[code]/page.tsx` | 신규 |
| `web/src/app/api/agents/[code]/persona/route.ts` | 신규 |
| `web/src/components/agent/` | 신규 |
| `web/src/components/ui/site-nav.tsx` | 수정 |
| `web/src/app/admin/agents/agent-admin-panel.tsx` | 수정 |
| `test/browser/` | 추가 |
| `test/e2e/fake-hermes.ts` | 수정 |
| `test/e2e/scenarios/` | 추가 |

## 끝낸 뒤

`tasks/plan017-persona-ownership/index.json` 의 이 phase 를 `completed` 로,
plan 의 `status` 를 `completed` 로 바꾼다.

## 배포한 뒤 실제로 한 번 돌린다

**테스트가 모두 통과해도 운영에서 안 될 수 있다.**
Hermes 대역이 실제와 다른 응답을 내도록 쓰여 있으면 테스트는 통과한다.
실제로 그렇게 스트리밍이 통째로 안 된 채 배포된 적이 있다.

선행이 하나 있다. 비공개 저장소 `fos-home-infra` 가 소유한다.

- 대시보드 plugin 이 profile 이름마다 `GET` 과 `PUT /api/profiles/<이름>/soul` 을 토큰 경로로 여는 것

**배포 요청에 그것을 함께 적는다.** 없으면 성격 화면이 열리지도 저장되지도 않는다.

배포한 뒤 아래를 확인한다.

- `/agents/{code}` 를 열면 홈서버 파일에 있는 성격이 그대로 보인다
- 한 줄을 고쳐 저장하면 「저장되었습니다」 가 뜬다
- 그 에이전트와 대화를 한 번 왕복하면 바꾼 성격이 답에 드러난다
