# Phase 04. 에이전트가 본문을 읽는 도구를 연다

**Execution profile**: deep

## 목표

제목만 실린 Memory 의 본문을 에이전트가 필요할 때 읽을 수 있게 한다.
그 도구를 Control Plane 이 MCP 로 열고, 무엇을 돌려줄지도 Control Plane 이 정한다.

**범위 외**:
Hermes profile 에 이 서버를 등록하는 것은 비공개 저장소 `fos-home-infra` 가 한다.
이 phase 는 서버를 열고 등록에 필요한 것을 정리해 넘기는 데까지 한다.
검색으로 고르는 기능을 만들지 않는다. 도구는 번호로 하나를 읽는다.

## 컨텍스트

phase-02 가 색인을 실었다. 제목과 번호만 있고 본문이 없다.
이 phase 가 그 번호로 본문을 읽는 길을 만든다.

`ADR-003` 이 이 길을 이미 열어 뒀다.

> 에이전트에게 memory 조회 도구를 주고 그 안에서 권한을 검사하는 방식은
> MVP 에서 필요하지 않은 왕복을 더하므로 지금은 쓰지 않는다.
> 나중에 memory 가 커져 전부 주입하기 어려워지면 그때 도입한다.
> **그때도 권한 검사는 도구 구현이 아니라 Control Plane 이 맡는다.**

마지막 문장이 이 phase 의 설계를 정한다.
Hermes 의 내장 `memory` toolset 을 켜는 것이 아니라, 우리가 만든 도구를 준다.

**경계가 맞는 이유가 있다.**
profile 하나에는 주인이 하나다.
`ADR-002` 가 사용자마다 profile 을 나누기로 했고,
한 사용자가 기본 profile 에 역할 profile 을 더해 여럿을 가질 수는 있지만
그 profile 들의 주인은 모두 그 사용자다.

그래서 profile 마다 **그 profile 주인의** 토큰을 박아 두면,
Control Plane 은 요청이 오는 순간 누구인지 안다. 요청 본문이 사용자를 정하지 못한다.

한 사용자가 profile 을 여럿 가지면 그 profile 들이 같은 사용자의 토큰을 쓴다.
**다른 사용자의 토큰을 재사용하지 않는다.** 그것이 경계를 무너뜨리는 유일한 길이다.

**근거 문서**: `docs/adr/ADR-003-memory-권한은-주입으로-강제한다.md`,
`docs/adr/ADR-015-memory-는-층을-나눠-싣는다.md`,
`docs/adr/ADR-002-profile은-나누고-ai-계정은-가족이-함께-쓴다.md`,
`docs/code-architecture.md` 의 「Memory」 절

## 의도 메모

- 별도 서비스로 빼지 않는다.
  Memory 데이터와 권한 검사 코드가 Control Plane 에 있다.
  빼면 데이터베이스 접근과 권한 로직이 두 곳이 된다.
- 다만 경로의 성격은 다르다.
  기존 `/api/v1/**` 은 브라우저가 지나는 길이고 짧은 수명 토큰을 쓴다.
  이 경로는 Hermes 가 지나는 길이고 장기 토큰을 쓴다.
  `SecurityConfig` 에서 둘을 다르게 다룬다.
- 검색을 만들지 않는다. 번호로 하나를 읽는 것만 만든다.
  색인이 이미 제목을 주므로 에이전트가 무엇을 읽을지 고를 수 있다.
  검색이 필요해지는 시점은 `context_chars` 와 도구 호출 기록을 보고 정한다.
- 외부로 열지 않는다. Hermes 와 Control Plane 이 같은 홈서버의 컨테이너다.

## 작업 항목

### 1. 장기 토큰을 만드는 자리

`backend/src/main/resources/db/migration/V8__agent_token.sql` 신규.

제안 상태가 바뀐 뒤에도 중복 저장을 막기 위해
`V9__memory_proposal_dedup.sql`에서 제안 중복 키와 유일 제약을 추가한다.
아직 구현되지 않은 실행 사건 계획의 마이그레이션은 `V10` 으로 함께 고친다.

```sql
CREATE TABLE agent_token (
    id BIGINT NOT NULL AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    token_hash VARCHAR(64) NOT NULL,
    label VARCHAR(100) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    last_used_at DATETIME(6) NULL,
    revoked_at DATETIME(6) NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_agent_token_hash (token_hash)
);
```

**토큰 원문을 저장하지 않는다.** 해시만 남긴다.
만들 때 한 번 보여주고 그 뒤로는 다시 볼 수 없다.
`AGENTS.md` 가 「비밀값을 데이터베이스에 넣지 않는다」 고 적고 있다.

발급과 폐기는 관리자만 한다.

`AgentTokenAdminController` 와 관리자 API 요청·응답 DTO를 만든다.
발급 요청은 `userEmail` 과 `label` 을 받고 `AppUserRepository`에서 사용자를 찾는다.
발급 응답만 `token` 원문을 한 번 포함하고, 목록 응답은 `id`, `userEmail`, `label`,
`createdAt`, `lastUsedAt`, `revokedAt` 만 포함한다.

