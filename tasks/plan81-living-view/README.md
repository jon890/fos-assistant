# plan81 지금 화면

#161 의 Living View 를 **지금 화면**(`/now`)으로 만든다.
원래 기록에서 판정한 카드 넷(실패, 내 차례, 맡긴 일, 이어서 하기)을 기존 부품으로 그리고, 항목마다 이유와 출처를 보이고, 숨기기와 미루기를 준다.
결정은 `docs/adr/ADR-074-지금-화면은-원래-기록을-읽어-만든-view-이고-정해진-카드-넷만-그린다.md`, 화면 계약은 `docs/frontend/now.md` 에 있다.

## PR 과 순서

plan79, plan80, plan81 은 PR 넷으로 나눠 낸다. 세 README 가 같은 표를 갖는다.

| PR | 브랜치 | 담는 phase | 계획서 처리 |
| --- | --- | --- | --- |
| 1 | `living-view-plan79` | plan79 01, 02, 03 | `tasks/plan79-attention-surfacing/` 전체를 지운다 |
| 2 | `living-view-plan80`(PR 1 브랜치 위) | plan80 01, 02 | `tasks/plan80-follow-up/phase-01.md`, `phase-02.md` 를 지우고 `index.json` 을 phase 03 하나로 고친다. README 는 남는다 |
| 3 | `living-view-plan81`(PR 2 브랜치 위) | plan81 01, 02 | `tasks/plan81-living-view/phase-01.md`, `phase-02.md` 를 지우고 `index.json` 을 phase 03 하나로 고친다. README 는 남는다 |
| 4 | `living-view-plan81-final`(PR 3 브랜치 위) | plan80 03, plan81 03 | `tasks/plan80-follow-up/`, `tasks/plan81-living-view/` 전체를 지운다. 이 PR 이 `Closes #160`, `Closes #161` 을 적는다 |

**PR 3 에서 계획서를 고치는 방법.** `build-with-teams` 의 마감 단계가 phase 02 를 마친 뒤 아래를 한 커밋으로 한다.

- `tasks/plan81-living-view/phase-01.md` 와 `tasks/plan81-living-view/phase-02.md` 를 지운다
- `tasks/plan81-living-view/index.json` 의 `phases` 를 `{ "number": 1, "file": "phase-03.md", "execution_profile": "standard" }` 하나로, `total_phases` 와 `current_phase` 를 1 로 고친다. `number` 는 배열 안의 차례라 1 이다(`verify_task.py` 가 본다). 파일 이름은 그대로 둔다
- 고친 뒤 planning 스킬의 `scripts/verify_task.py` 를 `plan81-living-view --tasks-dir tasks --audit` 인자로 저장소 root 에서 돌려 종료 코드 0 인지 본다
- README 는 그대로 둔다. PR 4 가 디렉터리째 지운다

**PR 4 는 plan80 phase 03 을 먼저, plan81 phase 03 을 그 뒤에 한다.** plan81 phase 03 의 Blocked 조건이 plan80 phase 03 의 산출물을 본다.

네 plan 이 각각 무엇을 만드는지는 아래와 같다.

| plan | 무엇 | 이슈 |
| --- | --- | --- |
| plan78 | 문맥 묶음과 첫 반응 시간 지표. 이 네 PR 과 따로 간다 | #159, #158 |
| plan79 | 먼저 알리기의 판정과 `/api/v1/attention` API, 숨기기와 미루기, 지표 사건, 결과 전달 실패 후보 | #160 |
| plan80 | 할 일(`follow_up`)의 표, 제안 도구, API, 할 일 trigger | #160 |
| plan81 | 이 plan. 지금 화면 | #161 |

phase 마다 기대는 backend 가 다르다. 각 phase 의 「Blocked 조건」 이 그것을 코드로 확인한다.

| phase | 기대는 것 |
| --- | --- |
| 01 | `GET /api/v1/attention` 과 `GET /api/v1/attention/summary`(`attention.presentation.AttentionController`) |
| 02 | `POST /api/v1/attention/hide`, `/snooze`, `/restore`, `/events`(`attention.domain.AttentionControlEntry`). `/api/v1/follow-ups` 경로들(`followup.presentation.FollowUpController`). 할 일 후보(`attention.application.FollowUpAttentionSource`) |
| 03 | `GET /api/v1/admin/attention/metrics`(`attention.presentation.AttentionAdminController`). 제안 도구 `follow_up_propose`(`mcp.application.McpToolService`) |

결과 전달 실패 항목(`DELIVERY_FAILED`)은 「대화 열기」 로 `/chat/{conversationId}` 에 보낸다. 다시 전달은 그 대화의 알림 줄 아래 「결과 다시 전달」 이 한다. 지금 화면은 다시 전달을 부르지 않는다(`docs/backend/attention.md` 「카드의 단추와 승인 경계」).

## 모든 phase 에 걸리는 규칙

