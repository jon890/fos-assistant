# 사람이 도와야 하는 지점 기술 설계

스펙: [`spec.md`](spec.md). 이 설계는 PR1(REQ1~REQ6), PR2(REQ7, REQ8), PR3(REQ9, REQ10)를 구현할 수 있는 깊이로 쓴다.
PR3 는 PR1 의 옆 패널을 「할 일」 과 「내 브라우저」 두 탭으로 바꾼다. 아래 PR1 절의 「패널」 은 PR3 뒤로는 「내 브라우저」 탭을 가리킨다.

## 요구사항 연결

| ID | PR | 해결 방법 |
| --- | --- | --- |
| REQ1 | PR1 | 대화 화면의 옆 패널 자리(`SidePanelTarget`)에 새 종류 `handoff` 를 더하고, 결과물 패널과 같은 모양(넓으면 `aside`, 좁으면 `Sheet`)의 `HandoffPanel` 이 기존 `BrowserScreen` 을 그 카드의 탭으로 연다. 카드의 「내 브라우저 열기」 는 링크가 아니라 그 패널을 여는 단추다 |
| REQ2 | PR1 | 카드 목록 읽기를 훅 `useConversationHandoffs` 로 올리고, 대화마다 처음 읽은 목록의 번호를 기준으로 그 뒤에 처음 보인 번호를 새 카드로 보고 패널을 연다 |
| REQ3 | PR1 | 패널의 「다 했어요」 가 카드의 「다 했어요」 와 같은 함수(감추기, 정해 둔 글 보내기)를 부르고 패널을 닫는다 |
| REQ4 | PR1 | `BrowserScreen` 이 받은 `tabs` 사건을 패널에 넘기고, 순수 함수 `watchHandoffTab` 이 넘긴 탭의 주소 변화를 판정한다. 패널이 확인 줄을 그린다 |
| REQ5 | PR1 | `notificationHref` 가 `HANDOFF_REQUESTED` 에 `?handoff=open` 을 붙이고, 대화 화면이 그 값을 읽어 첫 목록의 가장 최근 넘기기로 패널을 연 뒤 주소에서 값을 지운다 |
| REQ6 | PR1 | 훅이 다시 읽은 목록에 패널의 넘기기가 없으면 패널을 닫는다. 카드의 「내 브라우저 열기」 는 패널을 그 카드로 바꾼다 |
| REQ7 | PR2 | Control Plane, fos-ctx, 웹의 어휘에 `bot_check`, `unexpected_page` 를 더하고, 네이버 쇼핑의 `PAGE_CHANGED` 가 탭을 남긴 `unexpected_page` 넘기기를 낸다. 아래 「PR2 넘기기 어휘 확장과 KTX·블로그 넘기기」 |
| REQ8 | PR2 | 코레일 KTX 와 네이버 블로그가 쇼핑과 같은 넘기기 결과와 남긴 탭 규칙을 갖는다. 같은 절 |
| REQ9 | PR3 | 아래 「PR3 할 일 탭」 |
| REQ10 | PR3 | 같은 절 |

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
| DECISION2 | 화면 부품 | `BrowserScreen` 을 그대로 쓰고 선택 속성 `onTabs` 와 `onReopen` 만 더한다 | 작성자 | 스펙 DECISION20 이 `/browser` 와 같은 도구 줄이다. 입력 변환, 확인 창, 다시 열기를 두 벌로 두지 않는다 |
| DECISION3 | 새 카드 판정 | 처음 성공한 읽기의 번호 집합에 없는 번호 | 작성자 | 서버 시각(`createdAt`)과 브라우저 시계를 비교하면 시계가 어긋난 기기에서 열리지 않거나 이미 있던 것이 열린다. 번호 집합은 같은 뜻을 시계 없이 판정한다 |
| DECISION4 | 알림 진입의 표시 | 주소 검색어 `?handoff=open` | 작성자 | 알림 응답에 넘기기 번호가 없고(스펙 DECISION14) Control Plane 을 바꾸지 않는다. 검색어는 같은 대화에서 다시 눌러도 바뀌어 받을 수 있다 |
| DECISION5 | ADR | 새 ADR [ADR-20261112 / handoff-drawer](../../../web/docs/adr/ADR-20261112-handoff-drawer.md) 를 웹 모듈에 두고, [ADR-20261111 / connector-handoff](../../adr/ADR-20261111-connector-handoff.md) 의 카드 행 가운데 「새 창을 열고」 를 대체된 부분으로 적는다 | 작성자 | 받아들인 결정의 일부(카드가 새 창을 연다)를 바꾸므로 덮어쓰지 않고 대체로 남긴다. 결정을 지키는 코드가 웹에만 있다 |
| DECISION6 | 밀린 뒤 「다시 열기」 | 패널에서는 `onReopen` 으로 `BrowserScreen` 을 새로 만들어 넘긴 탭으로 다시 옮긴다. `/browser` 는 지금처럼 지금 탭에 머문다 | 작성자 | 스펙 DECISION18 로 창마다 패널이 열려 밀리는 일이 흔하다. 다시 열었을 때 다른 탭이 보이면 REQ1 의 「그 카드의 탭」 과 어긋난다 |