| 메서드 | 경로 | 하는 일 |
| --- | --- | --- |
| `POST` | `/api/v1/admin/agent-tokens` | 사용자를 정해 발급. 원문을 한 번만 낸다 |
| `GET` | `/api/v1/admin/agent-tokens` | 목록. 원문은 없고 `label` 과 발급 시각만 |
| `DELETE` | `/api/v1/admin/agent-tokens/{id}` | 폐기 |

폐기는 지우지 않고 `revoked_at` 을 채운다.
지우면 그 토큰으로 무엇을 했는지 되짚을 수 없다.

### 2. MCP 경로를 연다

`backend/src/main/java/com/bifos/assistant/mcp/` 아래다.

MCP 는 JSON-RPC 2.0 과 Streamable HTTP protocol version `2025-03-26` 을 쓴다.
Hermes 가 `--url` 로 붙으므로 HTTP 경로 하나면 된다.

```
POST /mcp
```

받아야 하는 method 는 셋이다.

| method | 낼 것 |
| --- | --- |
| `initialize` | 서버 이름과 프로토콜 판, `tools` capability |
| `tools/list` | 아래 도구 하나 |
| `tools/call` | 그 도구의 결과 |

`initialize` 결과는 protocol version `2025-03-26`을 돌려준다.
`capabilities.tools.listChanged` 는 `false`, `serverInfo.name` 은 `fos-assistant-memory`,
`serverInfo.version` 은 애플리케이션 version 이다.
`notifications/initialized` 는 본문 없는 HTTP 202로 끝낸다.

성공한 `tools/call` 은 `result.content` 에 `type: "text"` 한 개와 본문을 넣고
`isError: false` 를 낸다.
없는 도구는 JSON-RPC `-32601`, 잘못된 인자는 `-32602` 로 응답한다.
볼 수 없거나 없는 Memory는 같은 `result.content`와 `isError: true`를 돌려 존재를 구분하지 않는다.
모든 응답은 요청의 JSON-RPC `id`를 그대로 돌려준다.

도구 하나만 낸다.

```json
{
  "name": "memory_read",
  "description": "지금 묻는 사람의 Memory 항목 본문을 번호로 읽는다. 번호는 지시문의 색인에 있다.",
  "inputSchema": {
    "type": "object",
    "properties": { "id": { "type": "integer" } },
    "required": ["id"]
  }
}
```

**`tools/call` 이 하는 일은 `MemoryService.bodyFor(user, id)` 하나다.**
그 메서드가 phase-01 에서 이미 권한을 검사한다.
여기서 권한을 다시 구현하지 않는다.

남의 것이나 없는 번호를 요구하면 MCP 오류로 답한다.
**있는지 없는지를 구분해 알리지 않는다.** 둘 다 같은 응답이다.

### 3. 인증

`Authorization: Bearer <토큰>` 을 받아 `agent_token` 에서 찾는다.

발급 원문은 `SecureRandom` 32바이트를 URL-safe Base64로 만들고 padding을 빼서 만든다.
저장할 때는 UTF-8 원문의 SHA-256을 소문자 16진수로 바꿔 `token_hash`에만 넣는다.

- 해시가 맞지 않으면 401
- `revoked_at` 이 있으면 401
- 맞으면 그 `user_id` 가 이 요청의 사용자다

**요청 본문이 사용자를 정하지 못한다.** 토큰만이 정한다.

찾을 때마다 `last_used_at` 을 갱신한다.
쓰이지 않는 토큰을 찾아 폐기할 수 있어야 한다.

`SecurityConfig` 에 `/mcp` 를 더한다.
기존 `ControlPlaneJwtFilter` 를 타지 않는다. 다른 인증이다.
`AgentTokenAuthenticationFilter` 를 JWT filter 앞에 두고 `/mcp` 요청에서만 동작시킨다.
`ControlPlaneJwtFilter.shouldNotFilter()`는 `/mcp`에서 참을 돌려 MCP 토큰을 JWT로 파싱하지 않는다.
토큰이 가리키는 `AppUser`를 데이터베이스에서 읽어 `CurrentUser`를 만들며,
요청 JSON의 어떤 필드도 사용자 선택에 쓰지 않는다.
Hermes는 브라우저가 아니므로 `Origin` header가 붙은 `/mcp` 요청은 403으로 거절한다.

### 4. 호출을 서버 로그에 남긴다

도구가 불릴 때마다 그 사실을 남긴다.
어느 항목이 실제로 읽히는지 알아야 항상 층으로 올릴 것을 고를 수 있다.

이번 phase 에서는 `log.info` 로 Memory 번호와 사용자 번호만 남긴다.
MCP 요청에는 연결할 실행 번호가 없으므로 실행 사건에는 기록하지 않는다.

**본문을 로그에 남기지 않는다.** 번호와 사용자만 남긴다.

