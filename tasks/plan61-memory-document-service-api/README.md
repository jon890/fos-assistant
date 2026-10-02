# plan61 문서 API 와 서비스 토큰

사용자가 `USER` 범위의 문서를 쓰고 고치는 API, 서비스 토큰, 다른 서비스가 문서를 읽는 API 를 만든다. backend 와 e2e 만 바꾼다.
결정과 계약은 아래 두 문서에 있다.

- `docs/adr/ADR-056-다른-서비스는-사용자에-묶인-서비스-토큰으로-문서를-읽기만-한다.md`
- `docs/adr/ADR-057-문서는-사람이-화면에서-직접-쓰고-고친다.md`

민감 본문 암호화(ADR-055)는 이미 main 에 있다.
그 구현이 만든 `MemoryContentCipher`, `StoredContent`, `MemoryService.contentOf`, `Memory.sealed()`, `ErrorCode.MEMORY_ENCRYPTION_UNAVAILABLE`, 마이그레이션 V53 이 없으면 `PHASE_BLOCKED: 민감 본문 암호화가 없다` 를 출력하고 멈춘다.
화면은 plan62-memory-document-screen 이 만든다.

## PR 과 계획서 삭제

- plan 하나를 PR 하나로 올린다. 그 plan 의 계획서 디렉터리는 그 PR 의 마감 단계에서 지운다. phase 는 `tasks/` 를 바꾸지 않는다
- 이 계획서와 ADR-056, ADR-057, ADR-058 은 구현보다 먼저 main 에 들어와 있다. 서로 기대는 구현 PR 이 여럿이라 공통 계약을 먼저 정했다(루트 `AGENTS.md` 의 「머지는 PR 로 한다」 가 그 예외를 정한다). 그 ADR 의 `status` 와 `docs/adr/INDEX.md` 는 「아직 구현 전이다」 로 적혀 있고, 구현한 plan 의 마지막 phase 가 그 글을 지운다
- 순서는 문서 API 와 서비스 토큰(plan61), 화면(plan62), 기존 지식 이관(plan63)이다. 앞의 것이 main 에 머지된 뒤에 다음 것을 시작한다

## 모든 phase 에 걸리는 규칙

- 공개 저장소다. 루트 `AGENTS.md` 의 「공개 저장소」 를 지킨다
- **민감 정보의 실제 값을 테스트와 문서에 적지 않는다.** 문서 본문은 `평문-표식-7391` 같은 지어낸 글을, 문서 이름은 `application-profile` 을 쓴다
- 서비스 토큰의 원문과 해시, 문서의 본문을 로그에 남기지 않는다. 로그에는 사용자 번호, 토큰 번호, collection, 문서 이름, 판 번호만 적는다
- backend 의 새 코드는 `backend/AGENTS.md` 의 규칙을 지키고 기준 파일에 기대지 않는다. 로거는 `@Slf4j`, 시각은 주입받은 `Clock`, 해시는 `shared.util.Sha256`, `@Enumerated` 의 enum 은 `domain.type`, 요청과 응답 record 는 `MemoryDtos.java`, 테스트에는 한국어 `@DisplayName`
- `shared` 패키지가 `memory` 를 import 하지 않게 한다(`ArchitectureRules.SHARED_DOES_NOT_DEPEND_ON_DOMAINS`). `SecurityConfig` 와 `ControlPlaneJwtFilter` 에는 경로 글자만 더한다
- **`memory` 가 `people` 과 `user` 를 import 하지 않게 한다.** 두 패키지에서 `memory` 로 닿는 길이 이미 있어 새 간선은 순환이 되고 `ArchitectureRulesTest` 가 실패한다. 주인의 허용 여부와 사용자를 끈 사건은 `shared.auth` 의 타입으로 주고받는다(phase 02, 03)
- 기능 변경과 포맷을 한 커밋에 섞지 않는다. 포맷이 필요하면 `scripts/quality.sh fix` 가 바꾼 것을 따로 커밋한다
- Flyway 번호는 이 계획이 V54 를 쓴다. `origin/main` 을 합칠 때 번호가 겹치면 main 의 최신 다음 번호로 옮긴다
- 주석과 Javadoc 은 한국어로 쓴다

## 범위 밖

- 그룹 공용 문서, 판 이력 조회, 제목 고치기
- 관리자가 에이전트의 collection 과 민감 허용을 고치는 경로. ADR-056 의 「대안 기각」 에 까닭이 있다
- 서비스 토큰으로 쓰는 길. 기존 지식 저장소의 이관도 이 토큰을 쓰지 않는다(ADR-058)
- 만료 없는 서비스 토큰, 관리자가 남의 토큰을 보거나 폐기하는 경로
- 민감 본문의 완전 삭제
