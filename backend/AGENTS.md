# backend

Control Plane 이다. Spring Boot 4 와 MySQL 8.4 를 쓴다.

저장소 전체에 걸리는 규칙은 루트 [`AGENTS.md`](../AGENTS.md) 가 갖는다.
공개 저장소에 무엇을 적지 않는지도 그 문서가 정한다.

## 패키지 배치

도메인별로 나누고 각 도메인 안은 `presentation` 에서 `application`, `domain`, `infra` 로만 흐른다.
자세한 것은 [`docs/code-architecture.md`](../docs/code-architecture.md) 에 있다.

## 기술 주의점

- **Spring Boot 4 는 Jackson 3 을 쓴다.**
  `com.fasterxml.jackson` 이 아니라 `tools.jackson` 을 import 한다.
- **`RestClient.Builder` 는 자동 구성되지 않는다.**
  `RestClient.builder()` 로 직접 만들고 timeout 을 준다.
- **`src/test/resources/application-test.yml` 은 test profile 전용이다.**
  `application.yml` 이라는 이름으로 두면 `smokeRun` 이 실제 설정 대신 이 파일을 읽는다.

## 엔티티와 마이그레이션은 따로 논다

테스트는 엔티티로 스키마를 만들고 운영은 Flyway 가 만든 스키마를 검증한다.
**그래서 둘이 어긋나도 테스트는 통과한다.**

엔티티를 바꾸면 마이그레이션도 함께 바꾸고, 배포 로그에서 `Schema validation` 을 확인한다.
실제로 `@Lob` 이 붙은 문자열이 MySQL 에서 `tinytext` 로 기대돼 기동에 실패한 적이 있다.
길이를 주지 않은 `@Lob` 문자열을 쓰지 말고 `columnDefinition` 으로 못 박는다.

이미 적용된 마이그레이션 파일을 고치지 않는다. Flyway 의 검사가 실패한다.
새 번호로 파일을 하나 더 만든다.

## 실행 기록

`agent_execution` 한 줄은 실행이 끝난 뒤가 아니라 **시작할 때** 만들어진다.
근거는 [ADR-011](../docs/adr/ADR-011-실행은-시작할-때-기록하고-끝날-때-갱신한다.md) 에 있다.

`RUNNING` 인 줄을 다루는 자리가 둘이다.

| 무엇 | 어떻게 |
| --- | --- |
| 사용량 목록 | 「도는 중」으로 보인다. 소요 시간과 금액은 비운다 |
| 월 비용 합계 | 뺀다. 빼지 않으면 「가격을 찾지 못한 실행」 으로 세어진다 |

금액을 0 으로 채우지 않는다. 0 은 공짜라는 뜻으로 읽힌다.

## 테스트

```bash
# cwd: backend/
./gradlew test
```

**`gradlew` 는 이 디렉터리 안에 있다.** 저장소 루트에서 `./gradlew` 를 부르면 없다.

`test/e2e` 는 저장소 루트에서 돌리고, 앞선 실행이 남긴 데이터에 걸린다.
`gradlew test` 를 건너뛰고 `run.ts` 만 돌리면
`this agent code is already used` 로 실패할 수 있다.

## 주석

주석과 Javadoc 을 한국어로 쓴다.
코드 식별자, 타입, 라이브러리 이름, 명령, 경로는 원문 그대로 둔다.

영어로 남아 있던 주석은 그 파일을 고칠 때 함께 옮긴다.
한 번에 전부 옮기려고 별도 커밋을 만들지 않는다.
읽는 사람이 diff 에서 무엇이 바뀌었는지 놓친다.
