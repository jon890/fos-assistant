# Phase 04. 전체 흐름을 e2e 로 검사하고 문서를 맞춘다

**Execution profile**: standard

## 목표

사용자가 민감 문서를 만들고, 토큰을 발급하고, 그 토큰으로 본문과 판 번호를 읽는 흐름을 e2e 시나리오 하나로 검사한다.
구현한 것을 `docs/` 와 루트 `AGENTS.md` 에 적는다.

**범위 외**: 화면(plan62).

## 컨텍스트

- e2e 는 `test/e2e/run.ts` 가 backend 를 띄우고 `test/e2e/scenarios/` 의 시나리오를 차례로 돌린다. backend 의 환경 변수는 `run.ts` 의 `env: { ...process.env, ... ASSISTANT_JWT_SECRET: JWT_SECRET, ... }` 블록에서 준다. **지금은 암호화 key 를 주지 않는다.** 민감 문서를 만들려면 더해야 한다
- 시나리오의 선례는 `test/e2e/scenarios/memory.ts` 다. `import { call, expect, expectStatus, step, type Scenario } from "../harness.ts";` 를 쓴다. `call(context, path, { method, token, body })` 는 `${context.api}${path}` 를 부르고 `token` 을 `Authorization: Bearer` 로 싣는다. `context.api` 가 어디까지인지는 `test/e2e/harness.ts` 를 읽어 확인한다. `memory.ts` 는 `/memories` 로 부른다
- 사용자 토큰은 `context.tokens.dad` 와 `context.tokens.kid` 다
- 시나리오는 `run.ts` 의 `SCENARIOS` 배열에 넣은 순서로 돈다. 뒤 시나리오가 앞 시나리오의 데이터에 걸리므로 넣는 자리를 고른다
- phase 01~03 이 만든 경로
  - `POST /api/v1/memory-documents`, `GET /api/v1/memory-documents`, `GET /api/v1/memory-documents/{id}`, `PUT /api/v1/memory-documents/{id}`, `GET /api/v1/memory-collections`
  - `POST /api/v1/service-tokens`, `GET /api/v1/service-tokens`, `DELETE /api/v1/service-tokens/{id}`
  - `GET /api/v1/service/memory-documents/{collection}/{documentKey}`
- 문서가 지금 적고 있는 것
  - `docs/code-architecture.md`: 패키지 표의 `memory` 줄, 「Memory」 절, 그 아래 「다음」 목록. 그 목록에 「다른 서비스가 문서를 읽는 API 와 그 서비스 토큰」 과 「collection 탭, 문서 편집과 판 이력 화면, 출처 표시, 민감 항목 표시」 가 미구현으로 있다
  - `docs/data-schema.md`: 「memory」 가 「지금 화면과 제안이 만드는 줄은 모두 `core` collection 의 `MEMORY` 다」 로 적는다. 「memory_collection」 이 「`collectionsOf` 와 `revisionsOf` 는 아직 부르는 API 가 없다」 로 적는다. 「agent_token」 다음에 서비스 토큰 표가 없다
  - `docs/flow.md`: 「두 방향과 두 토큰」 의 토큰 표가 ①②③ 셋이다. 「Memory 본문을 읽는 길」 이 `memory_read` 만 적는다
  - 루트 `AGENTS.md` 의 「지켜야 할 것」: 「제목만 주입한 항목은 Control Plane 이 응답을 고르는 MCP 도구로만 읽는다」
  - `docs/adr/ADR-053-에이전트는-허용된-collection-의-memory-만-받는다.md` 의 「다음」 이 서비스 토큰을 앞으로 할 일로 적는다

**근거 문서**: `docs/adr/ADR-056-다른-서비스는-사용자에-묶인-서비스-토큰으로-문서를-읽기만-한다.md`, `docs/adr/ADR-057-문서는-사람이-화면에서-직접-쓰고-고친다.md`

## 의도 메모

