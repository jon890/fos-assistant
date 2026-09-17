# Phase 02. 실행 당시의 상태를 함께 남긴다

**Execution profile**: standard

## 목표

실행마다 그때의 설정과 문맥이 어땠는지를 남긴다.
비용이 달라졌을 때 무엇이 달라져서인지 되짚을 수 있게 한다.

**범위 외**:
축별 분석 화면은 phase-03 이 만든다.
모델을 자동으로 고르는 기능을 만들지 않는다.

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
이 phase 는 그 옆에 설정 지문을 더한다.

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
  Hermes 에 물어보는 값이라 그 호출이 실패할 수 있다.

## 작업 항목

### 1. 무엇을 지문으로 삼을지 정한다

Hermes 의 `prompt-size` 가 고정 프롬프트의 구성을 계산해 준다.
API 를 부르지 않고 도는 명령이다.

**이 저장소는 공개 저장소다. 실행 방법을 여기 적지 않는다.**
그 명령과 홈서버 구조는 비공개 저장소 `fos-home-infra` 가 소유한다.

그 응답에서 아래를 뽑아 지문으로 삼는다.

| 값 | 어디서 |
| --- | --- |
| system prompt 글자 수 | `system_prompt.chars` |
| 스킬 수 | `skills_breakdown` 의 길이 |
| 도구 수 | `tools.count` |
| 도구 정의 바이트 | `tools.json_bytes` |

이 넷을 이어 붙여 해시를 만든다. 그것이 `runtime_fingerprint` 다.

**실행마다 이 명령을 부르지 않는다.** 그러면 실행이 느려지고 홈서버에 부담이 된다.
에이전트별로 캐시하고, 캐시가 오래되면 다시 읽는다.

### 2. `backend/src/main/resources/db/migration/V10__execution_fingerprint.sql` 신규

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

### 3. 지문을 읽는 자리

`backend/src/main/java/com/bifos/assistant/agent/application/RuntimeFingerprintReader.java` 다.

```java
/**
 * 에이전트가 가리키는 Hermes profile 의 고정 프롬프트 구성을 지문으로 만든다.
 *
 * <p>실행마다 읽지 않는다. 에이전트별로 캐시하고 오래되면 다시 읽는다.
 * 읽지 못하면 비워 둔다. 실행을 막을 값이 아니다.
 */
@Service
public class RuntimeFingerprintReader {

    /** 읽지 못하면 null 을 낸다. */
    public String fingerprintOf(Agent agent);
}
```

캐시 수명은 `assistant.fingerprint.ttl` 로 정한다. 기본값은 1시간이다.

**Hermes API 로 읽을 방법이 있으면 그것을 쓴다.**
`prompt-size` 는 CLI 명령이고 Control Plane 은 컨테이너 밖에 있다.
`GET /v1/capabilities` 나 다른 경로로 같은 값을 얻을 수 있는지 먼저 확인한다.
없으면 그 사실을 적고 이 칸을 비워 두는 쪽으로 간다.

**확인하기 전에 구현하지 않는다.**
Control Plane 이 홈서버에 ssh 로 붙어 명령을 돌리는 구조는 만들지 않는다.
그 경로를 열면 Control Plane 이 홈서버의 셸을 갖게 된다.

### 4. `ExecutionRecorder` 가 적는다

`start` 에서 `runtimeFingerprint` 와 `instructionsHash` 를 받아 적는다.
`instructionsHash` 는 `ContextAssembler` 가 만든 문자열의 SHA-256 앞 16바이트다.

`instructions` 가 비어 있으면 이 칸도 비운다. 빈 문자열의 해시를 적지 않는다.

### 5. 사용량 조회가 낸다

`ExecutionView` 에 `contextChars`, `runtimeFingerprint`, `instructionsHash` 를 더한다.
`contextChars` 는 plan009 의 phase-02 가 이미 더했다.

### 6. 이 phase 를 검증하는 테스트

`backend/src/test/java/com/bifos/assistant/agent/RuntimeFingerprintReaderTest.java` 를 새로 만든다.

- **정상 경로**: 같은 구성이면 같은 지문이 나온다
- 도구 수가 달라지면 지문이 달라진다
- **이 phase 가 다루는 실패**: 읽지 못하면 `null` 을 내고 예외를 던지지 않는다
- 캐시 수명 안에는 다시 읽지 않는다

`backend/src/test/java/com/bifos/assistant/usage/ExecutionLifecycleTest.java` 에 더한다.

- 실행 줄에 `instructionsHash` 가 적히고, 같은 `instructions` 는 같은 해시다
- `instructions` 가 `null` 이면 그 칸도 `null` 이다
- **`instructions` 본문이 어느 칸에도 저장되지 않는다.**
  저장된 실행 줄의 모든 문자열 칸에 Memory 내용이 없는 것을 단언문으로 고정한다

## 검증

```bash
# cwd: 저장소 root
cd backend && ./gradlew test
node test/e2e/run.ts
cd web && pnpm typecheck && pnpm build
```

```bash
# cwd: 저장소 root
cd backend && ./gradlew test --tests '*RuntimeFingerprintReaderTest*'
```

실행마다 홈서버를 부르지 않는지 확인한다.
가짜를 호출 수를 세도록 만들고, 실행 셋을 돌렸을 때 호출이 한 번인 것을 본다.

## Critical Files

| 파일 | 변경 |
|---|---|
| `backend/src/main/resources/db/migration/V10__execution_fingerprint.sql` | 신규 |
| `backend/src/main/java/com/bifos/assistant/agent/application/RuntimeFingerprintReader.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/usage/domain/AgentExecution.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/usage/application/ExecutionRecorder.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/usage/presentation/UsageController.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/chat/application/ChatService.java` | 수정 |
| `backend/src/main/resources/application.yml` | 수정 |
| `backend/src/test/java/com/bifos/assistant/agent/RuntimeFingerprintReaderTest.java` | 신규 |
| `backend/src/test/java/com/bifos/assistant/usage/ExecutionLifecycleTest.java` | 수정 |
