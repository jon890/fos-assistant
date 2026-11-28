# 휴대폰의 기본 대화와 맡긴 일 카드

휴대폰에서 비서와의 대화방 하나로 일을 맡기고, 오래 걸리거나 확인이 필요한 일은 맡긴 일 카드로 다시 찾는 기능이다. 합의한 변경과 단계는 [스펙](mobile-default-chat/spec.md)이 갖고, 이 파일은 지금 동작하는 것만 적는다.

covers: `backend/src/main/java/com/bifos/assistant/connector/handoff/application/ConnectorHandoffs.java`, `backend/src/main/java/com/bifos/assistant/connector/handoff/application/HandoffButtons.java`, `web/src/components/chat/use-conversation-handoffs.tsx`, `web/src/components/chat/todo-panel.tsx`, `web/src/components/chat/use-conversation-todo.tsx`

## 요구

PR1(넘기기를 그 카드로만 닫기)까지 구현했다. 맡긴 일 카드와 「맡긴 일 N건」(PR2), 진행 문구(PR3)는 아직이다. 그 밖의 지금 동작은 [에이전트 팀](agent-team.md) 의 「대화방」, [사용자 브라우저](user-browser.md) 의 「대화 위 화면」, [지금 화면과 알림, 할 일](attention.md) 이 갖는다.

### 넘기기 카드가 닫히는 때

로그인, 보안 확인 같은 넘기기 카드는 같은 대화에서 다른 질문을 보내거나 화면을 새로 고쳐도 남는다. 시간이 지나도 저절로 닫히지 않는다.

| 닫히는 때 | 그 뒤 |
| --- | --- |
| 「다 했어요」(결제는 「결제했어요」) | 그 카드만 닫히고 이어 가는 글이 한 번만 간다. 남긴 탭이 없어졌거나 연결이 끊겼으면 글을 보내지 않고 대화에 「다시 맡겨 주세요」 알림 줄이 남는다 |
| 「그만할래요」(결제는 「사지 않을래요」) | 그 카드만 닫히고 그만둔다는 글이 간다 |
| 입력창으로 답해 그 플러그인의 다음 호출이 넘기기 없이 끝난다 | 그 카드가 닫히고 남긴 탭도 닫힌다. 다른 플러그인의 성공으로는 닫히지 않는다 |
| 같은 플러그인이 새 넘기기를 낸다 | 앞선 카드가 닫히고 새 카드가 보인다 |

두 창에서 함께 눌러도 이어 가는 글은 한 번만 간다. 승인한 주문서 채우기가 사람을 기다리는 동안의 카드는 「다 했어요」 로만 닫힌다. 구조는 [설계](mobile-default-chat/design.md) 의 「넘기기의 상태」 가 갖는다.
