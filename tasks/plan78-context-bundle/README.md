# plan78 문맥 묶음과 첫 반응 시간

#159 의 문맥 묶음 계약을 구현하고, #158 이 정한 첫 반응 시간 지표를 연다.
결정은 `docs/adr/ADR-071-여러-출처의-문맥은-항목마다-출처와-권한과-신선도를-지닌-묶음으로-조립한다.md` 에 있고,
항목의 칸과 형식과 규칙은 `docs/backend/context-bundle.md`, 지표는 `docs/model-tiers.md` 의 「첫 반응 시간」 이 갖는다.

## 이 plan 이 만드는 것

| phase | 만드는 것 | 바깥에서 보이는 변화 |
| --- | --- | --- |
| 01 | 첫 반응 시간 집계 API 와 관리자 사용량 화면의 절 | `/admin/usage` 에 날짜와 모델 단계별 중앙값과 90번째 백분위가 보인다. 묶음을 넣기 전의 기준값이 남는다 |
| 02 | `context` 패키지의 항목과 묶음 타입, Memory 를 항목으로 옮김, `toString` 가림, 같은 이름 문서의 충돌 표시, `memory_read` 사건 가드 | 충돌이 없으면 `instructions` 와 `instructions_hash` 가 지금과 같다 |
| 03 | `execution_context_source` 표와 Memory 항목 참조의 기록, 관리자 실행 상세의 출처 목록 | 실행마다 실은 Memory 항목의 참조가 남고 `/admin/executions/{id}` 에 보인다 |
| 04 | 결과 항목의 출처 머리줄과 신선도, 결과 항목 참조의 기록 | 자동 turn 의 `input` 에 `[출처: …, 끝난 시각: …]` 가 붙고 오래된 결과에 「오래됨」 이 붙는다. 결과 항목의 참조도 남는다 |

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

**지금 화면과 먼저 알리기는 이 plan 과 같은 `source` 이름과 `ref` 형식만 쓰고 이 plan 의 타입을 import 하지 않는다.** 그래서 plan79 부터 plan81 은 이 plan 을 기다리지 않는다.

phase 04 는 #162(결과 전달을 보존하고 결과만 다시 전하는 복구)가 main 에 들어온 뒤 시작한다.
#162 가 `ChatService.runDelegationResults` 와 결과 전달 경로를 함께 고치기 때문이다.
phase 01 부터 03 까지는 #162 와 겹치지 않아 먼저 할 수 있다.

## PR 과 계획서 삭제

- 네 phase 를 한 PR 로 올린다. #162 가 늦어지면 phase 01 부터 03 까지만 먼저 PR 로 올려도 된다. 그때는 이 디렉터리에서 끝난 phase 파일만 지우고 `index.json` 을 고친다
- 마지막 phase 를 끝내는 PR 이 이 디렉터리를 지운다
- 「아직 구현 전이다」 표시는 그것을 구현한 phase 가 지운다. 어느 phase 가 무엇을 지우는지는 각 phase 의 작업 항목에 적었다
  - phase 01: `docs/model-tiers.md` 「첫 반응 시간」 의 구현 전 줄
  - phase 03: `docs/backend/schema/execution.md` 의 「execution_context_source」 구현 전 줄, `docs/backend/schema/README.md` 의 `execution_context_source` 구현 전 표시
  - phase 04: ADR-071 의 `status` 와 `docs/adr/INDEX.md` 의 그 줄, `docs/README.md` 의 `backend/context-bundle.md` 줄 끝 「아직 구현 전이다」, `docs/backend/context-bundle.md` 의 구현 전 단락, `docs/code-architecture.md` 「아직 만들지 않은 것」 의 문맥 묶음 줄, `docs/backend/packages.md` 의 `context` 줄
- `docs/prd.md` 「답하는 비서에서 먼저 챙기는 비서로」 표는 plan81 이 옮긴다. 이 plan 은 건드리지 않는다

## 모든 phase 에 걸리는 규칙

- 공개 저장소다. 루트 `AGENTS.md` 의 「공개 저장소」 를 지킨다. 테스트 데이터는 지어낸 값만 쓴다(예: 「주간 회의는 화요일 10시」, `평문-표식-7391`)
- **Memory 의 제목과 본문, 결과 본문을 로그에 내지 않는다.** 로그에는 사용자 번호, 실행 번호, `source` 와 `ref`, 개수, 글자 수만 낸다
- **Memory 의 판정과 층을 바꾸지 않는다.** ADR-053 의 세 조건, 8,000자 예산, 색인 몫, 넘친 항목 통째 제외를 그대로 둔다(`docs/backend/memory.md`)
- 커넥터 에이전트의 실행에는 묶음을 주지 않는다. `ChatService` 와 `AgentRunner` 의 `connectorManaged()` 분기를 그대로 둔다
- backend 는 `backend/AGENTS.md` 를 지킨다. 요청과 응답 record 는 그 패키지의 `*Dtos.java`, 저장되는 enum 은 `<기능>.domain.type`, 테스트 메서드는 영문 camelCase 와 한국어 `@DisplayName`, 로거는 `@Slf4j`, 시각은 주입받은 `Clock`
- 층 순서(`docs/backend/packages.md`)를 지킨다. `usage` 는 `context` 와 `chat` 을 import 하지 못한다. 그래서 실행 기록에 넘기는 값은 `usage` 에 둔 타입이나 문자열이다
- web 은 `web/AGENTS.md` 를 지킨다. 화면 문구는 해요체, 색은 토큰, 내부 값(모델, 토큰, 시각 구간)은 `/admin` 에서만 그린다
- 기능 변경과 포맷(`./gradlew spotlessApply`, Prettier)을 한 커밋에 섞지 않는다
- 주석과 Javadoc 은 한국어로 쓴다

## 범위 밖

- 실행 상태와 할 일을 묶음 항목 타입으로 만드는 일. 지금 화면과 먼저 알리기는 「왜 보였는가」 의 `sources` 에 `EXECUTION_STATE`, `FOLLOW_UP` 이라는 `source` 이름과 `ref` 형식만 쓴다(`docs/backend/context-bundle.md` 「참여하는 source」)
- 커넥터를 Control Plane 이 직접 부르는 일. 하지 않는다(ADR-071 의 대안 기각)
- 뜻이 어긋나는 내용을 Control Plane 이 찾는 일. 모델이 지시대로 두 출처를 함께 말한다
- 브라우저가 그리는 시간을 재는 일(`docs/model-tiers.md` 「첫 반응 시간」)
- 결과 전달 실패와 다시 전하기. #162 가 한다
