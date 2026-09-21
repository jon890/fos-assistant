# Phase 03. 화면에서 자기 에이전트의 성격을 쓰고 고친다

**Execution profile**: standard

## 목표

가족 구성원이 화면에서 자기 에이전트의 성격을 쓰고 고친다.
그 사이에 홈서버를 만지지 않는다.

**범위 외**:
화면에서 에이전트를 새로 만드는 것은 이 plan 이 하지 않는다.
에이전트를 등록하는 경로는 지금처럼 관리 화면에 있다.
성격 말고 모델과 공개 범위를 고치는 것도 지금 자리에 그대로 둔다.

## 컨텍스트

phase-01 이 표와 읽고 쓰는 경로를, phase-02 가 Hermes 에 미는 경로를 만들었다.
이 phase 가 화면을 붙인다.

지금 화면에는 `/admin/agents` 밖에 없고 그것은 `ADMIN` 만 연다.
**성격은 `MEMBER` 도 고쳐야 하므로 관리 화면에 둘 수 없다.**

**근거 문서**:
`docs/code-architecture.md` 의 「페르소나」 절과 「web 화면 구조」 절,
`docs/flow.md` 의 「페르소나를 고칠 때」 절,
`docs/adr/ADR-019-페르소나는-control-plane-이-갖고-hermes-에-민다.md`.

## 의도 메모

- 성격 편집을 `/admin/agents` 에 두는 안을 버렸다. 그 화면은 `ADMIN` 만 연다.
- 대화 화면 안에서 고치는 안을 버렸다.
  대화 도중에 성격이 바뀌면 그 대화의 앞뒤가 다른 성격으로 답한 글이 된다.
  화면을 나눠 고치러 가는 걸음을 남긴다.
- 옛 판을 지우거나 되살리는 단추를 두지 않았다.
  되돌리기는 옛 본문을 편집창에 불러와 새 판으로 저장하는 것이다.
- 미반영일 때 대화를 막지 않는다. 표시와 단추만 둔다.

## 작업 항목

### 1. 목록 응답에 성격 상태를 담는다

`agent/presentation/AgentDtos.java` 의 `AgentView` 에 칸 하나를 더한다.

| 칸 | 값 |
| --- | --- |
| `personaState` | `NONE` 또는 `SYNCED` 또는 `PENDING` |

| 값 | 언제 |
| --- | --- |
| `NONE` | 판이 하나도 없다. 홈서버 파일의 성격으로 돌고 있다 |
| `SYNCED` | 지금 판과 반영된 판이 같다 |
| `PENDING` | 둘이 다르다. 아직 반영되지 않았다 |

`AgentController.readable` 이 그 값을 채운다.
**목록 한 줄마다 질의를 더하지 않는다.** 볼 수 있는 에이전트의 번호를 모아 한 번에 읽는다.
`AgentPersonaRepository` 에 번호 목록을 받는 질의 둘을 더한다.
에이전트마다 지금 판 하나와 반영된 판 하나를 내는 것이고, 본문은 고르지 않는다.
phase-01 이 만든 한 건짜리 메서드는 그대로 둔다. 상세 화면이 그것을 쓴다.
`usage` 의 축별 합계가 에이전트 이름을 한 번에 읽는 방식을 따른다.
그 결합을 허용하는 근거는 `docs/code-architecture.md` 의
「합계 질의는 다른 도메인의 엔티티를 조인해도 된다」 가 갖는다.

### 2. 서버 라우트

`web/src/app/api/agents/` 아래에 둔다.
`web/src/app/api/admin/agents/` 가 Control Plane 을 부르는 방식을 그대로 따른다.
브라우저가 Control Plane 을 직접 부르지 않는다. `web/AGENTS.md` 가 그렇게 정한다.

