# plan79 먼저 알리기의 판정

#160 의 먼저 알리기 판정과 지금 화면이 읽는 API 를 backend 에 만든다.
결정은 `docs/adr/ADR-072-먼저-알리기의-기본값은-알리지-않음이고-control-plane-기록에서-정한-신호만-화면-안에-올린다.md` 와 `docs/adr/ADR-074-지금-화면은-원래-기록을-읽어-만든-view-이고-정해진-카드-넷만-그린다.md` 에 있다.
계약은 `docs/backend/attention.md`, 표는 `docs/backend/schema/attention.md` 가 갖는다.

## 이 plan 이 만드는 것

| phase | 만드는 것 | 시작 조건 |
| --- | --- | --- |
| 01 | 새 최상위 패키지 `attention`, 읽을 때 계산하는 판정, `GET /api/v1/attention`, `GET /api/v1/attention/summary` | 없다 |
| 02 | 숨기기, 미루기, 되돌리기, 지표 사건, 관리자 지표, 사건 보관 기간 정리 | phase 01 |
| 03 | 결과 전달 실패 후보 `DELIVERY_FAILED` | #162 가 main 에 들어왔다 |

화면은 이 plan 이 만들지 않는다. plan81 이 이 API 를 읽어 그린다.
할 일(`follow_up`)의 후보 `FOLLOW_UP_PROPOSED`, `FOLLOW_UP_OPEN` 은 plan80 이 더한다.

## 다른 plan 과의 순서

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

- **이 plan 은 plan78 의 코드에 기대지 않는다.** 판정 응답의 `sources[].ref` 는 문맥 묶음의 참조와 같은 형식(`execution:<번호>` 같은 글)을 쓰지만, plan78 의 타입을 import 하지 않는다. plan78 보다 먼저 머지돼도 동작한다
- phase 02 의 마이그레이션 번호는 자리표시다. 구현할 때 main 의 마지막 다음 번호를 쓰고, phase 02 의 「변경 파일」 표도 같은 커밋에서 고친다
- plan80 phase 02 가 `AttentionCandidates` 구현을 하나 더한다. 그래서 plan80 phase 02 는 이 plan 의 phase 01 뒤다. plan80 phase 01 은 `followup` 패키지만 만들어 이 plan 과 겹치지 않는다
- plan81 은 phase 01 과 02 의 API 를 읽는다

## PR 과 계획서 삭제

- phase 01 과 02 는 한 PR 로 낸다. phase 03 은 #162 가 머지된 뒤 따로 PR 을 낸다
- **phase 03 이 막혀 있으면 phase 01, 02 만 먼저 낸다.** 그 PR 은 phase 02 의 작업 항목대로 ADR-072 의 `status` 와 `docs/adr/INDEX.md` 의 그 줄, `docs/backend/attention.md` 의 구현 전 단락, `docs/code-architecture.md` 의 `attention` 줄을 「`DELIVERY_FAILED` 는 아직 구현 전이다」 로 바꾼다. 이 디렉터리에서는 `phase-01.md`, `phase-02.md` 를 지우고 `index.json` 을 phase 03 하나로 고친다
- phase 03 을 낸 PR 이 남은 「아직 구현 전」 표시를 모두 지우고 이 디렉터리를 지운다
- phase 03 은 `docs/README.md` 의 `backend/attention.md` 줄 끝 「아직 구현 전이다」 도 지운다
- `docs/adr/ADR-074-…` 의 「아직 구현 전이다」 는 plan81 이 지운다. 이 plan 은 건드리지 않는다

## 모든 phase 에 걸리는 규칙

- 공개 저장소다. 루트 `AGENTS.md` 의 「공개 저장소」 를 지킨다. 테스트와 문서는 지어낸 제목(「주간 장보기 목록 정리」 같은 것)만 쓴다
- **판정은 보이기만 한다.** `attention` 패키지는 Hermes 를 부르지 않고, 실행이나 커넥터 호출을 시작하지 않고, 다른 패키지의 기록을 고치지 않는다. 고치는 동작은 각 패키지의 기존 API 가 한다
- **응답에 오류 코드, 모델, 토큰, 금액을 싣지 않는다.** 일반 경로라 역할과 상관없이 뺀다(ADR-063). DTO 에 그 칸을 두지 않는다
- **로그에는 사용자 번호, 카드 열쇠, 개수만 낸다.** 항목 제목, 대화 제목, `itemKey` 를 로그에 내지 않는다
- **웹 알림(ADR-070, `notification` 패키지)의 `notification` 표를 읽거나 쓰지 않는다.** 지금 화면에서 승인을 처리해도 알림의 읽음 상태를 바꾸지 않는다(`docs/backend/attention.md` 「웹 알림과의 경계」)
- 다른 패키지의 기록은 그 패키지의 `application` 에 읽기 메서드를 두고 읽는다. `attention` 이 다른 패키지의 `infra` 를 바로 import 하지 않는다
- 저장소에 메서드를 더하면 `RepositoryQueryMysqlTest` 가 실제 MySQL 에서 실행한다. 인자 타입을 만들지 못해 실패하면 `backend/src/test/java/com/bifos/assistant/testsupport/RepositoryQuerySweep.java` 에 그 타입의 값을 더한다. 건너뛰게 하지 않는다
- backend 의 새 코드는 `backend/AGENTS.md` 를 지킨다. 로거는 `@Slf4j`, 시각은 주입받은 `Clock`, 저장하는 enum 은 `attention.domain.type`, 저장하지 않는 값은 `attention.application.model`, 요청과 응답 record 는 `AttentionDtos.java` 하나, 테스트 메서드에는 한국어 `@DisplayName`
- 기능 변경과 포맷(`./gradlew spotlessApply`)은 다른 커밋이다
- 주석과 Javadoc 은 한국어로 쓴다. 용어는 루트 `AGENTS.md` 의 「용어」 표를 따른다(실행 트리, 루트, 할 일, 지금 화면)

## 범위 밖

- 지금 화면과 사이드바 건수, 새 대화 화면의 한 줄(plan81)
- 할 일의 표와 후보(plan80)
- 문맥 묶음(plan78)
- 화면 밖 채널(Discord, Web Push). ADR-072 의 열린 질문이다
- 집중 모드처럼 알리기를 끄는 설정
- 결과 전달 실패 상태의 저장과 다시 전하기(#162)
