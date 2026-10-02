# Phase 01. infra 가 쓰는 설정 record 넷을 infra 로 옮긴다

**Execution profile**: standard

## 목표

`infra` 의 store 와 fetcher 가 `application` 의 `@ConfigurationProperties` record 를 쓰는 위반을 없앤다.
record 넷을 그것을 쓰는 `infra` 패키지로 옮긴다. `LAYER_DIRECTION` 의 기준이 46 줄에서 28 줄로 준다.

**범위 외**: record 의 칸과 기본값, `@ConfigurationProperties` 의 prefix. 설정 이름이 바뀌면 운영 설정이 읽히지 않는다. `SkillStore` 가 쓰는 `SkillBundle` 과 `SkillFile` 은 다음 phase 가 맡는다.

## 컨텍스트

- 규칙은 `backend/src/test/java/com/bifos/assistant/architecture/ArchitectureRules.java` 의 `LAYER_DIRECTION` 이다. `infra` 는 `application` 만 쓸 수 있고, `infra` 가 `application` 을 쓰는 것과 `presentation` 이 `infra` 를 쓰는 것이 위반이다. `domain` 은 어느 층이나 쓴다.
- 기준 파일은 `backend/config/archunit/store/2790ecd4-faaa-4952-b703-a028b68814d5` 다. 손으로 고치지 않고 아래 명령으로 줄인다. 절차는 `docs/backend/quality.md` 의 「구조 규칙의 기준 파일」 에 있다.
- **동작을 바꾸지 않는다.** 응답, 저장되는 값, 설정 이름, 트랜잭션이 열리는 자리가 그대로여야 한다. `ArchitectureRules.java` 를 고치지 않는다.
- 포맷은 이 phase 에서 돌리지 않는다. `spotlessApply` 결과는 team-lead 가 따로 커밋한다. import 순서를 손으로 정렬하지 않는다.
- 주석과 Javadoc 은 한국어로 쓴다. 테스트 메서드는 영문 camelCase 이름과 한국어 `@DisplayName` 을 갖는다. `gradlew` 는 `backend/` 안에 있다.
- BSD `sed` 는 `\b` 를 모른다. 여러 파일의 이름을 바꿀 때는 `perl -pi -e` 를 쓴다.
- 선례: `backend/src/main/java/com/bifos/assistant/usage/infra/PricingProperties.java` 가 이미 `infra` 에 있는 설정 record 다.
- `application` 은 `infra` 를 쓸 수 있다. 옮긴 뒤 `application` 의 서비스가 `infra` 의 record 를 import 하는 것은 위반이 아니다.

옮길 것이다. 경로는 `backend/src/main/java/com/bifos/assistant/` 아래다.

| 지금 | 옮긴 뒤 | 운영 코드에서 쓰는 곳 |
| --- | --- | --- |
| `chat/application/ArtifactSourceProperties.java` | `chat/infra/ArtifactSourceProperties.java` | `chat/infra/ArtifactSourceFetcher.java` |
| `chat/application/ArtifactProperties.java` | `chat/infra/ArtifactProperties.java` | `chat/infra/ArtifactStore.java`, `chat/application/ArtifactCleaner.java` |
| `chat/application/AttachmentProperties.java` | `chat/infra/AttachmentProperties.java` | `chat/infra/AttachmentStore.java`, `chat/application/AttachmentService.java` |
| `skill/application/SkillProperties.java` | `skill/infra/SkillProperties.java` | `skill/infra/SkillStore.java`, `skill/application/SkillService.java` |

**근거 문서**: `docs/backend/packages.md` 의 「패키지와 책임」, `docs/adr/ADR-042-코드-품질-규칙은-도구-설정이-갖고-기존-위반은-기준-파일에-둔다.md`, `docs/backend/quality.md` 의 「구조 규칙의 기준 파일」

## 의도 메모

- store 가 값을 풀어 받도록 생성자를 바꾸는 방법은 쓰지 않는다. 빈을 조립하는 설정 클래스가 새로 필요하고 테스트의 생성 호출이 모두 바뀐다.
- record 를 `domain` 에 두지 않는다. 설정은 저장되는 모델이 아니다.