| 파일 | 부르는 것 |
| --- | --- |
| `route.ts` | `GET /api/v1/agents` |
| `[code]/persona/route.ts` | `GET` 과 `PUT /api/v1/agents/{code}/persona` |
| `[code]/persona/sync/route.ts` | `POST /api/v1/agents/{code}/persona/sync` |
| `[code]/persona/revisions/route.ts` | `GET /api/v1/agents/{code}/persona/revisions` |
| `[code]/persona/revisions/[revision]/route.ts` | `GET` 한 판 |

### 3. `/agents` 화면

내가 쓸 수 있는 에이전트 목록이다.
`web/src/app/agents/page.tsx` 와 `web/src/components/agent/` 에 부품을 둔다.

한 줄에 이름과 모델과 성격 상태를 보인다.

| 상태 | 무엇을 보이나 |
| --- | --- |
| `NONE` | 「성격을 아직 쓰지 않았습니다」 |
| `SYNCED` | 아무 표시도 하지 않는다 |
| `PENDING` | 「아직 반영되지 않음」 |

빈 상태는 `components/ui/empty-state.tsx` 를 쓴다.
쓸 수 있는 에이전트가 없으면 「쓸 수 있는 에이전트가 없습니다」 를 보인다.

### 4. `/agents/{code}` 화면

그 에이전트의 성격을 보고 고치는 곳이다.

- 본문 편집창. 아래에 「N / 8000자」 를 보인다. 상한은 응답의 `maxChars` 가 준다
- 저장 단추. 고칠 수 없는 사람에게는 그리지 않고 편집창을 읽기 전용으로 둔다
- 판 목록. 판 번호와 쓴 사람과 시각을 줄로 보인다
- 판 하나를 고르면 그 본문을 편집창에 넣는다. **저장을 눌러야 새 판이 된다**

상태를 넷 보인다.

| 상태 | 무엇을 보이나 |
| --- | --- |
| 판이 없다 | 빈 편집창과 「홈서버 파일의 성격으로 돌고 있습니다」 |
| 저장 중 | 단추를 잠그고 도는 표시 |
| 반영됨 | 「반영되었습니다」 |
| 미반영 | 「저장했지만 아직 반영되지 않았습니다」 와 「다시 반영」 단추 |

오류를 셋 갈라 보인다.

| 오류 | 문장 |
| --- | --- |
| `PERSONA_STALE` | 그 사이 다른 사람이 고쳤다고 알리고 본문을 다시 읽어 보인다 |
| 길이 초과 | 몇 자를 줄여야 하는지 보인다 |
| 반영 실패 | `syncFailureCode` 에 맞는 문장. 글은 저장됐다는 것을 함께 적는다 |

색과 간격은 테마 토큰만 쓴다. `web/AGENTS.md` 가 그것을 정한다.

### 5. 들어가는 길

`web/src/components/ui/site-nav.tsx` 의 `LINKS` 에 `{ href: "/agents", label: "에이전트" }` 를 더한다.
`ADMIN` 에게만 붙는 「에이전트 관리」 는 그대로 둔다.

`web/src/app/admin/agents` 의 각 줄에서 그 에이전트의 `/agents/{code}` 로 가는 링크를 둔다.

### 6. 이 phase 를 검증하는 테스트

`test/browser` 에 화면 검사를 더한다.
기존 검사 파일의 짜임을 따르고 `mobile` 과 `desktop` 두 폭에서 돈다.

| 무엇 | 기대 |
| --- | --- |
| 자기 에이전트의 성격 화면을 연다 | 편집창과 저장 단추가 보인다 |
| 성격을 저장한다 | 「반영되었습니다」 가 보이고 판 목록이 한 줄 는다 |
| 8000자를 넘겨 적는다 | 저장 단추가 잠기고 남은 글자 수가 음수로 보인다 |
| 고칠 수 없는 에이전트를 연다 | 편집창이 읽기 전용이고 저장 단추가 없다 |
| 판이 없는 에이전트를 연다 | 「홈서버 파일의 성격으로 돌고 있습니다」 가 보인다 |
| 옛 판을 고른다 | 편집창에 그 본문이 들어오고 판 목록은 그대로다 |