- e2e 는 backend 테스트가 본 경우를 다시 훑지 않는다. 운영과 같은 조립(보안 설정, 인터셉터, 암호화 설정, Flyway 로 만든 스키마)에서 한 번 왕복하는 것을 본다
- 테스트 데이터베이스는 엔티티로 표를 만들고 e2e 만 Flyway 를 지난다. `uk_memory_user_document` 제약과 V52, V53 가 실제로 붙는 곳이 e2e 다
- `docs/flow.md` 에 서비스 토큰을 「셋째 방향」 으로 적는다. 요청자를 origin 실행에서 정하지 않는 유일한 읽기 길이라 따로 보여야 한다

## 작업 항목

### 1. `test/e2e/run.ts` 의 환경 변수

backend 를 띄우는 `env` 블록의 `ASSISTANT_JWT_SECRET` 아래에 더한다.

```ts
      // 민감 Memory 문서를 만드는 시나리오가 쓴다. 운영 값이 아니라 글자 0123456789abcdef0123456789abcdef 의 base64 다.
      ASSISTANT_MEMORY_ENCRYPTION_ACTIVE_KEY_ID: "test-1",
      ASSISTANT_MEMORY_ENCRYPTION_KEYS: "test-1:MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=",
```

`run.ts` 가 backend 를 띄우는 곳이 여럿이면(다시 띄우는 시나리오가 있다) 모두 같은 값을 받는지 확인한다.

### 2. `test/e2e/scenarios/memory-document.ts`

`export const memoryDocumentScenario: Scenario = { name: "Memory 문서와 서비스 토큰", async run(context) { ... } }`.
문서 이름은 실행마다 겹치지 않게 `application-profile-${randomUUID().slice(0, 8)}` 로 만든다. 서비스 경로는 `call` 의 `token` 에 서비스 토큰 원문을 넣어 부른다.

| step | 하는 일 | 기대 |
| --- | --- | --- |
| collection 목록에 `identity` 가 있다 | dad 가 `/memory-collections` | 200. key 에 `identity` 가 있다 |
| 민감 문서를 만든다 | dad 가 `/memory-documents` 에 `{collection: "identity", documentKey, title: "지원서 공통 프로필", content: "평문-표식-7391", sensitive: true}` | 200. `revision` 이 1, `sensitive` 가 참 |
| 같은 이름으로 다시 만들지 못한다 | 같은 요청 | 409, 코드 `MEMORY_DOCUMENT_EXISTS` |
| 문서는 Memory 목록에 없다 | dad 가 `/memories` | 그 문서의 `id` 가 없다 |
| 다른 사용자는 읽지 못한다 | kid 가 `/memory-documents/{id}` | 404 |
| 토큰을 발급한다 | dad 가 `/service-tokens` 에 `{label: "e2e", expiresInDays: 90, collections: [{collection: "identity", allowSensitive: true}]}` | 200. `token` 이 `fos_svc_` 로 시작한다. `info.expiresAt` 이 있다 |
| 토큰 목록에 원문이 없다 | dad 가 `/service-tokens` | 응답 본문이 원문을 담지 않는다 |
| 토큰으로 읽는다 | 서비스 토큰으로 `/service/memory-documents/identity/{documentKey}` | 200. `content` 가 `평문-표식-7391`, `revision` 이 1 |
| 고친 뒤 판 번호가 오른다 | dad 가 `PUT /memory-documents/{id}` 에 `{content: "평문-표식-8802", sensitive: true, expectedRevision: 1}` 뒤 서비스 토큰으로 다시 읽는다 | `revision` 이 2, `content` 가 `평문-표식-8802` |
| 낡은 판 번호는 거절한다 | dad 가 `expectedRevision: 1` 로 한 번 더 고친다 | 409, 코드 `MEMORY_REVISION_CONFLICT` |
| 민감 허용이 없는 토큰은 읽지 못한다 | dad 가 `allowSensitive: false` 로 발급한 토큰으로 읽는다 | 404 |
| 토큰 없이는 읽지 못한다 | `token` 없이 부른다 | 401 |
| 서비스 토큰은 사용자 API 를 열지 못한다 | 서비스 토큰으로 `/memories` | 403. `test/e2e/scenarios/auth.ts` 가 서명이 틀린 토큰에 기대하는 값과 같다 |
| 폐기한 토큰은 읽지 못한다 | dad 가 `DELETE /service-tokens/{id}` 뒤 그 토큰으로 읽는다 | 401 |
| 뒤 시나리오에 남기지 않는다 | dad 가 `DELETE /memories/{id}` 로 문서를 지운다 | 200 |

