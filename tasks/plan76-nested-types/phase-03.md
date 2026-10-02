# Phase 03. 결과물 store 와 fetcher 의 중첩 타입 여덟을 파일로 뺀다

**Execution profile**: standard

## 목표

`chat.infra.ArtifactSourceFetcher` 의 중첩 타입 다섯과 `chat.infra.ArtifactStore` 의 중첩 타입 셋을 같은 패키지의 파일로 뺀다. 기준이 9 줄에서 1 줄로 준다.

**범위 외**: 내려받기의 검사(URL, DNS, 제한 시간, 크기)와 파일을 쓰고 지우는 규칙.

## 컨텍스트

- 규칙은 `backend/src/test/java/com/bifos/assistant/architecture/ArchitectureRules.java` 의 `SERVICES_DO_NOT_EXPOSE_NESTED_TYPES` 다. 서비스(`@Service`, `@Component`, `@Repository`)와 `infra` 안의 중첩 타입은 `private` 이어야 한다. 최상위 타입은 대상이 아니다. 익명 클래스와 지역 클래스도 대상이 아니다.
- 기준 파일은 `backend/config/archunit/store/48448fa9-9176-4cf5-bcc0-f28f044ecc43` 다. 손으로 고치지 않고 아래 명령으로 줄인다. 절차는 `docs/backend/quality.md` 의 「구조 규칙의 기준 파일」 에 있다.
- `application` 과 `domain` 은 타입 하나에 파일 하나다(`backend/AGENTS.md`).
- **동작을 바꾸지 않는다.** 타입의 칸과 메서드 본문은 그대로다. 바뀌는 것은 타입이 놓인 파일과 이름, 그리고 바깥 클래스가 쓰던 멤버의 접근 수준뿐이다. `ArchitectureRules.java` 를 고치지 않는다.
- 최상위 패키지 사이에 새 간선을 만들지 않는다. 타입은 지금 바깥 클래스와 같은 패키지에 둔다. `TOP_LEVEL_PACKAGES_FREE_OF_CYCLES` 와 `TOP_LEVEL_PACKAGES_FOLLOW_LAYER_ORDER` 와 `LAYER_DIRECTION` 의 기준은 0 줄이고 그대로여야 한다.
- 포맷은 이 phase 에서 돌리지 않는다. 이 phase 커밋 뒤 team-lead 가 `./gradlew spotlessApply` 결과를 별도 커밋으로 낸다. import 순서를 손으로 정렬하지 않는다. BSD `sed` 는 `\b` 를 모른다. 여러 파일의 이름을 바꿀 때는 `perl -pi -e` 를 쓴다.
- 주석과 Javadoc 은 한국어로 쓴다. 테스트 메서드는 영문 camelCase 이름과 한국어 `@DisplayName` 을 갖는다. `gradlew` 는 `backend/` 안에 있다.
- 바깥에서 쓰는 운영 코드는 `chat/application/ArtifactService.java`(`FoundFile`)와 `chat/application/ArtifactCleaner.java`(`Removed`)다. fetcher 의 다섯은 테스트만 쓴다: `chat/ArtifactSourceFetcherTest.java`, `chat/ArtifactWriteServiceTest.java`, `chat/infra/ArtifactSourceTlsTest.java`. store 의 둘은 `chat/ArtifactTest.java` 도 쓴다.
- 중첩 타입이 바깥 클래스의 `private` 멤버(상수, 정적 메서드)를 쓰거나 바깥 클래스가 중첩 타입의 `private` 멤버를 쓰는 곳이 있으면, 그 멤버의 `private` 만 지워 패키지 전용으로 낮춘다. 본문은 바꾸지 않는다. 낮춘 멤버를 회신에 적는다.

**근거 문서**: `docs/adr/ADR-042-코드-품질-규칙은-도구-설정이-갖고-기존-위반은-기준-파일에-둔다.md`, `docs/backend/quality.md` 의 「구조 규칙의 기준 파일」, `backend/AGENTS.md` 의 「데이터 클래스는 컨트롤러 안에 두지 않는다」

## 의도 메모

- 이름에 `ArtifactSource` 와 `Artifact` 를 붙인다. `Response`, `Transport`, `Removed` 같은 이름은 최상위 타입으로는 뜻이 넓다.
- 테스트만 쓰는 타입도 `private` 으로 낮추지 않는다. 다른 패키지의 테스트(`chat/ArtifactSourceFetcherTest`)가 생성자 인자로 넘긴다.

## 작업 항목

### 1. 중첩 타입을 파일로 뺀다

