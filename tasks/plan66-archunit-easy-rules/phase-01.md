# Phase 01. MessageDigest 호출을 Sha256 으로 모은다

**Execution profile**: fast

## 목표

`MessageDigest.getInstance` 를 `shared.util.Sha256` 밖에서 부르는 세 곳을 `Sha256` 호출로 바꿔
`MESSAGE_DIGEST_ONLY_IN_SHA256` 의 기준을 0 줄로 만든다. 해시 구현을 한 곳에 두기 위해서다.

**범위 외**: 다른 규칙의 위반. `Sha256` 에 새 메서드를 더하는 것.

## 컨텍스트

- 먼저 `tasks/plan66-archunit-easy-rules/README.md` 를 읽는다. 모든 phase 에 걸리는 규칙과 기준을 줄이는 명령이 거기 있다.
- `backend/src/main/java/com/bifos/assistant/shared/util/Sha256.java` 에 `hex16(String)` 과 `hex(String)` 이 있다.
  `hex16` 은 SHA-256 앞 16바이트를 16진수 32글자로, `hex` 는 전체를 64글자로 돌려준다. 둘 다 UTF-8 로 읽고 null 을 빈 문자열로 다룬다.
- 규칙은 `backend/src/test/java/com/bifos/assistant/architecture/ArchitectureRules.java` 의 `MESSAGE_DIGEST_ONLY_IN_SHA256` 이다.

**근거 문서**: `docs/adr/ADR-042-코드-품질-규칙은-도구-설정이-갖고-기존-위반은-기준-파일에-둔다.md`, `docs/backend/quality.md` 의 「구조 규칙의 기준 파일」

## 의도 메모

- 돌려주는 해시 값이 한 글자도 달라지면 안 된다. 토큰 해시와 위임 키는 DB 에 저장된 값과 견준다.
- `Sha256` 은 null 을 빈 문자열로 다룬다. 지금 null 에서 `NullPointerException` 이 나는 자리는 그 동작을 유지한다.

## 작업 항목

### 1. `AssembledContext.instructionsHash()` 의 변경

`backend/src/main/java/com/bifos/assistant/context/AssembledContext.java`.
null 이나 빈 문자열이면 null 을 돌려주는 앞부분은 그대로 두고, 해시 계산을 `Sha256.hex16(instructions)` 로 바꾼다.
쓰지 않게 된 `HASH_BYTES` 상수와 import 를 지운다.

### 2. `AgentTokenService.hash(String)` 의 변경

`backend/src/main/java/com/bifos/assistant/mcp/application/AgentTokenService.java`.
본문을 `Sha256.hex(Objects.requireNonNull(raw))` 로 바꾼다. `public static` 시그니처는 그대로 둔다.

### 3. `DelegationKey.of(...)` 의 변경

`backend/src/main/java/com/bifos/assistant/usage/domain/DelegationKey.java`.
`joined` 를 만드는 부분은 그대로 두고 `new DelegationKey(Sha256.hex(joined))` 로 바꾼다.

### 4. 이 phase 를 검증하는 테스트

값이 그대로인지 고정 값으로 단언한다. 고정 값은 **코드를 고치기 전에** 지금 구현을 돌려 얻는다.

- `backend/src/test/java/com/bifos/assistant/usage/DelegationKeyTest.java` 에 고정 입력 넷의 키가 64글자 고정 값과 같다는 테스트를 더한다. 이미 고정 값으로 단언하는 테스트가 있으면 더하지 않는다.
- `backend/src/test/java/com/bifos/assistant/mcp/AgentTokenServiceTest.java` 에 `AgentTokenService.hash("abc")` 가
  `ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad` 와 같다는 테스트와 null 에서 `NullPointerException` 이 난다는 테스트를 더한다.
- `backend/src/test/java/com/bifos/assistant/context/ContextAssemblerTest.java` 에 `instructionsHash()` 가 32글자 고정 값과 같고 지침이 비면 null 이라는 테스트를 더한다. 이미 있으면 더하지 않는다.

## 검증

```bash
# cwd: backend/
./gradlew archTest --rerun -Parchunit.freeze.store.default.allowStoreUpdate=true
./gradlew test
test "$(wc -l < config/archunit/store/d3f00600-4e52-412f-ab23-2abb80f9eefd)" -eq 0
! grep -rn "MessageDigest\.getInstance" src/main/java --include='*.java' | grep -v "shared/util/Sha256.java"
```

모두 종료 코드 0 이어야 한다.

## 변경 파일

| 파일 | 변경 |
|---|---|
| `backend/src/main/java/com/bifos/assistant/context/AssembledContext.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/mcp/application/AgentTokenService.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/usage/domain/DelegationKey.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/usage/DelegationKeyTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/mcp/AgentTokenServiceTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/context/ContextAssemblerTest.java` | 수정 |
| `backend/config/archunit/store/d3f00600-4e52-412f-ab23-2abb80f9eefd` | 수정 |
