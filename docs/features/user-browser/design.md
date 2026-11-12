# 사람이 도와야 하는 지점 기술 설계

스펙: [`spec.md`](spec.md). 이 설계는 PR1(REQ1~REQ6)을 구현할 수 있는 깊이로 쓰고, PR2 와 PR3 는 경계와 방향만 정한다.
뒤 PR 은 착수할 때 그 PR 의 절을 이 파일에서 고치고 독립 검토를 다시 받는다.

## 요구사항 연결

| ID | PR | 해결 방법 |
| --- | --- | --- |
| REQ1 | PR1 | 대화 화면의 옆 패널 자리(`SidePanelTarget`)에 새 종류 `handoff` 를 더하고, 결과물 패널과 같은 모양(넓으면 `aside`, 좁으면 `Sheet`)의 `HandoffPanel` 이 기존 `BrowserScreen` 을 그 카드의 탭으로 연다. 카드의 「내 브라우저 열기」 는 링크가 아니라 그 패널을 여는 단추다 |
| REQ2 | PR1 | 카드 목록 읽기를 훅 `useConversationHandoffs` 로 올리고, 대화마다 처음 읽은 목록의 번호를 기준으로 그 뒤에 처음 보인 번호를 새 카드로 보고 패널을 연다 |
| REQ3 | PR1 | 패널의 「다 했어요」 가 카드의 「다 했어요」 와 같은 함수(감추기, 정해 둔 글 보내기)를 부르고 패널을 닫는다 |
| REQ4 | PR1 | `BrowserScreen` 이 받은 `tabs` 사건을 패널에 넘기고, 순수 함수 `watchHandoffTab` 이 넘긴 탭의 주소 변화를 판정한다. 패널이 확인 줄을 그린다 |
| REQ5 | PR1 | `notificationHref` 가 `HANDOFF_REQUESTED` 에 `?handoff=open` 을 붙이고, 대화 화면이 그 값을 읽어 첫 목록의 가장 최근 넘기기로 패널을 연 뒤 주소에서 값을 지운다 |
| REQ6 | PR1 | 훅이 다시 읽은 목록에 패널의 넘기기가 없으면 패널을 닫는다. 카드의 「내 브라우저 열기」 는 패널을 그 카드로 바꾼다 |
| REQ7 | PR2 | 아래 「PR2 방향」 |
| REQ8 | PR2 | 아래 「PR2 방향」 |
| REQ9 | PR3 | 아래 「PR3 방향」 |
| REQ10 | PR3 | 아래 「PR3 방향」 |

## 책임과 경계

PR1 은 웹만 바꾼다. Control Plane, fos-ctx, 플러그인과 넘기기 계약([ADR-20261111 / connector-handoff](../../adr/ADR-20261111-connector-handoff.md) 의 결과 모양, `/internal/hermes/connector-handoff`, `GET /api/v1/chat/conversations/{id}/handoffs`)은 그대로다.

| 자리 | 책임 |
| --- | --- |
| `web/src/components/chat/use-conversation-handoffs.tsx`(새) | 대화의 열린 넘기기 읽기, 「다 했어요」 로 감춘 카드, 새 카드 판정, 알림 진입, 패널 열고 닫기. 지금 `HandoffList` 안의 읽기와 감추기 상태를 옮기고 카드 목록과 패널을 그려 돌려준다. `conversation-session-view.tsx` 가 파일 길이 상한(400줄) 바로 아래라 그 파일에는 훅 호출과 결과 두 자리만 둔다 |
| `web/src/components/chat/handoff-list.tsx` | 카드 그리기만. 「내 브라우저 열기」 는 `onOpen(handoff)` 단추 |
| `web/src/components/chat/handoff-panel.tsx`(새) | 옆 패널 모양, 머리(카드 제목, 「새 창으로 열기」, 닫기), `BrowserScreen`, 확인 줄과 「다 했어요」 |
| `web/src/components/browser/browser-screen.tsx`, `use-screen-stream.ts` | 선택 속성 둘을 더한다. `onTabs` 는 SSE 의 `tabs` 사건을 받을 때만 그 목록을 알린다(초기값 `[]` 과 다시 열 때 비우는 것은 알리지 않는다). `onReopen` 이 있으면 「다시 열기」 가 안에서 다시 잇지 않고 그것을 부른다. `/browser` 는 둘 다 넘기지 않아 동작이 그대로다 |
| `web/src/lib/handoff.ts` | 주소 변화 판정 `watchHandoffTab` 과 새 카드 판정 같은 순수 함수 |
| `web/src/lib/notification.ts` | `HANDOFF_REQUESTED` 의 갈 곳에 `?handoff=open` |
| `conversation-session-view.tsx` | 훅을 부르고 `HandoffList` 와 `HandoffPanel` 을 잇는다 |
| `docs/features/user-browser.md` | 「대화 위 화면」 절을 새로 쓰고, 「로그인 화면」 의 `/browser?tab=` 줄을 「새 창으로 열기」 로 고친다. `docs/features/naver-shopping.md` 의 「화면 넘기기」 와 `HandoffList` 주석의 「새 창」 도 고친다 |