| 지금 | 옮긴 뒤 (`backend/src/main/java/com/bifos/assistant/chat/infra/` 아래) |
| --- | --- |
| `ArtifactSourceFetcher.DnsResolver` | `ArtifactSourceDnsResolver.java` |
| `ArtifactSourceFetcher.Transport` | `ArtifactSourceTransport.java` |
| `ArtifactSourceFetcher.Response` | `ArtifactSourceResponse.java` |
| `ArtifactSourceFetcher.Cancellation` | `ArtifactSourceCancellation.java` |
| `ArtifactSourceFetcher.SocketTransport` | `ArtifactSourceSocketTransport.java` |
| `ArtifactStore.FoundFile` | `ArtifactFoundFile.java` |
| `ArtifactStore.Removed` | `ArtifactRemoved.java` |
| `ArtifactStore.AtomicMover` | `ArtifactAtomicMover.java` |

접근 수준은 지금과 같게 둔다. `AtomicMover` 는 패키지 전용 인터페이스라 옮긴 뒤에도 패키지 전용이다. 나머지는 `public` 이다.

타입의 Javadoc 은 함께 옮긴다. 본문은 바꾸지 않는다.

### 2. 참조를 고친다

`backend/src/main/java` 와 `backend/src/test/java` 에서 `바깥.중첩` 으로 쓰던 이름과 그 import 를 새 이름으로 바꾼다. 바깥 클래스 안에서 단순 이름으로 쓰던 곳도 새 이름으로 바꾼다.

`./gradlew compileJava compileTestJava` 가 통과해야 한다.

### 3. 이 phase 를 검증하는 테스트

새 테스트를 만들지 않는다. `backend/src/test/java/com/bifos/assistant/chat/ArtifactSourceFetcherTest.java`, `backend/src/test/java/com/bifos/assistant/chat/infra/ArtifactSourceTlsTest.java`, `backend/src/test/java/com/bifos/assistant/chat/ArtifactTest.java`, `backend/src/test/java/com/bifos/assistant/chat/infra/ArtifactStoreWriteTest.java` 가 이 타입들로 정상 경로(허용 호스트의 이미지를 받는다, 바뀐 HTML 을 찾는다)와 실패 경로(막힌 주소와 제한 시간 초과를 거절한다, 원자적 교체가 실패하면 대체 경로로 쓴다)를 단언한다. 타입 이름만 고치고 단언은 바꾸지 않는다.

### 4. 기준 파일을 줄인다

```bash
# cwd: backend/
./gradlew archTest --rerun -Parchunit.freeze.store.default.allowStoreUpdate=true
```

9 줄에서 1 줄이 된다. 남는 하나는 `AgentRunner$Run` 이다.
이와 다르거나 다른 규칙의 새 위반으로 실패하면 다시 얼리지 말고 보고한다.

## 검증

```bash
# cwd: backend/
./gradlew test
./gradlew checkstyleMain checkstyleTest
test "$(grep -c "" config/archunit/store/48448fa9-9176-4cf5-bcc0-f28f044ecc43)" -eq 1
! grep -n "Artifact" config/archunit/store/48448fa9-9176-4cf5-bcc0-f28f044ecc43
! grep -rnE "ArtifactSourceFetcher\.(Cancellation|DnsResolver|Response|SocketTransport|Transport)|ArtifactStore\.(AtomicMover|FoundFile|Removed)" src
git diff --exit-code -- config/archunit/store ':!config/archunit/store/48448fa9-9176-4cf5-bcc0-f28f044ecc43'
```

```bash
# cwd: 저장소 root
node test/e2e/run.ts
```

모두 종료 코드 0 이어야 한다.

## 변경 파일

| 파일 | 변경 |
|---|---|
| `backend/src/main/java/com/bifos/assistant/chat/infra/ArtifactSourceDnsResolver.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/chat/infra/ArtifactSourceTransport.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/chat/infra/ArtifactSourceResponse.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/chat/infra/ArtifactSourceCancellation.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/chat/infra/ArtifactSourceSocketTransport.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/chat/infra/ArtifactFoundFile.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/chat/infra/ArtifactRemoved.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/chat/infra/ArtifactAtomicMover.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/chat/infra/ArtifactSourceFetcher.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/chat/infra/ArtifactStore.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/chat/application/ArtifactService.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/chat/application/ArtifactCleaner.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/chat/**/*.java` | 수정 |
| `backend/config/archunit/store/48448fa9-9176-4cf5-bcc0-f28f044ecc43` | 수정 |