`test/e2e/scenarios/` 에 하나 더한다.
성격을 저장하고, Hermes 대역이 받은 본문이 저장한 것과 같은지 보고,
그 뒤 실행 하나를 만들어 그 실행 줄이 그 판을 가리키는 데까지 간다.
기존 시나리오 파일의 짜임을 따른다.

`backend/src/test/java/com/bifos/assistant/agent/AgentListPersonaStateTest.java`

| 무엇 | 기대 |
| --- | --- |
| 판이 없는 에이전트 | `personaState` 가 `NONE` |
| 반영까지 끝난 에이전트 | `SYNCED` |
| 저장만 되고 반영이 안 된 에이전트 | `PENDING` |
| 에이전트 셋이 있는 목록 | 질의 수가 에이전트 수만큼 늘지 않는다 |

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
| `backend/src/main/java/com/bifos/assistant/agent/presentation/AgentDtos.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/agent/presentation/AgentController.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/agent/application/AgentService.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/agent/infra/AgentPersonaRepository.java` | 수정 |
| `web/src/app/agents/page.tsx` | 신규 |
| `web/src/app/agents/[code]/page.tsx` | 신규 |
| `web/src/app/api/agents/route.ts` | 신규 |
| `web/src/app/api/agents/[code]/persona/route.ts` | 신규 |
| `web/src/app/api/agents/[code]/persona/sync/route.ts` | 신규 |
| `web/src/app/api/agents/[code]/persona/revisions/route.ts` | 신규 |
| `web/src/app/api/agents/[code]/persona/revisions/[revision]/route.ts` | 신규 |
| `web/src/components/agent/` | 신규 |
| `web/src/components/ui/site-nav.tsx` | 수정 |
| `web/src/app/admin/agents/agent-admin-panel.tsx` | 수정 |
| `backend/src/test/java/com/bifos/assistant/agent/AgentListPersonaStateTest.java` | 신규 |
| `test/browser/` | 추가 |
| `test/e2e/scenarios/` | 추가 |

## 끝낸 뒤

`tasks/plan017-persona-ownership/index.json` 의 이 phase 를 `completed` 로,
plan 의 `status` 를 `completed` 로 바꾼다.

## 배포한 뒤 실제로 한 번 돌린다

**테스트가 모두 통과해도 운영에서 안 될 수 있다.**
Hermes 대역이 실제와 다른 응답을 내도록 쓰여 있으면 테스트는 통과한다.
실제로 그렇게 스트리밍이 통째로 안 된 채 배포된 적이 있다.

배포한 뒤 아래를 확인한다.

- 성격을 하나 저장하면 화면에 「반영되었습니다」 가 뜬다
- 그 에이전트와 대화를 한 번 왕복하면 바꾼 성격이 답에 드러난다
- 그 실행을 열면 실행 줄이 그 판을 가리킨다

선행이 하나 있다. 비공개 저장소 `fos-home-infra` 가 소유한다.

- 대시보드 plugin 이 `/api/profiles/<이름>/soul` 을 그 이름마다 토큰 경로로 열어 두는 것

**배포 요청에 그것을 함께 적는다.** 없으면 저장은 되고 반영만 계속 실패한다.

## 지금 도는 profile 의 이관

홈서버에서 도는 profile 의 성격은 아직 파일에만 있다.
**옮기는 것은 구현자가 한 번에 한다. 주인이 화면에서 붙여 넣는 것이 아니다.**

대시보드에 `SOUL.md` 를 읽는 경로가 없다. 그래서 Control Plane 이 스스로 가져올 수 없다.
홈서버에서 파일을 읽어 Control Plane 에 넣는 방향으로만 된다.

**먼저 되어 있어야 하는 것**