화면 중계, 입력, 탭 옮기기는 `BrowserScreen` 과 `useScreenStream` 을 그대로 쓴다. 한 브라우저에 화면이 하나인 규칙과 `replaced` 안내([사용자 브라우저](../user-browser.md) 「로그인 화면」)도 그대로라 패널이 따로 만들지 않는다.

## 인터페이스와 데이터

### 옆 패널

`SidePanelTarget` 에 `{ kind: "handoff"; handoff: Handoff }` 를 더한다. 작업 과정, 결과물과 같은 상태 하나를 쓰므로 새 카드가 다른 패널을 갈아 쓰고(REQ2), 닫히면 자리가 빈다(DECISION21). Esc 로 패널을 닫는 지금 처리도 그대로 받는다. 넓은 화면(`aside`)에서는 화면에 글자를 넣는 칸이 Esc 를 원격 페이지로 보내며 `preventDefault` 하므로 `window` 의 처리기가 멈춘다. 좁은 화면의 `Sheet`(radix Dialog)는 Esc 를 먼저 받으므로 `SheetContent` 의 `onEscapeKeyDown` 에서, Esc 가 화면 글자 칸이나 크게 보기 중인 화면 안에서 났으면 `preventDefault` 해 시트를 닫지 않는다.

`BrowserScreen` 에 넘기는 `onOpen`, `onTabs`, `onReopen` 은 렌더마다 바뀌면 안 된다. 대화 화면은 답 조각마다 다시 그려지고 `useScreenStream` 의 연결 effect 가 `onOpen` 을 의존 목록에 두기 때문이다. `onOpen` 은 모듈 상수(패널은 브라우저 상태를 다시 읽을 것이 없다), `onTabs` 는 `useScreenStream` 안에서 ref 로 읽어 의존 목록에 넣지 않고, `onReopen` 은 단추의 누름 처리에만 쓰여 effect 에 닿지 않는다(rule-69).

`HandoffPanel` 은 `key` 를 넘기기 번호로 두어 넘기기가 바뀌거나 다시 열 때 `BrowserScreen` 과 주소 기준을 새로 만든다(스펙 DECISION19 의 「drawer 를 열 때 기준을 잡는다」). 다른 창에 밀린 뒤의 「다시 열기」 는 `onReopen` 으로 패널 안의 `BrowserScreen` 만 `key` 를 올려 새로 만든다. 그래서 다시 열어도 첫 `tabs` 사건에서 넘긴 탭으로 옮기고(REQ1 의 「그 카드의 탭」), 주소 기준은 패널이 들고 있어 그대로다.

| 동작 | 결과 |
| --- | --- |
| 머리의 닫기, 도구 줄의 닫기(`BrowserScreen` 의 `onClose`), Esc | 패널만 닫는다. 카드는 남는다 |
| 「새 창으로 열기」 | 지금의 `/browser?tab=` 를 새 창으로 열고 패널을 닫는다(DECISION18) |
| 「다 했어요」, 확인 줄의 「다 했어요, 이어서 해 주세요」 | 훅의 `done(handoff)` 를 부르고 패널을 닫는다 |
| 확인 줄의 「아직이에요」 | 확인 줄을 거두고 그때 주소를 기준으로 삼는다 |

### 넘기기 훅

```ts
useConversationHandoffs({ conversationId, refreshKey, panelTarget, setPanelTarget, submit(text) })
  → { list: ReactNode, panel: ReactNode | null }
```