## 결정 기록 연결

| 결정 | 종류 | ADR 또는 필요 없음의 이유 | 현재 규칙의 원본 |
| --- | --- | --- | --- |
| 넘기기 카드가 대화 위 패널을 연다 | architecture | [ADR-20261112 / handoff-drawer](../../../web/docs/adr/ADR-20261112-handoff-drawer.md). [ADR-20261111 / connector-handoff](../../adr/ADR-20261111-connector-handoff.md) 의 카드 행 일부를 대체한다 | 이 설계의 「인터페이스와 데이터」, `docs/features/user-browser.md` 의 「대화 위 화면」 |

## PR2 넘기기 어휘 확장과 KTX·블로그 넘기기

결과 모양, 서명할 글, 알리는 주소, 카드와 닫기 규칙([ADR-20261111 / connector-handoff](../../adr/ADR-20261111-connector-handoff.md))은 그대로다. `need` 값 둘이 늘고, 플러그인 셋이 그 값을 낸다.

### 어휘

| `need` | Control Plane, 웹 | 카드 제목 | 「다 했어요」 가 보내는 글 |
| --- | --- | --- | --- |
| `bot_check` | `BOT_CHECK` | 「내 브라우저」에서 자동 접속 확인을 풀어 주세요 | 「내 브라우저」에서 자동 접속 확인을 마쳤어요. 멈춘 단계부터 이어서 해 주세요. |
| `unexpected_page` | `UNEXPECTED_PAGE` | 「내 브라우저」에서 화면을 확인해 주세요 | 「내 브라우저」에서 화면을 확인했어요. 멈춘 단계부터 이어서 해 주세요. |

`bot_check` 는 사이트가 사람인지 의심해 막은 화면(KTX 의 매크로 탐지 안내)이고, 보안문자 그림을 푸는 `captcha` 와 나눈다. `unexpected_page` 는 플러그인이 기대한 자리나 칸을 찾지 못했거나 최종 주소가 기대한 사이트가 아닌 화면이다.

| 자리 | 바꾸는 것 |
| --- | --- |
| Control Plane `HandoffNeed` | `BOT_CHECK("bot_check")`, `UNEXPECTED_PAGE("unexpected_page")`. `connector_action.handoff_need`(`VARCHAR(20)`)에 그대로 들어가 마이그레이션이 없다 |
| Control Plane `HandoffNeed.title()` | 위 카드 제목. 알림 제목도 같다 |
| fos-ctx `handoff.py` 의 `NEEDS` | 두 낱말을 더한다. 버전 1.8.0 |
| 웹 `handoff.ts` | `HANDOFF_NEEDS`, `HANDOFF_TEXT` 에 두 값. 카드, 패널, 「다 했어요」 는 이 표를 읽으므로 화면 코드는 바뀌지 않는다 |

모르는 낱말은 지금처럼 fos-ctx 가 보내지 않고 Control Plane 이 받지 않으며 웹이 버린다.

### 플러그인이 넘기는 때

