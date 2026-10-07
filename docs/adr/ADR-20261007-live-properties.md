## ADR-20261007: 운영 코드는 실행 중에 쓰는 설정을 LiveProperties 로 읽는다

- **status**: `accepted`
- Date: 2026-10-07
- **결정**: 아래 설정 묶음은 `@ConfigurationProperties` record 를 직접 주입받지 않고 `LiveProperties<T>` 에서 쓸 때마다 `current()` 로 읽는다.
  위임 깨우기, 사용자 실행 한도, 먼저 살펴보기, 위임, 추천 질문, memory 제안, autonomy, 가격표, memory 암호화, 커넥터 정책, 모델 tier 다.
  `HermesProperties` 는 `runTimeout()` 과 `pollInterval()` 을 쓰는 곳만 `LiveProperties<HermesProperties>` 로 읽는다.
  - 운영 구현은 기동 때 바인딩한 record 를 그대로 돌려준다. 값이 바뀌지 않으므로 운영 동작은 지금과 같다.
  - 기동 때 한 번 계산하던 값(가격표 파일, 암호화 key 표)은 `current()` 가 돌려준 record 가 바뀌었을 때만 다시 계산한다.
  - 서버 전체 동시 위임 한도는 기동 때 만든 `Semaphore` 대신 쓰는 수를 세고 `current().maxActive()` 와 견준다.
  - 기동 때 두 설정을 함께 검사하던 것(살펴보기 `max-duration` 이 `hermes.run-timeout` 보다 짧다)은 그대로 기동 때 검사한다.
  - 위 설정 record 를 `LiveProperties` 빈 정의 밖에서 주입받지 않는다. `runTimeout()` 과 `pollInterval()` 은 `LiveProperties` 로만 읽는다. 구조 규칙으로 확인한다.
  - 예외는 실제 Hermes 클라이언트(`HermesRunEventStream`, `HttpHermesRunsClient`)가 HTTP 클라이언트를 만들 때 읽는 시간 상한이다. 기동 때 한 번 쓰고, 통합 검사는 그 두 클라이언트를 대역으로 바꾼다.
- **맥락**:
  - [ADR-20261007 / test-context-base](ADR-20261007-test-context-base.md) 의 측정에서 검사 컨텍스트 38개의 대부분이 이 설정 값만 달랐다.
    값마다 컨텍스트를 새로 띄우면 기동이 늘고, 밀려나 닫힌 컨텍스트가 풀리지 않아 힙이 찼다.
  - 이 설정 대부분은 운영에서도 요청마다 읽는다. record 를 필드로 쥐는 대신 홀더에서 읽어도 운영 동작이 바뀌지 않는다.
  - 운영 코드에 시험용 문을 만들지 않는다는 규칙([`docs/code-architecture.md`](../code-architecture.md))이 있다. 홀더는 운영에서 값을 바꾸는 길을 열지 않는다. 바꾸는 구현은 검사 쪽에만 있다.
- **대안 기각**:
  - **설정마다 `XxxSettings` 인터페이스를 둔다.** 이름은 또렷하지만 설정 수만큼 타입과 두 구현이 늘어난다. 하는 일이 같아 일반 타입 하나로 둔다.
  - **Spring Cloud 의 `@RefreshScope` 로 다시 바인딩한다.** 새 의존이 생기고, record 로 만든 설정은 제자리에서 다시 바인딩되지 않는다. 빈을 다시 만드는 동안 그 빈을 쓰는 스레드와 경합한다.
  - **`HermesProperties` 전체를 홀더로 읽는다.** 22곳이 바뀐다. 검사가 바꾸는 값은 두 개뿐이라 그 값을 읽는 곳만 바꾼다.
  - **record 의 값을 검사가 리플렉션으로 바꾼다.** record 는 불변이고, 운영 빈의 내부를 검사가 건드리게 된다.
- **결과**:
  - 얻는 것:
    - 검사가 설정 값만 다를 때 컨텍스트를 새로 띄우지 않는다.
    - 실행 중에 쓰는 설정과 기동 때만 쓰는 설정이 코드에서 구분된다.
  - 감당할 것:
    - **새로 이 설정을 쓰는 곳은 `LiveProperties` 로 읽는다.** record 를 필드로 쥐면 검사가 바꾼 값을 보지 못해, 검사가 엉뚱하게 통과할 수 있다. 구조 규칙이 막는다.
    - **값을 한 번 읽어 오래 쥐지 않는다.** 한 요청 안에서 같은 값을 여러 번 쓰면 처음 읽은 record 를 지역 변수로 쥔다. 요청을 넘겨 쥐지 않는다.
    - cron 식, 스레드 풀 크기처럼 기동 때만 쓰는 값은 홀더로 바꿔도 소용이 없다. 그런 값은 이 결정 밖이고 test profile 의 기본값 하나로 둔다.
- **적용 범위**: `backend/src/main` 의 위 설정 묶음과 그 사용처. 검사 쪽 사용법은 [`docs/backend/testing.md`](../backend/testing.md) 가 갖는다.