- 읽기와 실패 처리는 지금 `HandoffList` 와 같다. 실패하면 앞 목록을 두고, 대화가 바뀌면 비운다
- `seen`: 대화마다 처음 성공한 읽기의 번호 집합. 그 뒤 읽기에서 `seen` 에 없는 번호가 보이면 가장 최근 것으로 패널을 한 번 열고 `seen` 에 더한다. 스펙 REQ2 의 「대화를 연 뒤에 생긴」 을 서버 시각과 브라우저 시계를 비교하지 않고 판정한다. 첫 읽기가 도는 사이 몇 밀리초 안에 생긴 넘기기와, 첫 읽기가 실패하고 다음 읽기가 성공하기 전에 생긴 넘기기만 「이미 있던 것」 으로 보이는 차이는 받아들인다(카드는 보이고 패널만 저절로 열리지 않는다)
- 알림 진입: 주소의 `handoff=open` 이 참이고 성공한 읽기가 있을 때 처리한다. 이미 성공한 읽기가 있으면 바로, 없으면 다음 성공한 읽기에서 목록의 첫 줄(서버가 최신 것부터 준다)로 패널을 열고, 있든 없든 주소에서 그 값만 지운다(`router.replace`, 다른 검색어는 둔다). 읽기가 계속 실패해도 처리할 때까지 값이 남을 뿐 새로 고침마다 한 번 시도하는 것은 받아들인다
- `done(handoff)`: 감춘 목록에 더하고 `onDone(HANDOFF_TEXT[need].done)`. 카드와 패널이 같은 함수를 쓴다
- 다시 읽은 목록에 패널의 넘기기가 없으면 패널을 닫는다(REQ6). 감춘 넘기기도 없는 것으로 본다. 다른 패널을 닫지 않게 함수형 갱신으로 넘기기 패널일 때만 닫는다(`setPanelTarget(prev => prev?.kind === "handoff" && !open.has(prev.handoff.handoffId) ? null : prev)`). 같은 읽기 결과에서 새 카드 자동 열기를 먼저 정하고 닫기를 그 뒤에 본다

다시 읽는 때는 지금처럼 `approvalRefresh` 다. 넘기기를 적을 때와 닫을 때 Control Plane 이 대화 SSE 로 `approval` 사건을 보내고(`ConnectorHandoffs` 의 `afterCommit` → `ConnectorActionListener`), 웹은 이 사건을 보류하지 않고 이 창이 보낸 turn 이 도는 중에도 다시 읽는다. 끊긴 동안 생긴 것은 재연결이 올린다. 그래서 패널은 turn 이 끝날 때가 아니라 넘기기가 적힌 순간에 열린다. 새 다시 읽기는 더하지 않는다.

### 주소 변화 판정

```ts
watchHandoffTab(state, tabs) → { state, changed: boolean }
state = { phase: "waiting" } | { phase: "watching", base: string } | { phase: "off" }
```

| 지금 | 받은 탭 목록 | 다음 |
| --- | --- | --- |
| `waiting` | 넘긴 탭이 있다 | `watching`, 기준은 그 탭 주소에서 `#` 뒤를 뺀 것 |
| `waiting` | 넘긴 탭이 없다 | `off`. 묻지 않는다(REQ4 의 「처음부터 없으면」) |
| `watching` | 넘긴 탭의 주소(`#` 뒤 제외)가 기준과 다르다 | `changed` |
| `watching` | 넘긴 탭이 없다 | `changed` |
| `off` | 무엇이든 | 그대로 |

「아직이에요」 는 지금 탭 주소로 기준을 바꾸고, 넘긴 탭이 이미 없으면 `off` 로 둔다. 판정은 `/browser` 화면과 상관없이 패널 안에서만 쓴다.

### 알림 갈 곳

`notificationHref` 에서 `kind` 가 `HANDOFF_REQUESTED` 이고 갈 곳이 대화면 `/chat/<id>?handoff=open` 이다. 다른 종류는 그대로다. 알림 목록은 `/notifications` 화면에만 있어 대화 화면으로는 늘 새로 들어온다. 훅은 `useSearchParams` 로 받으므로 같은 대화 안에서 검색어만 바뀌어도 처리한다.

### 데이터

저장하는 것이 없다. 새 API, 칸, 마이그레이션이 없다. 패널 상태와 `seen`, 감춘 목록은 브라우저 메모리에만 있다.