- 공개 저장소다. 루트 `AGENTS.md` 의 「공개 저장소」 를 지킨다. 테스트 데이터는 「주간 장보기 목록 정리」 같은 지어낸 값만 쓴다
- `web/AGENTS.md` 를 지킨다
  - `src/components/**` 와 `src/app/**/*.tsx` 에서 `fetch` 를 직접 쓰지 않는다. 브라우저 요청은 `web/src/lib/` 의 함수를 거치고, 그 함수는 `app/api/` 서버 라우트를 부른다
  - `style={{` 를 쓰지 않는다. 색은 테마 토큰, 상태는 `Badge` 의 의미 색, 안내는 `Notice` 다
  - 화면 문구는 해요체다. 오류 코드, 모델, 금액, 에이전트 코드를 일반 화면에 그리지 않는다
  - 줄 수가 정해지지 않은 목록의 링크는 `prefetch={false}` 다. 카드 안 항목의 링크가 모두 해당한다
  - 서버 라우트의 오류 응답은 `web/src/lib/api-response.ts` 의 `errorResponse`, 요청 본문은 `web/src/lib/json-body.ts` 의 `readJsonBody` 로 읽는다
  - 단위 테스트가 읽는 web 파일은 상대 경로로 import 하거나 import 가 없어야 한다(`web/eslint.config.mjs` 의 `NODE_TEST_READ_FILES`). 이 plan 이 단위 테스트로 읽는 `web/src/lib/attention.ts` 와 `web/src/lib/format.ts` 는 import 가 없다
- 항목의 `title` 은 모델이 쓴 글일 수 있다. 평문으로만 그린다. `dangerouslySetInnerHTML` 이나 마크다운 렌더러를 쓰지 않는다(ADR-009)
- 이유 문구는 `docs/frontend/now.md` 의 「이유 문구」 표 그대로다. 표에 없는 문구를 만들지 않는다
- 카드 순서와 항목 순서는 서버 응답을 그대로 따른다. 화면이 다시 정렬하지 않는다
- **항목의 식별자는 응답의 `execution`, `followUp`, `actionId`, `conversationId` 칸에서 읽는다.** `itemKey` 를 잘라 식별자를 얻지 않는다. `itemKey` 와 `stateKey` 는 제어와 사건을 보낼 때 그대로 돌려보내는 값이다
- 운영 코드에 시험용 문을 만들지 않는다. 합성 데이터가 더 필요하면 `backend/src/test/java/com/bifos/assistant/testsupport/` 에 `@ConditionalOnProperty(name = "assistant.test-support.enabled", havingValue = "true")` 컨트롤러로 더한다(`ChatTestSupportController` 와 같은 모양)
- 브라우저 검사는 로컬에서 이 plan 이 고친 spec 만 돌린다. 전체 검사는 PR 의 CI(`browser-mobile`, `browser-desktop`)가 맡는다
- 기능 변경과 포맷을 한 커밋에 섞지 않는다. 처음 고치는 파일은 `pnpm format:changed` 결과를 따로 커밋한다
- 실행의 부모와 자식 흐름은 루트 `AGENTS.md` 의 용어 표대로 실행 트리, 루트라고 쓴다

## 범위 밖

- 고정(pin). ADR-074 가 미뤘다
- 오늘과 다음 카드. 일정 커넥터가 생긴 뒤에 정한다
- 비용 이상 카드. 관리자 영역 안에서 따로 정한다(ADR-063)
- 문맥 묶음에서 고른 중요 맥락 카드
- 외부 알림 채널(Discord, Web Push). ADR-072 의 열린 질문이다
- 모델이 만드는 화면 구성이나 카드 문구
- 판정 규칙과 기준값을 바꾸는 일. plan79 와 `docs/backend/attention.md` 가 갖는다

## 「아직 구현 전」 표시

- phase 03 이 이 plan 의 마지막이다. phase 03 이 아래 표시를 지운다
  - `docs/adr/ADR-074-…md` 의 `status` 와 `docs/adr/INDEX.md` 의 ADR-074 줄에 있는 「아직 구현 전이다」
  - `docs/frontend/now.md` 머리 단락, `docs/README.md` 의 `frontend/now.md` 줄에 있는 「아직 구현 전이다」
  - `docs/flow.md` 「지금 화면을 열 때」 의 「아직 구현 전이다」
  - `docs/prd.md` 「답하는 비서에서 먼저 챙기는 비서로」 표의 ADR-073 줄과 ADR-074 줄을 「범위와 확인 방법」 표로 옮긴다. ADR-072 줄은 plan79 phase 03 이 이미 옮겼다. ADR-071 줄과 첫 반응 시간 줄은 그 ADR 이나 절의 「아직 구현 전」 을 지우는 PR 이 옮긴다
- 이 디렉터리는 PR 4 의 `build-with-teams` 마감 단계가 지운다. phase 는 `tasks/` 를 고치지 않는다
