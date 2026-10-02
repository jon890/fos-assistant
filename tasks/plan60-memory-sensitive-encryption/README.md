# plan60 민감 Memory 본문 암호화

민감(`SENSITIVE`) Memory 의 본문을 저장할 때 암호화한다. `memory_revision` 에 남는 본문도 같다.
결정과 계약은 `docs/adr/ADR-054-민감-memory-본문은-저장할-때-암호화하고-key-는-환경-변수로-받는다.md` 에 있다.

세 plan 가운데 첫째다. 순서대로 구현하고 plan 마다 PR 하나로 올린다.

| 순서 | plan | 내용 |
| --- | --- | --- |
| 1 | plan60-memory-sensitive-encryption | 민감 본문 암호화. backend 만 바꾼다 |
| 2 | plan61-memory-document-service-api | 문서를 쓰고 고치는 API, 서비스 토큰, 다른 서비스가 문서를 읽는 API |
| 3 | plan62-memory-document-screen | `/memory` 화면의 문서 폼과 서비스 토큰 패널 |

이 plan 이 머지되기 전에는 뒤의 두 plan 을 시작하지 않는다. 민감 문서를 평문으로 저장하는 구간이 생긴다.

## PR 과 계획서 삭제

- plan 하나를 PR 하나로 올린다. 그 plan 의 계획서 디렉터리는 그 PR 의 마감 단계에서 지운다. phase 는 `tasks/` 를 바꾸지 않는다
- 세 plan 의 계획서와 ADR-054, ADR-055, ADR-056 초안은 한 브랜치에서 함께 썼다. 먼저 올리는 PR 에 뒤 plan 의 계획서와 아직 구현하지 않은 ADR 이 함께 실린다. 그 ADR 의 `status` 와 `docs/adr/INDEX.md` 는 「아직 구현 전이다」 로 적혀 있고, 구현한 plan 의 마지막 phase 가 그 글을 지운다

## 모든 phase 에 걸리는 규칙

- 공개 저장소다. 루트 `AGENTS.md` 의 「공개 저장소」 를 지킨다. 실제 key 값과 key 를 두는 자리를 적지 않는다
- **민감 정보의 실제 값을 테스트와 문서에 적지 않는다.** 테스트 본문은 `평문-표식-7391` 같은 지어낸 글을 쓴다
- 검사용 key 는 `test-1:MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=` 하나만 쓴다. 글자 `0123456789abcdef0123456789abcdef` 의 base64 이고 운영 값이 아니다
- backend 의 새 코드는 `backend/AGENTS.md` 의 규칙을 지키고 기준 파일에 기대지 않는다. 로거는 `@Slf4j`, 시각은 주입받은 `Clock`, 설정 record 에는 `@Validated`, 테스트에는 한국어 `@DisplayName`
- 본문과 key 를 로그에 남기지 않는다. 로그에는 항목 번호와 key id 만 적는다
- 기능 변경과 포맷을 한 커밋에 섞지 않는다. 포맷이 필요하면 `scripts/quality.sh fix` 가 바꾼 것을 따로 커밋한다
- Flyway 번호는 이 계획이 V48 을 쓴다. `origin/main` 을 합칠 때 번호가 겹치면 main 의 최신 다음 번호로 옮긴다
- 주석과 Javadoc 은 한국어로 쓴다

## 범위 밖

- 민감 본문의 완전 삭제. ADR-054 의 「다음」 에 까닭이 있다
- 옛 key 로 쓴 줄을 새 key 로 다시 쓰는 명령
- 민감 항목을 만드는 화면과 API. plan61 과 plan62 가 만든다