세 플러그인 모두 넘기기는 `isError` 없는 결과 `{"status": "HUMAN_ACTION_REQUIRED", "message", "handoff": {"code", "need", "tab"}}` 이고, 남긴 탭은 MCP 서버 프로세스 메모리에 두었다가 다음에 탭을 여는 도구가 새 탭을 열기 전에 닫는다(쇼핑과 같은 규칙). `code` 는 지금 오류 코드를 그대로 쓰므로 `assistant.json` 의 `errors` 표는 바뀌지 않는다. 결과를 만드는 코드는 플러그인마다 둔다(rule-65).

| 플러그인 | 화면 | `code` | `need` |
| --- | --- | --- | --- |
| 네이버 쇼핑 | 로그인, 보안 확인, 기기 인증(지금과 같다) | `NAVER_SHOPPING_LOGIN_REQUIRED` 등 | `login`, `captcha`, `device_check` |
| 네이버 쇼핑 | 최종 주소가 네이버가 아니다, 상태 JSON 의 필수 칸이 없다 | `NAVER_SHOPPING_PAGE_CHANGED` | `unexpected_page` |
| 코레일 KTX | 로그인 주소, 매크로 탐지 안내, 보안 문자 | `KORAIL_LOGIN_REQUIRED`, `KORAIL_BOT_CHECK`, `KORAIL_CAPTCHA` | `login`, `bot_check`, `captcha` |
| 코레일 KTX | 필수 단계의 자리가 없다, 목록 화면으로 가지 않는다, 목록 칸이 비거나 모양이 틀리다 | `KORAIL_PAGE_CHANGED` | `unexpected_page` |
| 네이버 블로그 | 읽기 도구의 글쓰기 탭이 로그인 화면(캡차, 기기 인증 포함)으로 넘어갔다 | `NAVER_BLOG_LOGIN_REQUIRED` | `login` |

넘기지 않는 것:

- 탭을 열지 않는 확인 도구(`check_shopping_login`, `check_korail_login`, `session_status`)의 로그인 없음. 사람이 볼 탭이 없다. 지금처럼 오류다
- 입력 오류, 없음(`NOT_FOUND`), 날짜 밖, 코레일의 거절(`KORAIL_REJECTED`), 일시 장애. 사람이 그 화면에서 풀 것이 없다. 탭을 닫는다
- 전체 제한 시간을 넘긴 실패. 지금처럼 일시 장애다
- 블로그의 저장 작업(`save_draft`, `overwrite_draft`). 작업 프로세스가 탭을 갖고 결과는 `draft_job` 이 읽으므로 넘길 탭을 서버 프로세스가 모른다. 블로그는 보안 문자와 봇 탐지를 따로 가르지 않는다(로그인 호스트로 넘어간 화면은 모두 로그인이다)
- 블로그의 편집기 실패(`NAVER_BLOG_EDITOR_CHANGED`)와, 탭을 닫은 뒤 쿠키로 가린 로그인 없음. 블로그 읽기는 글이 실린 편집기를 남기지 않으려고 까닭을 가리기 전에 탭을 닫는다. 편집기 탭을 남기면 네이버 자동저장이 그 탭에서 돌 수 있다

### 플러그인 안의 구조

- 쇼핑: 지금은 `readInTab` 이 탭을 닫은 뒤 도구가 상태 JSON 을 읽어 칸 없음이 탭 밖에서 난다. `readInTab` 이 읽기 함수(`parse`)를 받아 탭 안에서 부르고, 그 함수나 호스트 확인이 `NAVER_SHOPPING_PAGE_CHANGED` 를 내면 탭을 남기고 `unexpected_page` 넘기기로 바꾼다. 없음(`NOT_FOUND`)은 지금처럼 탭을 닫는 오류다
- KTX: `searchInTab` 도 읽기 함수를 받아 탭 안에서 목록을 읽는다. 탭 안에서 난 `KORAIL_PAGE_CHANGED` 와 사람이 해야 하는 셋은 탭을 남기고 넘기기다. 남긴 탭 목록과 다음 호출의 닫기를 쇼핑과 같이 더한다
- 블로그: `withWriteTab` 이 편집기의 `login_required` 실패(탭이 로그인 호스트로 넘어감)만 탭을 닫기 전에 잡아 탭을 남기고 넘기기다. 그 밖의 실패는 지금처럼 탭을 닫은 뒤 까닭을 가린다. 남긴 탭은 다음 `withWriteTab` 이 탭을 열기 전에 닫는다

