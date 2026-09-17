# Phase 02. 실행 당시의 상태를 함께 남긴다

**Execution profile**: deep

## 목표

실행마다 그때의 문맥이 어땠는지를 남긴다.
비용이 달라졌을 때 무엇이 달라져서인지 되짚을 수 있게 한다.

이 phase 가 실제로 채우는 것은 `instructions_hash` 하나다.
`runtime_fingerprint` 는 칸만 만들고 비워 둔다. 아래 「컨텍스트」 가 그 이유를 갖는다.

**범위 외**:
축별 분석 화면은 phase-03 이 만든다.
모델을 자동으로 고르는 기능을 만들지 않는다.
Hermes 의 고정 프롬프트 구성을 읽는 경로를 만들지 않는다.

## 컨텍스트

오늘 같은 질문에 입력 토큰이 이렇게 움직였다.

| 시점 | 입력 토큰 | 무엇이 달랐나 |
| --- | --- | --- |
| 스킬 97개 | 14,949 | |
| 스킬 6개 | 12,388 | 스킬을 줄였다 |
| `MEMORY.md` 를 비운 뒤 | 9,551 | 내장 memory 를 비웠다 |

이 표를 만들려고 사람이 그때그때 손으로 적었다.
실행 기록만 보면 14,949 와 9,551 이 왜 다른지 알 수 없다.

앞으로 바뀔 것이 더 있다.
도구 범위를 좁히는 작업이 `fos-home-infra` 에서 돌고 있고,
Memory 를 Control Plane 이 주입하기 시작하면 그 양이 실행마다 달라진다.

**무엇이 달라져서 비용이 움직였는지를 기록이 스스로 말해야 한다.**

plan009 의 phase-02 가 `context_chars` 를 이미 채운다.
이 phase 는 그 옆에 `instructions_hash` 를 더한다.
길이가 같아도 내용이 다를 수 있어서, 길이만으로는 같은 문맥으로 돌았는지 알 수 없다.

설정 지문은 이번에 채우지 못한다.
그 값을 계산해 주는 것이 CLI 명령 하나뿐이고 Control Plane 이 부를 수 있는 HTTP 경로가 없다.
칸만 만들어 두고 값은 비운다. 「작업 항목 1」 이 그 판단의 근거를 갖는다.

**근거 문서**: `docs/adr/ADR-014-실제-청구액과-환산액을-나눠-적는다.md`,
`docs/data-schema.md` 의 「agent_execution」 절,
`docs/hermes-integration.md` 의 「도구 정의가 더 크다」 절

## 의도 메모

- 설정 전문을 저장하지 않는다. 지문만 남긴다.
  `config.yaml` 이 15KB 이고 그것을 실행마다 저장하면 기록이 설정으로 찬다.
  지문이 달라진 구간을 찾으면 그때 그 설정을 보면 된다.
- 작업 영역의 git SHA 는 남길 것이 없다. 작업 영역은 제거됐다.
  대신 에이전트가 가리키는 Hermes profile 의 상태를 남긴다.
- 지문을 만들 수 없으면 비워 둔다. 실행을 실패시키지 않는다.
  지금은 만들 수 없으므로 항상 비어 있다.
- **Control Plane 이 홈서버의 셸을 갖게 하지 않는다.**
  ssh 로 붙어 명령을 돌리거나 `ProcessBuilder` 로 외부 명령을 부르는 구조를 만들지 않는다.
  값 하나를 얻으려고 그 경로를 여는 것은 값에 견줘 대가가 너무 크다.
- 칸은 지금 만든다. 값을 넣을 수 있게 되는 날 마이그레이션을 다시 하지 않기 위해서다.

## 작업 항목

### 1. `runtime_fingerprint` 는 비워 둔다

원래 의도는 Hermes 가 계산한 고정 프롬프트 구성을 지문으로 삼는 것이었다.
system prompt 글자 수, 스킬 수, 도구 수, 도구 정의 바이트 넷을 이어 붙여 해시를 만든다.

**그 넷을 주는 것은 CLI 명령 하나뿐이고 HTTP API 에는 그 경로가 없다.**
Control Plane 이 Hermes 에 쓰는 경로는 `/v1/runs` 계열과 `/v1/skills` 와 `/v1/capabilities` 다.
`/v1/skills` 는 스킬 수만 주고 나머지 셋을 주지 않는다.
`/v1/capabilities` 는 엔드포인트 목록이라 프롬프트 구성을 담지 않는다.

Control Plane 은 그 CLI 가 도는 곳 밖에 있다.
붙어서 명령을 돌리는 구조를 만들면 Control Plane 이 그쪽의 셸을 갖게 된다.
**그 대가가 값보다 커서 만들지 않는다.**

그러므로 이 phase 는 `runtime_fingerprint` 칸을 만들기만 하고 값을 넣지 않는다.
`RuntimeFingerprintReader` 와 그 캐시 설정과 그 테스트를 만들지 않는다.

**칸은 지금 만든다.** Hermes 가 그 값을 HTTP 로 주기 시작하면
읽는 자리 하나만 더하면 되고 마이그레이션을 다시 하지 않는다.
phase-03 의 `fingerprint` 축도 같은 이유로 지금 만든다.

### 2. `backend/src/main/resources/db/migration/V11__execution_fingerprint.sql` 신규

`V9` 까지 적용돼 있고 `V10` 은 다른 작업이 쓴다. 그래서 이 파일은 `V11` 이다.

