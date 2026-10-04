# plan79 먼저 알리기의 판정

#160 의 먼저 알리기 판정과 지금 화면이 읽는 API 를 backend 에 만든다.
결정은 `docs/adr/ADR-072-먼저-알리기의-기본값은-알리지-않음이고-control-plane-기록에서-정한-신호만-화면-안에-올린다.md` 와 `docs/adr/ADR-074-지금-화면은-원래-기록을-읽어-만든-view-이고-정해진-카드-넷만-그린다.md` 에 있다.
계약은 `docs/backend/attention.md`, 표는 `docs/backend/schema/attention.md` 가 갖는다.

## 이 plan 이 만드는 것

| phase | 만드는 것 | 시작 조건 |
| --- | --- | --- |
| 01 | 새 최상위 패키지 `attention`, 읽을 때 계산하는 판정, `GET /api/v1/attention`, `GET /api/v1/attention/summary` | 없다 |
| 02 | 숨기기, 미루기, 되돌리기, 지표 사건, 관리자 지표, 사건 보관 기간 정리 | phase 01 |
| 03 | 결과 전달 실패 후보 `DELIVERY_FAILED`, 「아직 구현 전」 표시 정리 | phase 02 |

화면은 이 plan 이 만들지 않는다. plan81 이 이 API 를 읽어 그린다.
할 일(`follow_up`)의 후보 `FOLLOW_UP_PROPOSED`, `FOLLOW_UP_OPEN` 은 plan80 이 더한다.
결과 전달 상태는 PR #167 로 main 에 들어왔다(`V67__result_delivery.sql`, ADR-075). phase 03 은 그 표를 읽기만 한다.

## PR 과 순서

plan79, plan80, plan81 은 PR 넷으로 나눠 낸다. 세 README 가 같은 표를 갖는다.

| PR | 브랜치 | 담는 phase | 계획서 처리 |
| --- | --- | --- | --- |
| 1 | `living-view-plan79` | plan79 01, 02, 03 | `tasks/plan79-attention-surfacing/` 전체를 지운다 |
| 2 | `living-view-plan80`(PR 1 브랜치 위) | plan80 01, 02 | `tasks/plan80-follow-up/phase-01.md`, `phase-02.md` 를 지우고 `index.json` 을 phase 03 하나로 고친다. README 는 남는다 |
| 3 | `living-view-plan81`(PR 2 브랜치 위) | plan81 01, 02 | `tasks/plan81-living-view/phase-01.md`, `phase-02.md` 를 지우고 `index.json` 을 phase 03 하나로 고친다. README 는 남는다 |
| 4 | `living-view-plan81-final`(PR 3 브랜치 위) | plan80 03, plan81 03 | `tasks/plan80-follow-up/`, `tasks/plan81-living-view/` 전체를 지운다. 이 PR 이 `Closes #160`, `Closes #161` 을 적는다 |

- **이 plan 은 plan78 의 코드에 기대지 않는다.** 판정 응답의 `sources[].ref` 는 문맥 묶음의 참조와 같은 형식(`execution:<번호>` 같은 글)을 쓰지만, 문맥 묶음의 타입을 import 하지 않는다. plan78 은 이 네 PR 과 따로 간다
- 마이그레이션 번호는 phase 02 의 `V71__attention_control_event.sql` 로 고정한다. V69 와 V70 은 다른 작업이 쓴다. 머지 직전 main 과 겹치면 다음 번호로 옮기고 변경 파일 표도 같은 커밋에서 고친다
- plan80 phase 02 가 `attention.application.AttentionCandidates` 구현을 하나 더한다. plan80 phase 01 은 `followup` 패키지만 만들어 이 plan 과 겹치지 않는다
- plan81 은 phase 01 과 02 의 API 를 읽는다

## 계획서 삭제와 「아직 구현 전」 표시

