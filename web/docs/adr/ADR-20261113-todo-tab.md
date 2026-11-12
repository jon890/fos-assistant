## ADR-20261113 / todo-tab: 승인 카드와 넘기기 카드는 입력창 위에서 옆 패널의 「할 일」 탭으로 옮기고 입력창 위에는 한 줄만 남긴다

- **status**: `accepted`
- Date: 2026-11-13
- **decision-type**: `architecture`
- **현재 규칙의 원본**: 설계 [`docs/features/user-browser/design.md`](../../../docs/features/user-browser/design.md) 의 「PR3 할 일 탭」, 스펙 [`spec.md`](../../../docs/features/user-browser/spec.md) 의 REQ9, REQ10(사용자 확인 2026-11-12), 기능 파일 [`docs/features/user-browser.md`](../../../docs/features/user-browser.md) 의 「대화 위 화면」 과 [`docs/features/connector-policy.md`](../../../docs/features/connector-policy.md) 의 「화면」
- **결정**: [ADR-20261112 / handoff-drawer](ADR-20261112-handoff-drawer.md) 의 옆 패널을 「할 일」 과 「내 브라우저」 두 탭으로 나눈다. 승인 카드와 화면 넘기기 카드는 입력창 위가 아니라 「할 일」 탭에만 그리고, 묻는 카드는 답 안에 둔 채 지금 누를 수 있는 것을 「할 일」 탭에도 그린다. 입력창 위에는 「할 일 N건」 한 줄만 남긴다. 새 승인 줄이나 승인 알림은 「할 일」 탭을 연다. 승인 상태는 화면 훅(`useApprovalList`)이 들어 패널을 닫아도 「모두 승인」 이 끝까지 돈다.
- **대체하는 것**: `connector-policy.md` 의 「승인 영역은 화면 높이의 40% 까지만 차지하고 안에서 스크롤한다」 를 대체한다. 묶음 승인 조건([ADR-087](ADR-087-같은-도구의-승인-줄은-묶음으로-보이고-상시-허락을-줄-수-있는-줄만-한꺼번에-승인한다.md))과 승인·거절의 서버 경로([ADR-050](../../../backend/docs/adr/ADR-050-커넥터-쓰기는-control-plane-이-승인-줄을-저장하고-승인한-인자로-한-번만-실행한다.md))는 그대로다.
- **맥락**: 사람이 도와야 하는 일이 입력창 위 승인 카드, 입력창 위 넘기기 카드, 답 안의 묻는 카드 세 자리에 흩어져 있었다. 승인 카드가 많으면 입력창 위가 화면의 40% 를 차지해 대화를 읽기 어려웠다(사용자 2026-11-12, 스펙 DECISION6).
- **대안 기각**:
  - 입력창 위 카드를 두고 탭에도 그린다: 같은 줄을 두 곳에서 눌러 「모두 승인」 진행과 닫은 카드 상태가 갈린다
  - 세 길을 하나의 저장 자리로 합친다: 승인과 묻는 카드의 저장을 다시 짜야 한다(스펙 DECISION6 에서 범위 밖)
  - 탭을 숨긴 채 화면 연결을 둔다: 보이지 않는 탭이 화면 중계를 붙잡아 다른 창의 화면을 빼앗는다
- **결과**:
  - 얻는 것: 사람이 할 일이 한 자리에 모이고 입력창 위는 한 줄로 줄어 메시지 목록이 자리를 갖는다. Control Plane 과 승인 계약은 그대로다
  - 감당할 것: 승인 카드를 보려면 한 번 더 누르거나 새 승인이 패널을 열기를 기다린다. 휴대폰에서는 새 승인이 전체 폭 시트로 대화를 덮는다. 「내 브라우저」 탭에서 「할 일」 로 옮겼다 돌아오면 화면을 다시 열고 주소 기준을 다시 잡는다
- **확인 방법**: `web/test/browser/todo-panel.spec.ts`, `web/test/browser/approval-card.spec.ts`, `web/test/browser/ask-card.spec.ts`, `web/test/unit/todo.test.ts`, `web/test/unit/notification.test.ts`