## 작업 항목

### 1. `git mv` 로 넷을 옮기고 `package` 줄을 고친다

위 표대로 옮긴다. 본문은 `package` 줄만 바꾼다.

### 2. 참조를 고친다

`backend/src/main/java` 와 `backend/src/test/java` 에서 옛 import 넷을 새 패키지로 바꾼다.
같은 패키지라 import 없이 쓰던 `application` 클래스에는 import 를 더하고, 옮긴 뒤 같은 패키지가 된 `infra` 클래스의 import 는 지운다.
`./gradlew compileJava compileTestJava` 가 통과할 때까지 고친다.
Javadoc 의 `{@link}` 와 `{@code}` 에 옛 전체 이름이 있으면 같이 고친다.

### 3. 문서를 고친다

`docs/backend/artifact.md` 의 표에서 `chat/application/ArtifactSourceProperties` 를 `chat/infra/ArtifactSourceProperties` 로 고친다.
`git grep -n "application/\(ArtifactSourceProperties\|ArtifactProperties\|AttachmentProperties\|SkillProperties\)\|application\.\(ArtifactSourceProperties\|ArtifactProperties\|AttachmentProperties\|SkillProperties\)" -- docs backend AGENTS.md` 가 0 건이어야 한다.

### 4. 기준 파일을 줄인다

```bash
# cwd: backend/
./gradlew archTest --rerun -Parchunit.freeze.store.default.allowStoreUpdate=true
```

기준을 줄이는 명령이 **다른 규칙**의 새 위반으로 실패하면 다시 얼리지 말고 기준 파일을 그대로 둔 채 어느 규칙의 어느 줄인지 보고한다.

### 5. 이 phase 를 검증하는 테스트

`backend/src/test/java/com/bifos/assistant/shared/ValidatedPropertiesBindingTest.java` 가 넷의 빈을 타입으로 꺼낸다. 이 테스트의 import 를 새 패키지로 고친다.
정상: 넷이 새 패키지의 타입으로 컨텍스트에서 꺼내지고 프록시가 아니다. 실패: 넷 가운데 `@Validated` 가 빠진 것이 있으면 그 타입 이름을 내며 실패한다.
설정 값이 그대로 바인딩되는지는 이 record 를 쓰는 기존 store 테스트가 확인한다.

## 검증

```bash
# cwd: backend/
./gradlew test
./gradlew checkstyleMain checkstyleTest
test "$(wc -l < config/archunit/store/2790ecd4-faaa-4952-b703-a028b68814d5)" -eq 28
! grep -n "Properties" config/archunit/store/2790ecd4-faaa-4952-b703-a028b68814d5
! grep -rnE "(chat|skill)\.application\.(ArtifactSourceProperties|ArtifactProperties|AttachmentProperties|SkillProperties)" src
```

```bash
# cwd: 저장소 root
node test/e2e/run.ts
```

모두 종료 코드 0 이어야 한다.

## 변경 파일

| 파일 | 변경 |
|---|---|
| `backend/src/main/java/com/bifos/assistant/chat/application/ArtifactSourceProperties.java` | 삭제 |
| `backend/src/main/java/com/bifos/assistant/chat/application/ArtifactProperties.java` | 삭제 |
| `backend/src/main/java/com/bifos/assistant/chat/application/AttachmentProperties.java` | 삭제 |
| `backend/src/main/java/com/bifos/assistant/skill/application/SkillProperties.java` | 삭제 |
| `backend/src/main/java/com/bifos/assistant/chat/infra/ArtifactSourceProperties.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/chat/infra/ArtifactProperties.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/chat/infra/AttachmentProperties.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/skill/infra/SkillProperties.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/**/*.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/shared/ValidatedPropertiesBindingTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/**/*.java` | 수정 |
| `docs/backend/artifact.md` | 수정 |
| `backend/config/archunit/store/2790ecd4-faaa-4952-b703-a028b68814d5` | 수정 |
