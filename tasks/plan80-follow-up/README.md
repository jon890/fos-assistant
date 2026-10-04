# plan80 할 일

에이전트가 대화 중에 제안하고 사람이 받아들인 할 일(`follow_up`)을 만든다. #160 원문이 「추적 목표」 라고 부른 것이 이것이다.
결정은 `docs/adr/ADR-073-할-일은-에이전트가-제안하고-사람이-받아들인-것만-챙긴다.md`, 계약은 `docs/backend/follow-up.md` 와 `docs/backend/schema/attention.md` 의 「follow_up」 이 갖는다.

## 이 plan 이 만드는 것

| phase | 만드는 것 | 기대는 것 |
| --- | --- | --- |
| 01 | 최상위 패키지 `followup`, `follow_up` 표, 상태 전이, 사람이 쓰는 API `/api/v1/follow-ups` | plan79 전체(PR 1) |
| 02 | 먼저 알리기 판정에 `FOLLOW_UP_PROPOSED`, `FOLLOW_UP_OPEN` 을 더한다 | phase 01, plan79 phase 01 과 02 |
| 03 | Control Plane MCP 도구 `follow_up_propose`, 제안 억제, fos-ctx 의 서명 필수 도구, 「아직 구현 전」 표시 정리 | phase 02, plan81 phase 02(할 일을 받아들일 화면) |

화면(지금 화면의 「내 차례」 카드와 할 일 폼)은 이 plan 이 만들지 않는다. plan81 이 만든다.

## PR 과 순서

plan79, plan80, plan81 은 PR 넷으로 나눠 낸다. 세 README 가 같은 표를 갖는다.

| PR | 브랜치 | 담는 phase | 계획서 처리 |
| --- | --- | --- | --- |
| 1 | `living-view-plan79` | plan79 01, 02, 03 | `tasks/plan79-attention-surfacing/` 전체를 지운다 |
| 2 | `living-view-plan80`(PR 1 브랜치 위) | plan80 01, 02 | `tasks/plan80-follow-up/phase-01.md`, `phase-02.md` 를 지우고 `index.json` 을 phase 03 하나로 고친다. README 는 남는다 |
| 3 | `living-view-plan81`(PR 2 브랜치 위) | plan81 01, 02 | `tasks/plan81-living-view/phase-01.md`, `phase-02.md` 를 지우고 `index.json` 을 phase 03 하나로 고친다. README 는 남는다 |
| 4 | `living-view-plan81-final`(PR 3 브랜치 위) | plan80 03, plan81 03 | `tasks/plan80-follow-up/`, `tasks/plan81-living-view/` 전체를 지운다. 이 PR 이 `Closes #160`, `Closes #161` 을 적는다 |

**PR 2 에서 계획서를 고치는 방법.** `build-with-teams` 의 마감 단계가 phase 02 를 마친 뒤 아래를 한 커밋으로 한다.

- `tasks/plan80-follow-up/phase-01.md` 와 `tasks/plan80-follow-up/phase-02.md` 를 지운다
- `tasks/plan80-follow-up/index.json` 의 `phases` 를 `{ "number": 1, "file": "phase-03.md", "execution_profile": "deep" }` 하나로, `total_phases` 와 `current_phase` 를 1 로 고친다. `number` 는 배열 안의 차례라 1 이다(`verify_task.py` 가 본다). 파일 이름은 그대로 둔다
- 고친 뒤 planning 스킬의 `scripts/verify_task.py` 를 `plan80-follow-up --tasks-dir tasks --audit` 인자로 저장소 root 에서 돌려 종료 코드 0 인지 본다
- README 와 `remote-verification.md` 는 그대로 둔다. PR 4 가 디렉터리째 지운다

- **phase 03 은 할 일을 받아들일 화면이 들어온 브랜치 위에서만 한다.** 도구 응답이 「지금 화면에서 받아들이면 챙긴다」 고 안내하므로, 그 화면이 없으면 모델이 없는 화면을 안내하고 제안은 대화마다 3개에서 막힌다. `web/src/lib/follow-up-api.ts`(plan81 phase 02 가 만든다)가 없으면 멈춘다
- **phase 02 는 plan79 의 `attention` 패키지와 제어 표가 있는 브랜치 위에서만 한다.** 시험 데이터는 phase 01 의 `FollowUp.proposed(...)` 로 만들어 제안 도구를 기다리지 않는다
- 마이그레이션 번호는 phase 01 의 `V72__follow_up.sql` 로 고정한다. V69 와 V70 은 다른 작업이, V71 은 plan79 가 쓴다. 머지 직전 main 과 겹치면 다음 번호로 옮기고 변경 파일 표도 같은 커밋에서 고친다
- 이 plan 은 plan78 의 코드에 기대지 않는다. plan78 은 이 네 PR 과 따로 간다

