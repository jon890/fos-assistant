# Phase 01. 공통 실행에 로컬 MCP 단건 호출 지침을 싣는다

**Execution profile**: standard

## 목표

로컬 MCP 도구를 한 tool_call 에 여러 개 보내 거절된 뒤 다시 부르는 왕복을 줄인다.

**범위 외**: Hermes core, plugin 묶음 처리, profile 설정 이관, HTTP 원격 도구 서버, 권한, DB, 화면, 운영 배포.

## 컨텍스트

**근거 문서**: `docs/backend/context-bundle.md` 의 「공통 실행 지침」.
`ContextAssembler.withResponseInstructions(AssembledContext, boolean)` 은 공통 답변 지침 뒤에 선택적 기억 지침과 문맥을 붙인다.
대화의 `ChatTurnRunner`, 자식의 `AgentRunner`, `ProactiveCheckInput` 이 이 함수를 쓴다.
`AssembledContext` 는 instructions 와 chars, omittedMemoryIds, bundle 을 보관하고 instructionsHash 를 계산한다.
기억 지침의 조건과 본문은 다른 작업이 맡으므로 고치지 않는다.

## 의도 메모

- 새 profile 틀만 고치면 기존 profile 이 받지 못하므로 매 실행의 instructions 를 쓴다.
- 읽기 묶음 지원은 도구 실행과 hook 경계를 늘리므로 이번 범위에 넣지 않는다.
- 단건 호출을 안내하는 변경이며 모델의 준수와 왕복 감소는 운영에서 확인한다.
- docs 영향: 제품 범위, 호출 순서, 모듈 배치, 저장 계약은 같아 prd, flow, code-architecture, schema 는 고치지 않는다. 되돌리기 쉬운 안내라 새 ADR 은 만들지 않는다.

## 작업 항목

### 1. ContextAssembler 의 공통 도구 호출 지침

`backend/src/main/java/com/bifos/assistant/context/ContextAssembler.java` 에 한국어 도구 호출 지침 상수를 추가한다.
로컬 MCP 등록 이름 mcp__... 를 tool_call 로 부를 때 calls 배열은 정확히 항목 하나만 넣고,
같은 서버의 읽기 도구도 여러 개를 묶지 않으며 도구마다 별도 호출한다고 적는다.
MCP 서버 연결이 HTTP 여도 로컬 등록 이름에는 단건 규칙이 적용되고, HTTP 원격 도구 서버 규칙은 해당 계약을 따른다고 구분한다.
withResponseInstructions 에서 답변 형식 뒤, 선택적 기억 지침 앞에 공통 도구 호출 지침을 붙인다.
Memory 예산, 누락 번호, bundle 을 그대로 보존하고 chars 와 instructionsHash 는 추가한 지침까지 반영한다.
메서드 주석을 공통 실행 지침을 싣는다는 뜻으로 고친다. 시그니처는 유지한다.

### 2. 공통 지침 테스트

`backend/src/test/java/com/bifos/assistant/context/ContextAssemblerTest.java` 의 공통 지침 검사를 확장한다.
빈 문맥, remembers=false, remembers=true 모두에 calls 배열 단건·같은 서버 읽기 도구·별도 호출·HTTP 구분 안내가 들어가는지 확인한다.
기억 지침 조건과 기존 답변 형식이 유지되고, 기존 Memory 본문·누락 번호·bundle 과 지침을 포함한 chars·hash 가 유지되는지 확인한다.
각 테스트는 한국어 DisplayName 과 동사로 시작하는 영문 메서드명을 쓴다.
안내를 제거하면 테스트가 실패해야 한다. 추가 지침 때문에 기억 본문이나 누락 목록을 잃는 회귀도 실패해야 한다.

## 검증

무거운 로컬 검사는 dispatch 공통 지시문의 heavy-lock 으로 감싼다.

```bash
cd backend && ./gradlew test --tests com.bifos.assistant.context.ContextAssemblerTest
```

종료 코드 0. 전체 backend, e2e 와 브라우저 검사는 CI 가 맡는다.

## 변경 파일

| 파일 | 변경 |
| --- | --- |
| `backend/src/main/java/com/bifos/assistant/context/ContextAssembler.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/context/ContextAssemblerTest.java` | 수정 |