### 배포 순서

Control Plane 과 웹 → fos-ctx 1.8.0 → 플러그인 셋. 순서를 어겨도 끊기지 않는다. 플러그인이 먼저 가면 fos-ctx 1.7.0 이 새 낱말을 보내지 않아 카드만 없고, fos-ctx 가 먼저 가면 Control Plane 이 거절해 카드만 없다. 어느 때나 모델은 결과의 안내 글로 사용자에게 「내 브라우저」 에서 마쳐 달라고 한다. 넘기기는 `isError` 가 아니라 Hermes 의 연속 실패 정지에 걸리지 않는다.

### 구현 순서

1. Control Plane 어휘와 제목, `ConnectorHandoffRequestTest`·`ConnectorHandoffEndpointTest` 에 새 낱말
2. 웹 `handoff.ts`, `web/test/unit/handoff.test.ts`
3. fos-ctx `NEEDS`, 버전, `hermes/tests/test_fos_ctx.py`
4. 쇼핑 `readInTab` 의 읽기 함수와 `unexpected_page`, `tab.test.ts`·`naver-shopping.test.ts`·`tools.test.ts`
5. KTX 넘기기 결과와 남긴 탭, `tab.test.ts`·`korail-ktx.test.ts`
6. 블로그 `withWriteTab` 의 넘기기와 남긴 탭, `drafts.test.ts`
7. 스킬, `assistant.json` 도구 설명, 기능 문서, ADR 의 어휘

### 검증

| ID | 검증 |
| --- | --- |
| REQ7 | Control Plane: `bot_check`, `unexpected_page` 서명 알림이 판정 줄에 적히고 카드 목록에 보이며 알림 제목이 정해 둔 글인지, 모르는 낱말은 거절하는지(`ConnectorHandoffEndpointTest`, `ConnectorHandoffRequestTest`). 웹 단위: 새 낱말 줄을 받고 모르는 낱말을 버리는지, 제목과 「다 했어요」 글. fos-ctx: 새 낱말을 보내고 모르는 낱말은 보내지 않는지. 쇼핑: 가짜 CDP 로 네이버 밖 주소와 필수 칸 없음이 탭을 남긴 `unexpected_page` 결과(`isError` 없음)인지, 없음은 탭을 닫는 오류인지, 다음 호출이 남긴 탭을 닫는지 |
| REQ8 | KTX: 가짜 CDP 로 로그인·매크로 탐지·보안 문자 화면이 탭을 남긴 `login`·`bot_check`·`captcha` 결과인지, 자리 없음과 목록 칸 없음이 `unexpected_page` 인지, 거절과 날짜 밖은 탭을 닫는 오류인지, 다음 호출이 남긴 탭을 닫는지. 블로그: 가짜 CDP 로 로그인 화면이 탭을 남긴 `login` 결과인지, 다음 읽기가 남긴 탭을 닫는지, 쿠키로 가린 로그인 없음은 넘기지 않는지. 카드·알림·패널은 어휘만 다르므로 PR1 의 브라우저 테스트가 그대로 덮는다 |

실제 Chrome 과 코레일·네이버 화면, Hermes 왕복은 배포 뒤 운영 확인으로 남긴다(AGENTS.md 「Hermes 연동을 바꿨으면 배포 뒤 왕복시켜 본다」). 이 PR 은 운영 계정과 사용자 브라우저를 쓰지 않는다.

### 결정