### 3. `test/e2e/run.ts` 에 등록

`memoryDocumentScenario` 를 import 하고 `SCENARIOS` 의 `memoryScenario` 바로 뒤에 넣는다.
이 시나리오는 대화 turn 을 돌리지 않고 에이전트를 만들지 않는다. 문서를 지우고 끝나므로 Memory 주입과 사용량을 세는 뒤 시나리오에 걸리지 않는다.
돌려 보아 뒤 시나리오가 실패하면, 실패한 단언이 무엇을 세는지 읽고 자리를 옮긴다. 옮긴 까닭을 배열의 주석으로 남긴다.

### 4. `docs/code-architecture.md`

- 패키지 표의 `memory` 줄에 「문서 쓰기와 고치기, 서비스 토큰, 다른 서비스의 문서 읽기」 를 더한다
- 「Memory」 절에 아래 뜻을 더한다. 문장은 그 절의 문체에 맞춘다
  - 문서(`DOCUMENT`)는 사용자가 직접 쓰고 고친다. 곧 `ACCEPTED` 이고 꺼내는 방식은 `SEARCH` 다. Memory 목록과 `PATCH /api/v1/memories/{id}` 는 문서를 다루지 않는다
  - 다른 서비스는 서비스 토큰으로 `GET /api/v1/service/memory-documents/{collection}/{documentKey}` 를 부른다. 요청자는 토큰이 묶인 사용자이고, 판정은 「에이전트의 실행에 보이는 항목」 의 세 조건과 같다. collection 과 민감 허용은 `service_token_collection` 이 정한다
  - 이 경로의 인증은 `memory.presentation.ServiceTokenInterceptor` 가 한다. `SecurityConfig` 는 그 경로를 `permitAll` 로 열고 `ControlPlaneJwtFilter` 는 건너뛴다. `shared` 가 `memory` 를 쓰지 않게 하기 위해서다
- 클래스 표에 `memory.application.ServiceTokenService`, `memory.presentation.ServiceTokenInterceptor`, `memory.presentation.MemoryDocumentController`, `memory.presentation.MemoryDocumentServiceController` 를 더한다
- 「다음」 목록에서 「다른 서비스가 문서를 읽는 API 와 그 서비스 토큰」 을 지운다. collection 탭과 문서 편집 화면을 적은 줄은 그대로 둔다. 화면은 아직 없다
- 근거 줄에 ADR-056 와 ADR-057 링크를 더한다

### 5. `docs/data-schema.md`

- 「memory」 의 「지금 화면과 제안이 만드는 줄은 모두 `core` collection 의 `MEMORY` 다」 뒤에 「문서 API 가 만드는 줄은 `USER` 범위의 `DOCUMENT` 이고 `ACCEPTED` 다」 를 더한다
- 「memory_collection」 의 「아직 부르는 API 가 없다」 문장을 고친다. `collectionsOf` 는 `GET /api/v1/memory-collections` 가 부른다. `revisionsOf` 는 아직 부르는 API 가 없다
- 「agent_token」 절 다음에 「service_token」 과 「service_token_collection」 절을 더한다. 칸 표는 `V53__service_token.sql` 과 같게 적는다. 아래 뜻을 담는다
  - 원문은 발급 응답에서 한 번만 내고 해시만 저장한다
  - **`agent_token` 과 달리 사용자 한 사람에 묶인다.** 그 사용자 본인만 발급하고 폐기한다
  - `expires_at` 이 비어 있으면 만료가 없다. 폐기는 줄을 지우지 않는다
  - 근거는 ADR-056 다
- 「지울 때」 에 한 문장을 더한다: 서비스 토큰은 폐기해도 줄이 남는다

### 6. `docs/flow.md`