| 무엇 | 어디서 |
| --- | --- |
| 성격을 받는 경로 | phase-01 의 `PUT /api/v1/agents/{code}/persona` |
| 받은 것을 Hermes 에 미는 경로 | phase-02 |
| 이 phase 까지 배포된 상태 | 넣은 직후 화면에서 결과를 본다 |

**이관은 배포 뒤에 한다.** 경로가 없는 상태에서 넣을 곳이 없다.

**무엇을 어디로 넣나**

에이전트 목록에 등록된 profile 을 모두 옮긴다. 한 profile 이 한 번이고 지금은 다섯이다.

1. 홈서버에서 그 profile 의 `SOUL.md` 본문을 읽는다.
   읽는 방법은 비공개 저장소 `fos-home-infra` 가 갖는다
2. 그 profile 을 가리키는 에이전트의 `code` 로 `PUT /api/v1/agents/{code}/persona` 에 넣는다.
   첫 판이므로 `baseRevision` 을 주지 않는다
3. 부르는 쪽은 관리자 이메일로 서명한 짧은 수명 토큰을 쓴다.
   `ADMIN` 은 모든 에이전트의 성격을 고칠 수 있다.
   서명 비밀값과 Control Plane 의 주소는 `fos-home-infra` 가 갖는다
4. 넣은 그 자리에서 같은 본문이 Hermes 로 다시 밀린다. 파일의 내용은 달라지지 않는다

지킬 것이 둘이다.

- **홈서버 파일에 있는 값을 그대로 옮긴다.** 기본 판이나 다른 사본과 맞추려 하지 않는다.
  실제로 그 profile 이 읽고 있는 것은 그 파일이다
- **본문이 기본 판과 같아 보여도 옮긴다.** 판이 없는 상태로 남기지 않는다.
  판이 없으면 화면의 편집창이 비어서, 그 사람이 자기 성격을 고치려 할 때
  무엇에서 출발하는지 알 수 없다.
  기본 판과 같다는 것도 지금 시점의 우연이고 그 기본 판은 우리가 갖지 않는다

**스크립트를 만들지 않는다**

**`fos-home-infra` 에 이관용 코드를 남기지 않는다.**
한 번 쓰고 마는 것이 상시 파일로 남으면 그 뒤로 쓰이지 않는데 검사와 문서가 계속 따라붙는다.
파일을 읽어 경로 하나를 그 수만큼 부르는 일이고, 구현자가 그 자리에서 끝낸다.

**그 저장소에 상시로 남는 것은 token route 를 등록하는 plugin 하나다.**

**넣기 전에 볼 것**

본문이 상한인 8000자를 넘는 profile 이 있으면 그 호출이 거절된다.
**넣기 전에 옮길 본문의 글자 수를 세어 본다.** 넘는 것이 있으면 옮기지 말고 알린다.
본문을 잘라서 넣지 않는다. 상한을 다시 정할 일이지 글을 버릴 일이 아니다.

**넣은 뒤 확인할 것**

- 각 에이전트가 `/agents` 목록에서 `SYNCED` 로 보인다
- `/agents/{code}` 의 편집창에 홈서버 파일과 같은 본문이 들어 있다
- 그중 하나와 대화를 한 번 왕복하면 그 성격으로 답한다

**끝난 뒤 그 저장소에 알릴 것**

옮길 profile 이 하나도 남지 않으면 `fos-home-infra` 에 알린다.
**성격 사본을 담은 디렉터리를 통째로 처분한다.** 파일을 하나씩 고르지 않는다.
이미 쓰지 않는 profile 의 사본도 그 안에 함께 있고, 옮기고 나면 어느 것도 읽히지 않는다.
`default-SOUL.md` 는 그 처분에서 뺀다. 그것은 사람이 쓴 성격이 아니라
profile 을 만들 때 넣는 기본 본문이고, 계속 그 저장소가 갖는다.
근거는 `docs/adr/ADR-019-페르소나는-control-plane-이-갖고-hermes-에-민다.md` 의
「기본 본문은 옮기지 않는다」 절에 있다.