- phase 02 가 `docs/backend/schema/attention.md` 의 `attention_control`, `attention_event` 절의 「아직 구현 전이다」 와 `docs/backend/schema/README.md` 의 표시를 `follow_up` 만 남게 고친다
- phase 03 이 ADR-072 의 `status`, `docs/adr/INDEX.md` 의 ADR-072 줄, `docs/code-architecture.md` 의 `attention` 줄의 「아직 구현 전」 을 지운다
- phase 03 은 `docs/backend/attention.md` 와 `docs/README.md` 의 `backend/attention.md` 줄에 할 일 후보의 「아직 구현 전」 만 남긴다. 그 표시는 plan80 phase 02 가 지운다
- phase 03 은 `docs/prd.md` 「답하는 비서에서 먼저 챙기는 비서로」 표의 ADR-072 줄을 「범위와 확인 방법」 표로 옮긴다
- `docs/adr/ADR-074-…` 의 「아직 구현 전이다」 는 plan81 이 지운다. 이 plan 은 건드리지 않는다
- 이 디렉터리는 PR 1 의 `build-with-teams` 마감 단계가 지운다. phase 는 `tasks/` 를 고치지 않는다

## 모든 phase 에 걸리는 규칙

- 공개 저장소다. 루트 `AGENTS.md` 의 「공개 저장소」 를 지킨다. 테스트와 문서는 지어낸 제목(「주간 장보기 목록 정리」 같은 것)만 쓴다
- **판정은 보이기만 한다.** `attention` 패키지는 Hermes 를 부르지 않고, 실행이나 커넥터 호출을 시작하지 않고, 다른 패키지의 기록을 고치지 않는다. 고치는 동작은 각 패키지의 기존 API 가 한다
- **응답에 오류 코드, 모델, 토큰, 금액을 싣지 않는다.** 일반 경로라 역할과 상관없이 뺀다(ADR-063). DTO 에 그 칸을 두지 않는다
- **로그에는 사용자 번호, 카드 열쇠, 개수만 낸다.** 항목 제목, 대화 제목, `itemKey` 를 로그에 내지 않는다
- **웹 알림(ADR-070, `notification` 패키지)의 `notification` 표를 읽거나 쓰지 않는다.** 지금 화면에서 승인을 처리해도 알림의 읽음 상태를 바꾸지 않는다(`docs/backend/attention.md` 「웹 알림과의 경계」)
- 다른 패키지의 기록은 그 패키지의 `application` 에 읽기 메서드를 두고 읽는다. `attention` 이 다른 패키지의 `infra` 를 바로 import 하지 않는다. phase 01 이 이 규칙을 `docs/backend/attention.md` 「패키지」 에 적는다
- 저장소에 메서드를 더하면 `RepositoryQueryMysqlTest` 가 실제 MySQL 에서 실행한다. 인자 타입 `Long`, `Instant`, `UUID`, `Collection`, enum 은 `backend/src/test/java/com/bifos/assistant/testsupport/RepositoryQuerySweep.java` 가 이미 만든다. 그 파일은 고치지 않는다
- backend 의 새 코드는 `backend/AGENTS.md` 를 지킨다. 로거는 `@Slf4j`, 시각은 주입받은 `Clock`, 저장하는 enum 은 `attention.domain.type`, 저장하지 않는 값은 `attention.application.model`, 요청과 응답 record 는 `AttentionDtos.java` 하나, 테스트 메서드에는 한국어 `@DisplayName`
- 기능 변경과 포맷(`./gradlew spotlessApply`)은 다른 커밋이다
- 주석과 Javadoc 은 한국어로 쓴다. 용어는 루트 `AGENTS.md` 의 「용어」 표를 따른다(실행 트리, 루트, 할 일, 지금 화면)

## 범위 밖

- 지금 화면과 사이드바 건수, 새 대화 화면의 한 줄(plan81)
- 할 일의 표와 후보(plan80)
- 문맥 묶음(plan78)
- 화면 밖 채널(Discord, Web Push). ADR-072 의 열린 질문이다
- 집중 모드처럼 알리기를 끄는 설정
- 결과 전달 상태의 저장과 다시 전달. ADR-075 와 `chat` 패키지가 갖는다