- 「두 방향과 두 토큰」 의 토큰 표에 줄을 더한다: ④ | 다른 서비스 → Control Plane | 서비스 토큰 | 이 요청이 어느 사용자의 문서를 읽을 수 있다. 그 아래 「①은 사용자를 정하고 ③은 profile 만 정한다」 뒤에 「④는 사용자 한 사람과 받는 collection 을 정한다. 실행 없이 읽는 유일한 길이다」 를 더한다. 그 절의 mermaid 그림에는 손대지 않는다
- 「Memory 본문을 읽는 길」 의 「이 왕복은 비싸다」 앞에 「### 다른 서비스가 문서를 읽을 때」 를 더한다. mermaid `sequenceDiagram` 하나(참가자: 다른 서비스, Control Plane, 데이터베이스. 토큰 인증, 문서 조회와 세 조건 판정, 민감 문서 복호화, 본문과 판 번호 응답)와 「갈리는 지점」 표를 둔다. 표의 줄은 ADR-056 의 「적용 범위」 첫 표와 같게 한다

### 7. 루트 `AGENTS.md`

「지켜야 할 것」 의 Memory 항목을 고친다.

```
- Memory 접근 권한은 Control Plane 이 정한다. Hermes 내장 memory 도구는 주지 않는다.
  제목만 주입한 항목은 Control Plane 이 응답을 고르는 MCP 도구로만 읽는다.
  실행 밖의 서비스는 사용자에 묶인 서비스 토큰으로 문서만 읽는다(ADR-056).
```

`CLAUDE.md` 는 `AGENTS.md` 를 가리키는 링크다. 따로 고치지 않는다.

### 8. ADR 과 목록

- `docs/adr/ADR-053-에이전트는-허용된-collection-의-memory-만-받는다.md` 의 「다음」 끝 문장 「다른 서비스가 문서를 읽는 신원은 …」 뒤에 「이것은 [ADR-056](ADR-056-다른-서비스는-사용자에-묶인-서비스-토큰으로-문서를-읽기만-한다.md) 가 정했다.」 를 더한다
- `docs/adr/ADR-056-다른-서비스는-사용자에-묶인-서비스-토큰으로-문서를-읽기만-한다.md` 의 `status` 줄에서 「아직 구현 전이다.」 를 지운다
- `docs/adr/ADR-057-문서는-사람이-화면에서-직접-쓰고-고친다.md` 의 `status` 줄을 「`accepted`. API 는 있고 화면은 아직 구현 전이다.」 로 고친다
- `docs/adr/INDEX.md` 의 ADR-056 줄에서 「아직 구현 전이다」 를 지우고, ADR-057 줄을 「화면은 아직 구현 전이다」 로 고친다

## 검증

```bash
# cwd: 저장소 root
(cd backend && ./gradlew test)
node test/e2e/run.ts
node --test 'test/unit/**/*.test.ts'
scripts/check-public-safe.sh
grep -n "service_token_collection" docs/data-schema.md
grep -n "ADR-056" docs/code-architecture.md docs/flow.md AGENTS.md
! grep -n "다른 서비스가 문서를 읽는 API 와 그 서비스 토큰" docs/code-architecture.md
```

- 모두 종료 코드 0. `grep` 둘은 줄이 나와야 하고 마지막 줄은 나오지 않아야 한다
- `node test/e2e/run.ts` 는 `./gradlew test` 뒤에 돌린다. 앞선 실행이 남긴 데이터에 걸린다. 출력에 `Memory 문서와 서비스 토큰` 시나리오가 통과로 나와야 한다

## 변경 파일

| 파일 | 변경 |
|---|---|
| `test/e2e/run.ts` | 수정 |
| `test/e2e/scenarios/memory-document.ts` | 신규 |
| `docs/code-architecture.md` | 수정 |
| `docs/data-schema.md` | 수정 |
| `docs/flow.md` | 수정 |
| `AGENTS.md` | 수정 |
| `docs/adr/ADR-053-에이전트는-허용된-collection-의-memory-만-받는다.md` | 수정 |
| `docs/adr/ADR-056-다른-서비스는-사용자에-묶인-서비스-토큰으로-문서를-읽기만-한다.md` | 수정 |
| `docs/adr/ADR-057-문서는-사람이-화면에서-직접-쓰고-고친다.md` | 수정 |
| `docs/adr/INDEX.md` | 수정 |