| ID | 결정 | 답 | 누가 | 근거 |
| --- | --- | --- | --- | --- |
| DECISION7 | 새 어휘의 카드 글 | 위 「어휘」 표 | 작성자 | 스펙 DECISION7 이 어휘마다 정해 둔 글이다. `unexpected_page` 제목은 스펙 REQ7 의 예와 같다. `bot_check` 는 `captcha` 의 「보안 확인」 과 겹치지 않게 「자동 접속 확인」 으로 쓴다 |
| DECISION8 | 넘기기 `code` | 지금 오류 코드를 그대로 쓴다 | 작성자 | `errors` 선언과 대시보드 검증을 바꾸지 않는다. 스킬의 코드 표도 그대로 읽힌다 |
| DECISION9 | 칸 없음을 탭 안에서 판정 | 쇼핑과 KTX 의 탭 함수가 읽기 함수를 받는다 | 작성자 | 지금은 칸 없음이 탭을 닫은 뒤에 나 남길 탭이 없다. 판정을 탭 밖에 두고 닫기를 늦추면 모든 성공도 탭 닫기가 늦는다 |
| DECISION10 | 확인 도구와 블로그 저장 작업 | 넘기지 않는다 | 작성자 | 확인 도구는 탭을 열지 않고, 저장 작업의 탭은 다른 프로세스가 갖는다. 스펙 REQ8 의 「멈추면」 은 화면을 연 도구가 멈춘 때다 |
| DECISION11 | 블로그의 편집기 실패 | 넘기지 않는다(로그인 화면만 넘긴다) | 사용자 2026-11-12 (스펙 DECISION22) | 블로그 읽기는 글이 실린 편집기를 남기지 않으려고 탭을 먼저 닫는다(`drafts.test.ts` 의 「쿠키 실패 진단을 시작하기 전에 읽기 탭을 닫는다」). 편집기 탭을 사람에게 남기면 자동저장이 그 탭에서 돌 수 있다. 로그인 화면은 편집기가 없어 남겨도 된다 |


## PR3 할 일 탭

승인 카드와 화면 넘기기 카드는 입력창 위에서 옆 패널의 「할 일」 탭으로 옮기고, 묻는 카드는 답 안에 둔 채 같은 탭에도 보인다.
승인 줄, 넘기기, 묻는 카드의 저장과 서버 경로는 그대로다(스펙 DECISION6). 웹만 바뀌고 Control Plane 응답을 새로 읽지 않는다.

### 패널과 탭

`SidePanelTarget` 의 `handoff` 종류를 `{ kind: "todo"; tab: "todo" | "browser"; handoff: Handoff | null }` 로 바꾼다. 작업 과정, 결과물과 같은 상태 하나라 갈아 쓰기와 Esc 닫기는 PR1 그대로다.

| 자리 | 그리는 것 |
| --- | --- |
| 패널 머리 | 「할 일」 과 「내 브라우저」 탭, 닫기(「옆 패널 닫기」). `handoff` 가 없으면 「내 브라우저」 탭은 누를 수 없다 |
| 「할 일」 탭 | 위에서부터 묻는 카드, 넘기기 카드, 승인 카드(묶음, 원고 저장 포함). 비면 「지금 할 일이 없어요」 |
| 「내 브라우저」 탭 | PR1 의 패널 본문 그대로. 카드 제목과 「새 창으로 열기」, `BrowserScreen`, 확인 줄과 「다 했어요」 |
| 입력창 위 | 대화 허락 줄과 대기 메시지는 그대로 두고, 「할 일」 탭에 보일 것이 있으면 「할 일 N건」 한 줄. 누르면 「할 일」 탭을 연다 |

탭은 보이는 쪽만 그린다. 「할 일」 로 옮기면 화면 연결이 닫히고 「내 브라우저」 로 돌아오면 다시 열어 넘긴 탭으로 옮긴다. 주소 기준도 그때 다시 잡는다(스펙 DECISION19 의 「drawer 를 다시 열면 기준도 다시 잡는다」 와 같다). 보이지 않는 탭이 화면 중계를 붙잡아 다른 창의 화면을 빼앗지 않게 한다.

차례는 묻는 카드, 넘기기, 승인이다. 묻는 카드는 지금 답에 바로 이어지는 물음이고, 넘기기는 사람이 풀어야 에이전트가 이어 가며, 승인은 대화 전체의 기다리는 일이라 오래 남을 수 있다.

`N` 은 「할 일」 탭의 카드 수다. 승인은 묶음 안의 줄마다 한 건, 원고 저장 카드는 한 장에 한 건, 넘기기는 한 건, 묻는 카드는 하나면 한 건이다. 접어 둔 끝난 원고 저장은 세지 않는다. 카드가 없고 「끝난 저장 N건 보기」 같은 단추만 있으면 「할 일」 이라고만 쓴다.

### 언제 무엇이 열리는가

