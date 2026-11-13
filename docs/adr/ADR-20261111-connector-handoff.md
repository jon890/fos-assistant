## ADR-20261111 / connector-handoff: 플러그인이 사람에게 화면을 넘기면 fos-ctx 가 결과의 칸을 알리고, 대화의 카드와 「다 했어요」 로 이어 간다

- **status**: `accepted`
- Date: 2026-11-11
- **decision-type**: `architecture`
- **areas**: `security, operations`
- **현재 규칙의 원본**: 설계 [`docs/features/naver-shopping/design.md`](../features/naver-shopping/design.md) 의 「PR2 화면 넘기기(REQ7, REQ8)」, 스펙 [`spec.md`](../features/naver-shopping/spec.md) 의 REQ7, REQ8(사용자 확인 2026-11-10)
- [ADR-026](ADR-026-에이전트가-물을-것은-답-끝의-태그로-두고-화면이-카드로-그린다.md) 의 묻는 카드처럼 실행은 기다리지 않고, 카드는 다음 메시지를 보내는 단추다. [ADR-070](ADR-070-알림은-control-plane-의-notification-표가-원장이고-웹은-사용자-단위-SSE-로-받는다.md) 의 `notification` 에 종류 하나를 더한다

### 결정

**「내 브라우저」 를 쓰는 플러그인이 사람이 해야 하는 화면에서 멈추면, 오류가 아닌 결과에 `handoff: {code, need, tab}` 을 싣고 fos-ctx 의 `transform_tool_result` hook 이 그 칸을 서명해 Control Plane 에 알린다.**

**화면 넘기기를 `isError` 로 돌려주지 않는다(2026-11-12).** 처음에는 오류로 돌려줬다. 그러나 Hermes 는 같은 MCP 서버의 `isError` 결과가 연속 3회면 60초 동안 그 서버의 호출을 막는다. 보안문자가 연달아 나오면 사람이 풀고 「다 했어요」 를 눌러도 다음 호출이 네이버에 닿지 않고 거절됐다(운영에서 확인).

| 항목 | 정한 것 |
| --- | --- |
| 결과 모양 | `isError` 없는 결과 `{"status": "HUMAN_ACTION_REQUIRED", "message", "handoff": {"code", "need", "tab"}}`. `need` 는 `login`, `captcha`, `device_check`, `bot_check`(봇 탐지), `unexpected_page`(예상 밖 화면, 2026-11-12 더함, [사람이 도와야 하는 지점 설계](../features/user-browser/design.md) 의 「PR2 넘기기 어휘 확장과 KTX·블로그 넘기기」). 결제(`payment`)와 탭 확인(`tab_check`)은 플러그인 결과로 받지 않고 주문서 채우기의 결과 판정이 직접 적는다([ADR-20261113 / purchase-checkout](ADR-20261113-purchase-checkout.md), 2026-11-13 더함). `status` 와 `message`(끝나지 않았고 결과가 없다, 사람이 풀 때까지 다시 부르지 않는다)가 모델이 성공으로 읽지 않게 한다. 옛 모양 `{"error": {"code", "handoff": {"need", "tab"}}}` 는 fos-ctx 가 받기만 하고 플러그인은 내지 않는다 |
| 알리는 곳 | `/internal/hermes/connector-handoff`. 판정 경로와 같은 profile 토큰과 서명 key. 서명할 글은 `v1-connector-handoff` 와 호출 네 칸(`hermes_tool`, `root_session_id`, `session_id`, `tool_call_id`), `code`, `need`, `tab` |
| 받는 쪽 | 같은 호출의 판정 줄(`connector_action`)을 dedupe key 로 찾아, hook 에 통과로 답한 같은 등록 이름의 줄에만 넘기기 칸 넷을 비어 있을 때 한 번 적는다. 처음 적을 때 알림 `HANDOFF_REQUESTED` 한 건 |
| 카드 | 웹이 그 대화의 열린 넘기기를 입력창 위에 그린다. 종류와 탭은 판정 줄에서만 온다. 「내 브라우저 열기」 는 그 탭을 고른 채 새 창을 열고, 「다 했어요」 는 정해 둔 글을 다음 메시지로 보낸다 |
| 닫기 | 그 대화에서 사람이 보낸 turn(사용자의 다음 메시지, 「다 했어요」, 다시 생성)이 시작되면 `chat` 이 낸 사건(`byUser`)으로 `connector.handoff` 가 열린 넘기기를 닫고 그 알림을 읽음으로 바꾼다. 맡긴 일의 결과를 전하는 자동 turn, 예약 작업, 먼저 살펴보기, 사용자가 눌러 결과를 다시 전달하는 turn 은 닫지 않는다(2026-11-12) |
| 남긴 탭 | 플러그인이 MCP 서버 프로세스 메모리에 두고 다음 탭 열기가 닫는다 |

- **대체된 부분**: 「카드」 행의 「「내 브라우저 열기」 는 그 탭을 고른 채 새 창을 열고」 는 [ADR-20261112 / handoff-drawer](../../web/docs/adr/ADR-20261112-handoff-drawer.md) 가 대체했다. 카드는 대화 위 옆 패널에 「내 브라우저」 를 띄운다.