## 실패와 일관성

| 경우 | 처리 |
| --- | --- |
| 넘기기 목록을 읽지 못한다 | 지금처럼 앞 목록을 둔다. 새 카드 판정과 알림 진입은 성공한 읽기에서만 한다 |
| 화면을 열지 못한다(브라우저 켜기 실패, 기능 꺼짐) | `BrowserScreen` 의 지금 안내가 패널 안에 보인다. 「새 창으로 열기」 와 「다 했어요」 는 남는다 |
| 다른 창이 화면을 가져간다 | 지금 `replaced` 안내와 「다시 열기」 가 패널 안에 보인다. 「다시 열기」 는 넘긴 탭으로 다시 옮긴다. 창마다 자동으로 열면 마지막에 연 창이 화면을 가진다(스펙 DECISION18) |
| 「다 했어요」 를 보냈는데 다른 turn 이 돈다 | 지금 카드와 같다. `submit` 이 대기 메시지로 넣는다 |
| 보내기 전 패널을 닫았다 | 카드가 남아 다시 연다 |

배포 순서는 없다. 웹만 바뀌고 Control Plane 응답을 새로 읽지 않는다.

## 검증 전략

| ID | 검증 |
| --- | --- |
| REQ1 | 브라우저 테스트(`web/test/browser/handoff-card.spec.ts`): 카드의 「내 브라우저 열기」 가 새 창 없이 패널을 열고 화면 열기 요청에 그 탭이 쓰이는지(첫 `tabs` 사건 뒤 입력 `tab`), 넓은 폭에서 입력창이 함께 보이는지, 좁은 폭에서 전체 폭 시트이고 화면 글자 칸의 Esc 가 시트를 닫지 않고 원격으로 가는지, 답 조각이 와도 화면 열기 요청이 한 번뿐인지, 닫으면 카드가 남는지, 「새 창으로 열기」 링크. 화면 중계는 `user-browser-screen.spec.ts` 의 가짜 SSE 를 공용 도우미로 옮겨 함께 쓴다 |
| REQ2 | 같은 파일: 처음 연 대화의 기존 카드는 패널을 열지 않고, 다시 읽은 목록에 새 번호가 생기면 패널이 열린다. 단위 테스트로 새 카드 판정 함수 |
| REQ3 | 같은 파일: 패널의 「다 했어요」 가 정해 둔 글을 보내고 패널을 닫는다 |
| REQ4 | 단위 테스트(`web/test/unit/`): `watchHandoffTab` 의 표 전부와 「아직이에요」. `onTabs` 가 SSE `tabs` 사건에서만 불려 빈 초기값으로 `off` 가 되지 않는 것은 브라우저 테스트로 본다. 브라우저 테스트: 넘긴 탭의 주소가 바뀐 `tabs` 사건 뒤 확인 줄, 「아직이에요」 뒤 거둠 |
| REQ5 | 단위 테스트: `notificationHref` 의 `?handoff=open`. 브라우저 테스트: `?handoff=open` 으로 열면 가장 최근 넘기기의 패널이 열리고 주소에서 값이 지워진다. 열린 넘기기가 없으면 대화만 열고 값을 지운다 |
| REQ6 | 브라우저 테스트: 열린 패널의 넘기기가 다시 읽은 목록에서 빠지면 패널이 닫힌다. 카드가 둘일 때 다른 카드의 「내 브라우저 열기」 가 패널을 바꾼다 |

실제 Chrome 과 네이버 화면으로 하는 왕복은 운영 확인으로 남긴다. Hermes 와 주고받는 것을 바꾸지 않으므로 배포 뒤 Hermes 왕복은 필요 없다.

## 결정