| 때 | 패널 |
| --- | --- |
| 새 승인 줄(처음 성공한 승인 줄 읽기의 번호에 없는 `PENDING` 줄)이 보인다 | 「할 일」 탭을 연다. 넘기기를 보고 있었으면 그 넘기기는 기억해 「내 브라우저」 탭으로 돌아갈 수 있다 |
| 승인 알림(`APPROVAL_REQUESTED`)을 누른다 | `/chat/<id>?todo=open` 으로 간다. 승인 줄을 처음 성공해 읽은 뒤 「할 일」 탭에 보일 것이 있으면 연다. 있든 없든 주소에서 그 값을 지운다 |
| 새 넘기기, 넘기기 알림, 넘기기 카드의 「내 브라우저 열기」 | 그 넘기기로 「내 브라우저」 탭을 연다(PR1 의 REQ1, REQ2, REQ5) |
| 「할 일 N건」 | 「할 일」 탭을 연다. 기억한 넘기기는 둔다 |
| 「할 일」 탭에서 카드가 모두 사라진다(승인·거절이 끝났다, 묻는 카드에 답했다) | 패널을 닫는다. 비어 있는 패널은 무엇을 위한 것인지 알 수 없고 휴대폰에서는 대화를 덮은 채 남는다 |
| 기억한 넘기기가 닫힌다 | 「내 브라우저」 탭이면 패널을 닫는다(REQ6). 「할 일」 탭이면 기억만 지운다 |
| 「다 했어요」(넘기기 카드와 「내 브라우저」 탭 모두) | 패널을 닫고 정해 둔 글을 보낸다(REQ3) |

새 승인 판정은 넘기기의 번호 집합(PR1 의 DECISION3)과 같은 방식이다. 첫 읽기가 도는 사이 승인 사건이 와 다음 읽기가 첫 읽기를 앞지르면, 버려진 첫 읽기의 번호로 기준을 잡아 그 사이 생긴 승인도 새 승인으로 연다. 처음 읽기에 이미 있던 승인은 「할 일 N건」 만 보이고 패널을 저절로 열지 않는다. 묻는 카드는 저절로 열지 않는다. 답이 끝날 때마다 답 바로 아래에 카드가 이미 보인다.

### 승인 영역의 높이와 묶음 승인

입력창 위의 승인 영역이 화면 높이의 40% 까지만 차지하던 규칙은 메시지 목록의 자리를 남기려던 것이다. 승인 카드가 패널로 가면 입력창 위에는 한 줄만 남으므로 그 상한을 걷고, 「할 일」 탭은 패널 높이 전체를 쓰며 안에서 스크롤한다.

- 넓은 화면은 대화가 패널 옆에 그대로 보여 승인 카드를 보며 답을 읽는다
- 좁은 화면은 시트가 대화를 덮는다. 닫으면 「할 일 N건」 한 줄과 메시지 목록이 남는다
- 상시 허락을 닫은 줄을 접지 않고 모두 펼치는 규칙, 카드 안 인자 칸의 스크롤은 그대로다

묶음 승인([ADR-087](../../../web/docs/adr/ADR-087-같은-도구의-승인-줄은-묶음으로-보이고-상시-허락을-줄-수-있는-줄만-한꺼번에-승인한다.md))은 조건과 순서가 그대로다. 바뀌는 것은 상태를 드는 자리다. 지금 `ApprovalList` 컴포넌트 안의 읽기, 펼친 묶음, 「모두 승인」 진행, 닫은 카드, 원고 저장 상태를 훅 `useApprovalList` 로 올려 대화 화면이 든다. 패널을 닫거나 탭을 옮겨도 「모두 승인」 은 끝까지 돌고 펼친 묶음이 남는다. 카드 부품(`ApprovalCard`, `ApprovalGroup`, `ContentDraftSaveCard`)과 승인·거절 요청(`approveConnectorAction`, `rejectConnectorAction`)은 그대로라 금융 주문 줄도 같은 서버 경로로만 간다.

### 묻는 카드

