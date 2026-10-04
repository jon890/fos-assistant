# plan80 할 일

에이전트가 대화 중에 제안하고 사람이 받아들인 할 일(`follow_up`)을 만든다. #160 원문이 「추적 목표」 라고 부른 것이 이것이다.
결정은 `docs/adr/ADR-073-할-일은-에이전트가-제안하고-사람이-받아들인-것만-챙긴다.md`, 계약은 `docs/backend/follow-up.md` 와 `docs/backend/schema/attention.md` 의 「follow_up」 이 갖는다.

## 이 plan 이 만드는 것

| phase | 만드는 것 | 기대는 것 |
| --- | --- | --- |
| 01 | 최상위 패키지 `followup`, `follow_up` 표, 상태 전이, 사람이 쓰는 API `/api/v1/follow-ups` | 없음 |
| 02 | 먼저 알리기 판정에 `FOLLOW_UP_PROPOSED`, `FOLLOW_UP_OPEN` 을 더한다 | phase 01, plan79 phase 01 의 `attention` 패키지 |
| 03 | Control Plane MCP 도구 `follow_up_propose`, 제안 억제, fos-ctx 의 서명 필수 도구 | phase 02, plan81 phase 02(할 일을 받아들일 화면) |

화면(지금 화면의 「내 차례」 카드와 할 일 폼)은 이 plan 이 만들지 않는다. plan81 이 만든다.

## 순서

네 plan 의 구현 순서는 아래 하나다. 네 README 가 같은 표를 갖는다.

| 순서 | phase | 먼저 있어야 하는 것 |
| --- | --- | --- |
| 언제든 | plan78 01, 02, 03 | 없다 |
| 1 | plan79 01, 02 | 없다 |
| 2 | plan80 01, 02 | plan80 02 는 plan79 01(`attention` 패키지) |
| 3 | plan81 01, 02 | plan79 01, 02 와 plan80 01, 02 |
| 4 | plan80 03 | plan81 02(할 일을 받아들일 화면) |
| 5 | plan81 03 | plan80 03 |
| #162 뒤 | plan78 04, plan79 03 | #162 가 main 에 있다 |

- phase 01 은 plan79 를 기다리지 않아도 된다. 다만 `TopLevelPackageOrder.ORDER` 를 함께 고치므로 plan79 phase 01 이 먼저 main 에 들어왔으면 그 위에서 자리를 정한다
- **phase 03 은 할 일을 받아들일 화면이 main 에 들어온 뒤에만 한다.** 도구 응답이 「지금 화면에서 받아들이면 챙긴다」 고 안내하므로, 그 화면이 없으면 모델이 없는 화면을 안내하고 제안은 대화마다 3개에서 막힌다. `web/src/lib/follow-up-api.ts`(plan81 phase 02 가 만든다)가 없으면 멈춘다
- **phase 02 는 plan79 phase 01 이 main 에 들어온 뒤에만 한다.** `backend/src/main/java/com/bifos/assistant/attention` 이 없으면 멈춘다. 시험 데이터는 phase 01 의 `FollowUp.proposed(...)` 로 만들어 제안 도구를 기다리지 않는다
- 마이그레이션 번호는 구현할 때 main 의 마지막 다음 번호를 쓴다. phase 문서의 `V68__follow_up.sql` 은 자리표시다. 번호를 바꾸면 phase 의 변경 파일 표도 같은 커밋에서 고친다

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
- backend 의 새 코드는 `backend/AGENTS.md` 를 따른다. 로거는 `@Slf4j`, 시각은 주입받은 `Clock`, `@Enumerated` enum 은 `followup.domain.type`, 요청과 응답 record 는 `FollowUpDtos.java`, 테스트 메서드는 영문 camelCase 와 한국어 `@DisplayName`
- 새 표는 `ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci` 를 적는다(`docs/backend/schema/README.md` 의 「마이그레이션 작성 규칙」). DDL 만 담는다
- 기능 변경과 포맷(`./gradlew spotlessApply`)을 한 커밋에 섞지 않는다
- 주석과 Javadoc 은 한국어로 쓴다. 용어는 루트 `AGENTS.md` 의 「용어」 표를 따른다(실행 트리, 루트, 할 일)

## 범위 밖

- 할 일에서 Hermes 위임이나 커넥터 호출로 넘어가는 자동 실행. 승인 정책을 먼저 정한다(ADR-073 의 6번)
- 그룹이 함께 챙기는 할 일. 할 일은 사용자 한 사람의 것이다
- 할 일만 모아 보는 별도 화면, 끝난 할 일의 이력 화면. 지금 화면의 「내 차례」 카드가 그 자리다
- 할 일 제목의 암호화

## 계획서 삭제와 「아직 구현 전」 표시

- phase 03 이 `docs/adr/ADR-073-할-일은-에이전트가-제안하고-사람이-받아들인-것만-챙긴다.md` 의 `status` 와 `docs/adr/INDEX.md` 의 「아직 구현 전이다」 를 지운다
- 같은 phase 가 `docs/backend/follow-up.md` 의 구현 전 단락, `docs/flow.md` 「할 일을 제안할 때」 의 구현 전 줄, `docs/code-architecture.md` 「아직 만들지 않은 것」 의 할 일 줄을 지운다
- `docs/backend/schema/attention.md` 는 표마다 「아직 구현 전이다」 줄을 둔다. phase 01 은 「follow_up」 절의 줄만 지우고, 나머지 두 표의 줄은 plan79 가 지운다
- 이 디렉터리는 plan80 의 마지막 phase 를 머지하는 PR 이 지운다