| ID | 결정 | 답 | 누가 | 근거 |
| --- | --- | --- | --- | --- |
| DECISION1 | 패널을 둘 자리 | 대화 화면의 옆 패널 상태(`SidePanelTarget`)에 종류 하나를 더한다 | 작성자 | 스펙 DECISION1 이 「작업 과정·결과물 옆 패널과 같은 자리와 규칙」 이다. 상태를 하나로 두면 갈아 쓰기(REQ2)와 Esc 닫기를 새로 만들지 않는다 |
| DECISION2 | 화면 부품 | `BrowserScreen` 을 그대로 쓰고 `onTabs` 만 더한다 | 작성자 | 스펙 DECISION20 이 `/browser` 와 같은 도구 줄이다. 입력 변환, 확인 창, 다시 열기를 두 벌로 두지 않는다 |
| DECISION3 | 새 카드 판정 | 처음 성공한 읽기의 번호 집합에 없는 번호 | 작성자 | 서버 시각(`createdAt`)과 브라우저 시계를 비교하면 시계가 어긋난 기기에서 열리지 않거나 이미 있던 것이 열린다. 번호 집합은 같은 뜻을 시계 없이 판정한다 |
| DECISION4 | 알림 진입의 표시 | 주소 검색어 `?handoff=open` | 작성자 | 알림 응답에 넘기기 번호가 없고(스펙 DECISION14) Control Plane 을 바꾸지 않는다. 검색어는 같은 대화에서 다시 눌러도 바뀌어 받을 수 있다 |
| DECISION5 | ADR | 새 ADR [ADR-20261112 / handoff-drawer](../../../web/docs/adr/ADR-20261112-handoff-drawer.md) 를 웹 모듈에 두고, [ADR-20261111 / connector-handoff](../../adr/ADR-20261111-connector-handoff.md) 의 카드 행 가운데 「새 창을 열고」 를 대체된 부분으로 적는다 | 작성자 | 받아들인 결정의 일부(카드가 새 창을 연다)를 바꾸므로 덮어쓰지 않고 대체로 남긴다. 결정을 지키는 코드가 웹에만 있다 |
| DECISION6 | 밀린 뒤 「다시 열기」 | 패널에서는 `onReopen` 으로 `BrowserScreen` 을 새로 만들어 넘긴 탭으로 다시 옮긴다. `/browser` 는 지금처럼 지금 탭에 머문다 | 작성자 | 스펙 DECISION18 로 창마다 패널이 열려 밀리는 일이 흔하다. 다시 열었을 때 다른 탭이 보이면 REQ1 의 「그 카드의 탭」 과 어긋난다 |

## 결정 기록 연결

| 결정 | 종류 | ADR 또는 필요 없음의 이유 | 현재 규칙의 원본 |
| --- | --- | --- | --- |
| 넘기기 카드가 대화 위 패널을 연다 | architecture | [ADR-20261112 / handoff-drawer](../../../web/docs/adr/ADR-20261112-handoff-drawer.md). [ADR-20261111 / connector-handoff](../../adr/ADR-20261111-connector-handoff.md) 의 카드 행 일부를 대체한다 | 이 설계의 「인터페이스와 데이터」, `docs/features/user-browser.md` 의 「대화 위 화면」 |

## PR2 방향

- 어휘: Control Plane `HandoffNeed` 와 `ConnectorHandoffs.TITLES`, 웹 `handoff.ts` 의 `HANDOFF_NEEDS`, `HANDOFF_TEXT` 에 `BOT_CHECK`, `UNEXPECTED_PAGE` 를 더한다. 서명할 글과 결과 모양은 그대로이고 `need` 값만 늘어난다. 웹이 먼저 배포돼도 모르는 값은 서버가 받지 않으므로 순서 제약은 「Control Plane 과 웹을 함께」 뿐이다
- 플러그인: 네이버 쇼핑의 `PAGE_CHANGED` 가 탭을 닫지 않고 `unexpected_page` 넘기기를 낸다. 코레일 KTX 와 네이버 블로그는 쇼핑의 `ToolError(handoff)` 와 같은 결과 모양을 낸다. 서비스 이름은 각 플러그인에만 둔다(rule-65)
- 함께 도는 「넘기기를 오류가 아닌 결과로」 작업이 결과 모양을 바꾸면 PR2 는 그 모양을 따른다

## PR3 방향

- 패널에 「할 일」 과 「내 브라우저」 두 탭을 둔다. 「할 일」 은 `ApprovalList` 의 카드 부품과 묻는 카드를 그대로 쓰고, 입력창 위에는 「할 일 N건」 한 줄만 남긴다
- 승인 영역의 높이 상한(화면 40%)과 묶음 승인([ADR-087](../../../web/docs/adr/ADR-087-같은-도구의-승인-줄은-묶음으로-보이고-상시-허락을-줄-수-있는-줄만-한꺼번에-승인한다.md))은 패널 안의 규칙으로 다시 정한다. 착수할 때 이 절을 고친다