### 5. 등록에 필요한 것을 정리해 넘긴다

`fos-home-infra` 에 넘길 내용을 완료 보고에 적는다.
**이 phase 에서 Hermes profile 을 고치지 않는다.**

넘길 것은 아래다.

- MCP 서버 이름
- Control Plane 이 여는 경로
- profile 마다 다른 토큰이 필요하다는 것과 그것을 발급받는 방법
- 토큰을 profile 설정에 어떻게 넣는지는 그 저장소가 정한다

**이 저장소는 공개 저장소다.**
홈서버의 주소와 포트와 컨테이너 이름을 적지 않는다.
발급한 토큰을 어디에도 적지 않는다.

### 6. 스키마 문서를 갱신한다

`docs/data-schema.md` 에 `agent_token` 표와 원문을 저장하지 않는 규칙을 반영한다.

### 7. 이 phase 를 검증하는 테스트

`backend/src/test/java/com/bifos/assistant/mcp/McpMemoryToolTest.java` 를 새로 만든다.

- **정상 경로**: 유효한 토큰으로 자기 항목의 번호를 주면 본문이 온다
- `tools/list` 가 `memory_read` 하나를 낸다
- **이 phase 가 막아야 할 것**: 다른 사용자의 항목 번호를 주면 본문이 오지 않는다.
  **없는 번호를 줬을 때와 응답이 같다.** 존재를 알리지 않는다
- 토큰이 없으면 401
- 폐기된 토큰이면 401
- 요청 본문에 `user_id` 를 실어 보내도 무시된다. 토큰의 사용자로만 답한다
- `PROPOSED` 인 항목은 본문이 오지 않는다
- `initialize` 와 `notifications/initialized`, `tools/list`, `tools/call` 응답이 위 JSON-RPC 계약과 맞는다
- 모르는 method, 없는 도구와 잘못된 인자의 오류 코드가 계약과 맞는다
- MCP 토큰 요청이 `ControlPlaneJwtFilter`를 지나지 않는다
- `Origin` header가 있는 요청은 403이고 없는 Hermes 요청은 정상 처리된다

`backend/src/test/java/com/bifos/assistant/mcp/AgentTokenServiceTest.java` 를 새로 만든다.

- 발급하면 원문이 한 번 오고 데이터베이스에는 해시만 있다
- **저장된 행의 어느 칸에도 원문이 없는 것을 단언문으로 고정한다**
- 폐기한 토큰은 행이 남고 `revoked_at` 이 채워진다
- 발급, 목록, 폐기 API는 관리자만 쓸 수 있고 구성원은 403 이다

`test/e2e/scenarios/memory.ts` 에 더한다.

- 사용자 둘의 토큰을 각각 발급해, 한쪽 토큰으로 다른 쪽 항목을 읽지 못한다

## 검증

```bash
# cwd: 저장소 root
cd backend && ./gradlew test
node test/e2e/run.ts
cd web && pnpm typecheck && pnpm build
```

```bash
# cwd: 저장소 root
cd backend && ./gradlew test --tests '*McpMemoryToolTest*'
cd backend && ./gradlew test --tests '*AgentTokenServiceTest*'
```

토큰 원문이 로그에 남지 않는지 확인한다.

```bash
# cwd: 저장소 root
grep -rn "token" backend/src/main/java/com/bifos/assistant/mcp/ | grep -iE "log\.|System\.out" && echo "확인 필요" || echo "통과"
```

**가짜로 통과해도 Hermes 가 실제로 이 도구를 부르는지는 모른다.**
`fos-home-infra` 가 등록을 마친 뒤 실제로 한 번 왕복시켜 아래를 보고에 적는다.

- 에이전트가 `memory_read` 를 실제로 부르는지
- 부르지 않으면 색인의 문구를 고쳐야 하는지

## Critical Files

| 파일 | 변경 |
|---|---|
| `backend/src/main/resources/db/migration/V8__agent_token.sql` | 신규 |
| `backend/src/main/java/com/bifos/assistant/mcp/presentation/McpController.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/mcp/application/McpToolService.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/mcp/application/AgentTokenService.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/mcp/domain/AgentToken.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/mcp/infra/AgentTokenRepository.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/mcp/infra/AgentTokenAuthenticationFilter.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/mcp/presentation/AgentTokenAdminController.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/mcp/presentation/AgentTokenDtos.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/shared/config/SecurityConfig.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/shared/auth/ControlPlaneJwtFilter.java` | 수정 |
| `docs/data-schema.md` | 수정 |
| `backend/src/test/java/com/bifos/assistant/mcp/McpMemoryToolTest.java` | 신규 |
| `backend/src/test/java/com/bifos/assistant/mcp/AgentTokenServiceTest.java` | 신규 |
| `test/e2e/scenarios/memory.ts` | 수정 |

## 끝낸 뒤

`tasks/plan009-memory/index.json` 의 이 phase 를 `completed` 로 바꾸고
plan 의 `status` 를 `completed` 로 바꾼다.