묻는 카드는 답 안에 그대로 둔다(ADR-026). 「할 일」 탭에는 지금 누를 수 있는 카드 하나만 더 그린다. 누를 수 있는지는 답 안의 카드와 같은 조건이다(마지막으로 보이는 줄이 에이전트 답이고, 최신 버전을 보고 있고, 돌고 있는 turn 이 없다). 두 카드는 고른 것을 따로 들고, 어느 쪽에서 보내도 같은 `submit` 으로 다음 메시지가 된다. 보내면 turn 이 돌아 둘 다 꺼진다.

판정은 순수 함수 `activeAsk(turns, selectedVersions, sending)` 이 `foldVersions`, `isLatestView`, `splitAnswer` 로 한다. 아직 저장되지 않은 줄(문자열 번호)이 있으면 없다고 본다.

### 책임과 경계

| 자리 | 책임 |
| --- | --- |
| `web/src/components/chat/approval-list.tsx` | 컴포넌트를 훅 `useApprovalList({ conversationId, refreshKey, onFresh })` 로 바꾼다. 돌려주는 것은 「할 일」 탭에 그릴 승인 영역, 카드 수, 첫 읽기 성공 여부다. 새 `PENDING` 번호를 보면 `onFresh` 를 부른다 |
| `web/src/components/chat/use-conversation-todo.tsx`(새) | 승인, 넘기기, 묻는 카드를 모아 「할 일 N건」 줄과 패널을 돌려준다. 승인 알림 진입(`todo=open`), 비면 닫기를 갖는다 |
| `web/src/components/chat/use-conversation-handoffs.tsx` | 카드 목록 대신 넘기기 카드 묶음과 「내 브라우저」 탭 본문을 돌려주고, 패널 상태를 새 모양으로 다룬다 |
| `web/src/components/chat/todo-panel.tsx`(새) | 옆 패널 모양(넓으면 `aside`, 좁으면 `Sheet`), 탭 머리, 닫기, 보이는 탭 본문 |
| `web/src/components/chat/handoff-browser.tsx`(새, 옛 `handoff-panel.tsx`) | PR1 의 `HandoffPanel` 에서 패널 껍데기를 `todo-panel.tsx` 로 넘기고 「내 브라우저」 탭 본문(`HandoffBrowser`)만 남긴다 |
| `web/src/lib/todo.ts`(새) | `activeAsk` 와 「할 일 N건」 글 |
| `web/src/lib/notification.ts` | `APPROVAL_REQUESTED` 의 갈 곳에 `?todo=open` |
| `docs/features/connector-policy.md` | 「화면」 의 40% 규칙을 「할 일」 탭으로 바꾼다 |
| `docs/features/chat.md` | 「질문 카드가 갈리는 지점」 에 「할 일」 탭 줄 |
| `docs/features/user-browser.md` | 「대화 위 화면」 을 두 탭으로 고친다 |

### 실패와 일관성

| 경우 | 처리 |
| --- | --- |
| 승인 줄을 읽지 못한다 | 지금처럼 앞 목록을 둔다. 새 승인 판정과 알림 진입은 성공한 읽기에서만 한다 |
| 「모두 승인」 중 패널을 닫는다 | 상태가 대화 화면에 있어 끝까지 돈다. 다시 열면 남은 결과와 오류가 보인다 |
| 카드 하나의 승인·거절을 보내는 중에 패널을 닫거나 탭을 옮긴다 | 보내는 중인 줄은 훅이 든 목록으로도 막아 다시 연 카드에서 같은 줄을 두 번 보내지 않는다. 그 사이 실패하면 카드 안의 오류 글은 사라지고, 다시 읽은 목록이 지금 상태를 보인다 |
| 같은 대화를 두 창에서 본다 | 창마다 새 승인에 패널이 열린다. 한쪽에서 승인하면 `approval` 사건으로 다른 쪽도 다시 읽어 카드가 사라지고, 비면 닫힌다 |
| 묻는 카드에 답하는 중에 새 승인이 온다 | 패널은 「할 일」 탭이고 묻는 카드가 맨 위라 고른 것이 그대로 남는다(같은 탭을 다시 그릴 뿐이다) |

배포 순서는 없다.

### 검증

