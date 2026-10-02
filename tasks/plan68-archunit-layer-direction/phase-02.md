# Phase 02. SkillBundle 과 SkillFile 을 skill.domain 으로 옮긴다

**Execution profile**: standard

## 목표

`skill.infra.SkillStore` 가 `skill.application` 의 `SkillBundle` 과 `SkillFile` 을 쓰는 위반을 없앤다.
두 record 를 `skill.domain` 으로 옮긴다. `LAYER_DIRECTION` 의 기준이 28 줄에서 11 줄로 준다.

**범위 외**: 두 record 의 칸과 생성자. `SkillStore` 의 메서드. `skill.application` 의 다른 모델(`SkillDetail`, `SkillFileInfo`, `SkillFileInput` 등).

## 컨텍스트

- 규칙은 `backend/src/test/java/com/bifos/assistant/architecture/ArchitectureRules.java` 의 `LAYER_DIRECTION` 이다. `infra` 는 `application` 만 쓸 수 있고, `infra` 가 `application` 을 쓰는 것과 `presentation` 이 `infra` 를 쓰는 것이 위반이다. `domain` 은 어느 층이나 쓴다.
- 기준 파일은 `backend/config/archunit/store/2790ecd4-faaa-4952-b703-a028b68814d5` 다. 손으로 고치지 않고 아래 명령으로 줄인다. 절차는 `docs/backend/quality.md` 의 「구조 규칙의 기준 파일」 에 있다.
- **동작을 바꾸지 않는다.** 응답, 저장되는 값, 설정 이름, 트랜잭션이 열리는 자리가 그대로여야 한다. `ArchitectureRules.java` 를 고치지 않는다.
- 포맷은 이 phase 에서 돌리지 않는다. `spotlessApply` 결과는 team-lead 가 따로 커밋한다. import 순서를 손으로 정렬하지 않는다.
- 주석과 Javadoc 은 한국어로 쓴다. 테스트 메서드는 영문 camelCase 이름과 한국어 `@DisplayName` 을 갖는다. `gradlew` 는 `backend/` 안에 있다.
- BSD `sed` 는 `\b` 를 모른다. 여러 파일의 이름을 바꿀 때는 `perl -pi -e` 를 쓴다.
- `backend/src/main/java/com/bifos/assistant/skill/application/SkillBundle.java` 는 스킬 하나의 파일 전체를 담는 record 이고, `SkillFile.java` 는 참고 파일 하나다. 둘 다 다른 타입을 import 하지 않는다(`SkillBundle` 이 `SkillFile` 과 `java.util.List` 만 쓴다).
- 운영 코드에서 쓰는 곳은 `skill/infra/SkillStore.java` 와 `skill/application/SkillService.java` 다.
- `skill/domain` 에는 이미 `ExecutionSkillUse`, `SkillUseCount`, `SkillUseOccurrence` 가 있다. 엔티티가 아닌 값 record 도 `domain` 에 둔다.

**근거 문서**: `docs/backend/packages.md` 의 「패키지와 책임」, `docs/backend/skill.md`, `docs/adr/ADR-034-올린-스킬은-control-plane-이-버전-디렉터리에-쓰고-hermes-는-읽기만-한다.md`

## 의도 메모

- `SkillStore` 를 `application` 으로 올리지 않는다. 파일 시스템을 다루는 클래스이고 `agent` 가 이 클래스를 쓴다.
- `SkillStore` 가 자기만의 record 를 따로 갖고 서비스가 변환하는 방법은 쓰지 않는다. 같은 모양의 타입이 둘이 된다.

## 작업 항목

### 1. `git mv` 로 둘을 옮기고 `package` 줄을 고친다

`skill/application/SkillBundle.java` 를 `skill/domain/SkillBundle.java` 로, `skill/application/SkillFile.java` 를 `skill/domain/SkillFile.java` 로 옮긴다.

### 2. 참조를 고친다

`backend/src/main/java` 와 `backend/src/test/java` 에서 `com.bifos.assistant.skill.application.SkillBundle` 과 `com.bifos.assistant.skill.application.SkillFile` 의 import 를 새 패키지로 바꾼다.
`SkillFileInfo`, `SkillFileInput` 처럼 이름이 `SkillFile` 로 시작하는 다른 타입을 건드리지 않는다. 패턴 끝을 `;` 로 고정한다.
같은 패키지라 import 없이 쓰던 `skill.application` 의 클래스에 import 를 더한다. `./gradlew compileJava compileTestJava` 가 통과할 때까지 고친다.
`docs` 에 옛 위치가 적혀 있으면 고친다. `git grep -n "application[./]\(SkillBundle\|SkillFile\)\b" -- docs backend/src AGENTS.md backend/AGENTS.md` 가 0 건이어야 한다.

### 3. 기준 파일을 줄인다

```bash
# cwd: backend/
./gradlew archTest --rerun -Parchunit.freeze.store.default.allowStoreUpdate=true
```

기준을 줄이는 명령이 **다른 규칙**의 새 위반으로 실패하면 다시 얼리지 말고 기준 파일을 그대로 둔 채 어느 규칙의 어느 줄인지 보고한다.

### 4. 이 phase 를 검증하는 테스트

`backend/src/test/java/com/bifos/assistant/skill/SkillStoreTest.java` 가 `SkillBundle` 로 버전을 쓰고 다시 읽는다. 이 테스트의 import 를 새 패키지로 고치고 단언은 바꾸지 않는다.
정상: 쓴 묶음을 다시 읽으면 이름, `SKILL.md`, 참고 파일이 같다. 실패: 이 테스트가 이미 가진 거절 경로(잘못된 경로나 이름)가 그대로 통과한다.

## 검증

```bash
# cwd: backend/
./gradlew test
./gradlew checkstyleMain checkstyleTest
test "$(wc -l < config/archunit/store/2790ecd4-faaa-4952-b703-a028b68814d5)" -eq 11
! grep -n "SkillStore" config/archunit/store/2790ecd4-faaa-4952-b703-a028b68814d5
! grep -rnE "skill\.application\.(SkillBundle|SkillFile);" src
```

모두 종료 코드 0 이어야 한다.

## 변경 파일

| 파일 | 변경 |
|---|---|
| `backend/src/main/java/com/bifos/assistant/skill/application/SkillBundle.java` | 삭제 |
| `backend/src/main/java/com/bifos/assistant/skill/application/SkillFile.java` | 삭제 |
| `backend/src/main/java/com/bifos/assistant/skill/domain/SkillBundle.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/skill/domain/SkillFile.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/skill/infra/SkillStore.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/skill/application/SkillService.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/skill/SkillStoreTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/**/*.java` | 수정 |
| `backend/config/archunit/store/2790ecd4-faaa-4952-b703-a028b68814d5` | 수정 |