## 배포 순서

**Hermes plugin `fos-ctx` 를 먼저 배포하고 Control Plane 을 그다음에 배포한다.**
phase 03 이 `follow_up_propose` 를 fos-ctx 의 서명 필수 도구로 넣는다.
옛 plugin 도 Control Plane MCP 도구에 서명할 수 있으면 서명해 보내므로 순서가 바뀌어도 제안은 동작한다. 서명하지 못할 때 Hermes 쪽에서 막는 것만 빠지고, 그 호출은 서버가 거절한다(`hermes/README.md` 의 서명 표).
그래도 plugin 을 먼저 올리는 관례(`docs/code-architecture.md` 의 「Hermes 쪽 코드」)를 따른다.
배포 뒤 확인은 `remote-verification.md` 에 있다.

## 모든 phase 에 걸리는 규칙

- 공개 저장소다. 루트 `AGENTS.md` 의 「공개 저장소」 를 지킨다. 테스트의 제목과 대화는 `할 일 검사 7391` 처럼 지어낸 글을 쓴다
- **할 일의 제목을 로그, 실행 사건, 대화의 알림 줄에 남기지 않는다.** 로그에는 사용자 번호, 할 일 번호, 실행 번호, 결과만 적는다
- 할 일은 대화의 `instructions` 에 싣지 않는다. 할 일에서 Hermes 실행이나 커넥터 호출을 시작하지 않는다
- 주인은 웹 JWT 의 사용자(`CurrentUserProvider.require()`)나 MCP 호출의 origin 실행의 사용자(`McpCaller.user()`)다. 요청 본문과 도구 인자로 사용자를 받지 않는다
- 남의 할 일과 없는 할 일은 같은 404 `FOLLOW_UP_NOT_FOUND` 로 답한다
- backend 의 새 코드는 `backend/AGENTS.md` 를 따른다. 로거는 `@Slf4j`, 시각은 주입받은 `Clock`, `@Enumerated` enum 은 `followup.domain.type`, 서비스가 받고 돌려주는 값은 `followup.application.model` 의 record, 요청과 응답 record 는 `FollowUpDtos.java`, 테스트 메서드는 영문 camelCase 와 한국어 `@DisplayName`
- `application` 은 `presentation` 의 타입을 받지 않는다(`ArchitectureRules.LAYER_DIRECTION`). 컨트롤러가 요청을 `followup.application.model` 의 record 로 바꿔 넘기고, 서비스가 돌려준 record 로 응답을 만든다
- 새 표는 `ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci` 를 적는다(`docs/backend/schema/README.md` 의 「마이그레이션 작성 규칙」). DDL 만 담는다
- 기능 변경과 포맷(`./gradlew spotlessApply`)을 한 커밋에 섞지 않는다
- 주석과 Javadoc 은 한국어로 쓴다. 용어는 루트 `AGENTS.md` 의 「용어」 표를 따른다(실행 트리, 루트, 할 일)

## 범위 밖

- 할 일에서 Hermes 위임이나 커넥터 호출로 넘어가는 자동 실행. 승인 정책을 먼저 정한다(ADR-073 의 6번)
- 그룹이 함께 챙기는 할 일. 할 일은 사용자 한 사람의 것이다
- 할 일만 모아 보는 별도 화면, 끝난 할 일의 이력 화면. 지금 화면의 「내 차례」 카드가 그 자리다
- 할 일 제목의 암호화

## 「아직 구현 전」 표시

- phase 01 이 `docs/backend/schema/attention.md` 의 「follow_up」 절과 머리 문장, `docs/backend/schema/README.md` 의 `follow_up` 표시를 지운다
- phase 02 가 `docs/backend/attention.md` 머리의 할 일 후보 단락과 `docs/README.md` 의 `backend/attention.md` 줄에 남은 할 일 표시를 지운다
- phase 03 이 ADR-073 의 `status`, `docs/adr/INDEX.md` 의 ADR-073 줄, `docs/backend/follow-up.md` 의 구현 전 단락, `docs/flow.md` 「할 일을 제안할 때」 의 구현 전 줄, `docs/code-architecture.md` 「아직 만들지 않은 것」 의 할 일 줄, `docs/README.md` 의 `backend/follow-up.md` 줄을 지운다. `docs/prd.md` 의 ADR-073 줄 옮기기는 plan81 phase 03 이 한다