| ID | 검증 |
| --- | --- |
| REQ9 | 브라우저 테스트(`approval-card.spec.ts`, `todo-panel.spec.ts`): 처음 연 대화의 기존 승인은 「할 일 N건」 만 보이고 패널을 열지 않는다. `approval` 사건으로 새 승인이 보이면 「할 일」 탭이 열린다. `?todo=open` 은 「할 일」 탭을 열고 주소에서 값을 지운다. 승인과 넘기기가 한 탭에 함께 보이고, 넘기기의 「내 브라우저 열기」 가 「내 브라우저」 탭으로 바꾼다. 마지막 카드를 처리하면 패널이 닫힌다. 기존 승인 카드 테스트(묶음, 모두 승인, 상시 허락, 대화 허락, 가린 인자, 금융)는 「할 일」 탭 안에서 그대로 통과한다. 금융 주문 줄의 승인·거절이 `/api/connector-actions/<id>/approve`, `/reject` 한 번씩만 보내는지. 넓은 폭과 휴대폰 폭에서 승인 줄이 많아도 메시지 목록이 남고 탭 안에서 스크롤되는지. 단위 테스트: `notificationHref` 의 `?todo=open`, 「할 일 N건」 글 |
| REQ10 | 브라우저 테스트(`ask-card.spec.ts`): 마지막 답의 묻는 카드가 「할 일」 탭에도 보이고 거기서 보낸 답이 다음 메시지로 가며, 보내면 탭에서 사라진다. 지난 답의 카드는 탭에 없다. 단위 테스트: `activeAsk` 가 최신 버전, 도는 turn, 저장 전 줄에서 없다고 보는지 |

### 결정

| ID | 결정 | 답 | 누가 | 근거 |
| --- | --- | --- | --- | --- |
| DECISION12 | 입력창 위에 남는 것 | 「할 일 N건」 한 줄. 승인 카드와 넘기기 카드는 패널로만 간다 | 스펙 REQ9 | 카드를 두 자리에 두면 같은 줄을 두 곳에서 눌러 「모두 승인」 진행과 닫은 카드 상태가 갈린다 |
| DECISION13 | 40% 규칙 | 걷는다. 「할 일」 탭이 패널 높이 전체를 쓰고 안에서 스크롤한다 | 작성자 | 규칙의 목적(메시지 목록의 자리)을 한 줄 요약과 옆 패널이 채운다 |
| DECISION14 | 탭을 옮길 때 화면 연결 | 보이는 탭만 그린다 | 작성자 | 숨긴 탭이 화면 중계를 붙잡으면 다른 창의 화면을 빼앗고 프레임을 계속 받는다. 다시 열면 넘긴 탭으로 다시 옮기므로 잃는 것은 주소 기준뿐이다 |
| DECISION15 | 「할 일」 이 비면 | 패널을 닫는다 | 작성자 | 스펙 DECISION13 과 같은 까닭이다. 휴대폰에서 빈 시트가 대화를 덮지 않는다 |
| DECISION16 | 묻는 카드로 저절로 열기 | 열지 않는다 | 작성자 | 스펙 REQ10 이 저절로 열기를 말하지 않고, 카드는 이미 답 바로 아래에 보인다. 답마다 패널이 열리면 대화를 가린다 |
| DECISION17 | 승인 상태를 드는 자리 | `ApprovalList` 를 훅으로 올려 대화 화면이 든다 | 작성자 | 패널이 닫혀도 「모두 승인」 과 펼친 묶음이 남아야 하고, 입력창 위 줄이 카드 수를 알아야 한다 |
| DECISION18 | 승인 알림 표시 | 주소 검색어 `?todo=open` | 작성자 | 넘기기 알림(PR1 의 DECISION4)과 같은 방식이다. 알림 응답과 Control Plane 을 바꾸지 않는다 |

### 결정 기록 연결

| 결정 | 종류 | ADR 또는 필요 없음의 이유 | 현재 규칙의 원본 |
| --- | --- | --- | --- |
| 승인 카드를 입력창 위에서 옆 패널 「할 일」 탭으로 옮긴다 | architecture | [ADR-20261113 / todo-tab](../../../web/docs/adr/ADR-20261113-todo-tab.md). `connector-policy.md` 의 40% 규칙을 대체한다 | 이 절, `docs/features/connector-policy.md` 의 「화면」 |