**「내 브라우저」 화면은 사이트의 확인 창을 보이고 사람이 고르게 한다(#702).** 플러그인이 읽는 탭의 창은 지금처럼 플러그인이 닫기로 넘긴다.

### 맥락

검색과 상세 읽기는 승인 없이 돌아 결과가 Control Plane 을 거치지 않고 모델에게 바로 간다. Control Plane 이 결과를 보지 못하면 카드를 띄울 수 없다.
카드의 종류와 탭을 모델이 쓴 글로 정하면 모델이 빠뜨리거나 바꿀 수 있다. 뒤의 결제 넘기기는 금액도 실어야 해서 모델 글로 그리면 안 된다.
같은 기반을 KTX 예매도 쓴다. 플러그인 이름을 Control Plane 과 hook 이 몰라야 한다(rule-65).

### 대안 기각

- **답 끝의 태그(ADR-026 방식)**: 모델이 태그를 빠뜨리면 카드가 뜨지 않아 REQ7 을 보장하지 못하고, 태그의 탭 id 는 모델이 바꿀 수 있다
- **플러그인이 Control Plane 에 직접 알린다**: 플러그인에 중계 밖의 운영 주소와 인증이 들어가 플러그인의 운영 값을 금지한 결정(ADR-064)과 부딪친다
- **Hermes 의 도구 완료 사건(`tool.completed.preview`)을 읽는다**: 500자로 자르고 비밀값 가림이 탭 id 를 바꿀 수 있으며 호출 id 가 없어 판정 줄과 맞출 수 없다
- **넘기기를 오류(`isError`)로 계속 돌려준다**: Hermes 의 연속 실패 집계에 들어가 사람이 푼 뒤에도 서버가 정지한다. 정지 임계값과 시간은 Hermes 의 모듈 상수라 우리가 바꾸지 못한다(ADR-001)
- **`assistant.json` 에 넘기기 코드를 선언한다**: 대시보드 검증과 카탈로그 전달을 함께 늘려야 한다. 결과를 만드는 것은 플러그인 소스라 선언이 막아 주는 것이 적다
- **새 테이블**: 판정 줄이 이미 사용자, 대화, 실행, 플러그인을 갖고 hook 의 호출과 하나로 맞는다(테이블을 늘리지 않는다, 2026-11-01)
- **실행을 멈추고 사람을 기다린다**: 기다리는 동안 동시 실행 한도와 스레드를 잡는다(spec DECISION11)

### 결과

- 얻는 것:
  - 모델이 무엇을 쓰든 사람이 해야 하는 화면에서 카드와 알림이 뜬다
  - 카드의 탭은 플러그인이 남긴 탭 그대로라 사람이 바로 그 화면에서 푼다
  - 다른 플러그인도 결과 모양만 맞추면 같은 카드를 얻는다
  - 사람이 풀고 돌아온 다음 호출이 Hermes 의 정지에 걸리지 않는다
  - 확인 창에서 「내 브라우저」 가 멈추지 않는다
- 감당할 것:
  - hook 은 실패해도 닫히지 않는다. 알리지 못하면 카드가 없고 모델의 글 안내만 남는다
  - 넘기기가 실패로 세어지지 않아, 모델이 안내를 어기고 같은 화면을 연달아 다시 부르면 막아 주는 정지가 없다. 결과 글과 스킬이 다시 부르지 말라고 시킬 뿐이다
  - fos-ctx 를 먼저 배포한다. 플러그인을 먼저 올리면 그 사이 새 모양을 hook 이 읽지 못해 카드가 뜨지 않는다
  - 카드를 닫는 기준이 「사람이 보낸 다음 turn」 이라 사람이 마치지 않고 다른 말을 해도 카드가 닫힌다. 담당이 멈춘 뒤 비서가 결과를 전하는 자동 turn 은 닫지 않아야 사용자가 보기 전에 카드가 사라지지 않는다. 다시 부른 도구가 같은 화면을 만나면 새 카드가 뜬다
  - 남긴 탭은 「다음 호출은 새 turn 에서 온다」 고 보고 닫는다. 모델이 같은 turn 에서 탭을 여는 도구를 다시 부르거나 같은 사용자의 다른 대화가 같은 MCP 서버를 부르면 사람이 열기 전에 그 탭이 닫히고 카드가 하나 더 생긴다. 앞 카드의 「내 브라우저 열기」 는 지금 탭에 머문다
  - 남긴 탭 목록은 프로세스 메모리라 MCP 서버가 다시 뜨면 그 탭은 사람이 닫을 때까지 남는다
  - 화면이 붙기 전에 떠 있던 확인 창을 Chrome 이 다시 알리는지는 실제 Chrome 으로 확인하지 않았다
- **확인 방법**: `hermes/connectors/naver-shopping/tests/`, `hermes/tests/test_fos_ctx.py` 의 `HandoffReportTest`, `ConnectorHandoffRequestTest`, `ConnectorHandoffEndpointTest`, `BrowserScreenSessionTest`, `web/test/browser/handoff-card.spec.ts`, `web/test/browser/user-browser-screen.spec.ts`