```sql
ALTER TABLE agent_execution
    ADD COLUMN runtime_fingerprint VARCHAR(64) NULL,
    ADD COLUMN instructions_hash VARCHAR(64) NULL;

CREATE INDEX idx_agent_execution_fingerprint ON agent_execution (runtime_fingerprint);
```

`instructions_hash` 는 그 실행에 실제로 넣은 `instructions` 의 해시다.
`context_chars` 가 길이를 말하고 이 칸이 내용이 같은지를 말한다.
길이가 같아도 내용이 다를 수 있다.

**`instructions` 본문을 저장하지 않는다.** 그 안에 Memory 가 들어 있고 그것은 개인 기록이다.
해시만 남기면 같은 문맥으로 돌았는지는 알 수 있고 내용은 남지 않는다.

### 3. `instructions` 의 해시를 만드는 자리

`ContextAssembler` 가 만든 문자열의 SHA-256 을 앞 16바이트만 남기고 16진수로 적는다.
32글자가 된다.

같은 `instructions` 는 같은 해시를 내고 다른 `instructions` 는 다른 해시를 낸다.
`context_chars` 가 길이를 말하고 이 칸이 내용이 같은지를 말한다.

**`instructions` 가 `null` 이거나 빈 문자열이면 해시를 적지 않는다.**
빈 문자열의 해시는 언제나 같은 값이라, 그것을 적으면
문맥 없이 돈 실행들이 모두 한 지문으로 묶여 잘못 읽힌다.

Hermes 로 나가는 새 호출을 만들지 않는다. 이 값은 Control Plane 이 가진 문자열로만 계산한다.

### 4. `ExecutionRecorder` 가 적는다

`start` 가 지금 6개 인자를 받는다. 여기에 인자를 더 붙이지 않는다.
**값을 담은 record 하나를 받는 오버로드를 더한다.** 기존 6인자 메서드는 남긴다.
같은 메서드를 다른 작업이 동시에 고치고 있어 인자를 늘리면 머지할 때 충돌한다.

```java
/** 실행을 시작할 때 함께 적는 실행 당시의 상태. 모르는 값은 null 이다. */
public record ExecutionContextSnapshot(
        Long contextChars,
        String runtimeFingerprint,
        String instructionsHash) {
}
```

`instructionsHash` 는 `ContextAssembler` 가 만든 문자열의 SHA-256 앞 16바이트다.
16진수로 32글자가 된다.

`instructions` 가 비어 있으면 이 칸도 비운다. 빈 문자열의 해시를 적지 않는다.

### 5. 사용량 조회가 낸다

`ExecutionView` 에 `runtimeFingerprint` 와 `instructionsHash` 를 더한다.
`contextChars` 는 plan009 의 phase-02 가 이미 더했다.

### 6. 스키마 문서를 함께 고친다

`docs/data-schema.md` 의 「agent_execution」 칸 표에 두 칸을 더한다.
그 표가 `context_chars` 와 `actual_cost_micros` 까지 이미 적고 있어, 이번에 더하는 두 칸만 빠지면 안 된다.

같은 문서의 「자식 실행의 토큰이 부모의 합계에 이미 들어 있는지는 아직 확인하지 못했다」 문단을
`ADR-016` 의 실측에 맞춰 고친다. 자식 토큰은 부모 usage 에 포함되지 않는다.

### 7. 이 phase 를 검증하는 테스트

`backend/src/test/java/com/bifos/assistant/usage/ExecutionLifecycleTest.java` 에 더한다.

- **정상 경로**: 실행 줄에 `instructionsHash` 가 적히고, 같은 `instructions` 는 같은 해시다
- 다른 `instructions` 는 다른 해시다
- **이 phase 가 다루는 실패**: `instructions` 가 `null` 이거나 빈 문자열이면 그 칸도 `null` 이다.
  빈 문자열의 해시가 적히지 않는다
- **`instructions` 본문이 어느 칸에도 저장되지 않는다.**
  저장된 실행 줄의 모든 문자열 칸에 Memory 내용이 없는 것을 단언문으로 고정한다
- `runtimeFingerprint` 는 `null` 이다. 읽는 경로를 만들지 않았기 때문이다

## 검증

```bash
# cwd: 저장소 root
cd backend && ./gradlew test
cd web && pnpm typecheck
cd web && pnpm build
cd web && pnpm test:browser
node test/e2e/run.ts
scripts/check-public-safe.sh
```

`AGENTS.md` 의 「확인」 절이 정한 순서다.
`scripts/check-public-safe.sh` 는 이 phase 에서 특히 중요하다.
홈서버와 Hermes profile 을 다루는 phase 라 운영 정보가 새기 쉽다.

```bash
# cwd: 저장소 root
cd backend && ./gradlew test --tests '*ExecutionLifecycleTest*'
```

**Control Plane 이 홈서버로 나가는 새 호출을 만들지 않은 것을 확인한다.**

```bash
# cwd: 저장소 root
! grep -rn 'ProcessBuilder\|Runtime.getRuntime\|ssh ' backend/src/main/java/
```

## Critical Files

| 파일 | 변경 |
|---|---|
| `backend/src/main/resources/db/migration/V11__execution_fingerprint.sql` | 신규 |
| `backend/src/main/java/com/bifos/assistant/usage/domain/AgentExecution.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/usage/application/ExecutionRecorder.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/usage/presentation/UsageController.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/chat/application/ChatService.java` | 수정 |
| `docs/data-schema.md` | 수정 |
| `backend/src/test/java/com/bifos/assistant/usage/ExecutionLifecycleTest.java` | 수정 |
