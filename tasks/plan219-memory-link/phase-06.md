# Phase 06. 임베딩으로 대체 후보를 찾는다 (PR 2b)

**Execution profile**: deep

## 목표

새로 임베딩한 항목마다 같은 주인의 비슷한 옛 항목 가운데 값이 다른 쌍을 찾아 `PROPOSED` `SUPERSEDES`(`origin=AUTO`)를 만든다. 사람이 받아들이기 전에는 주입이 바뀌지 않는다.

**범위 외**: 자동 `RELATED`(phase 07), 조립 확장(phase 08). 임베딩이 꺼져 있으면 이 phase 의 동작은 없다.

## 컨텍스트

**근거 문서**: `docs/adr/ADR-20261015-memory-link.md` 의 「결정」 의 「대체 후보는 코드가 찾는다」 와 「LLM 이 쓰기 때 UPDATE 나 DELETE 를 고르지 않는다」, 「맥락」 의 땅콩 사례(코사인이 높아도 뜻이 반대일 수 있어 자동 병합하지 않는다).

- 훅 자리: `memory/application/embedding/MemoryEmbeddingRefresher.refreshOnce` 의 묶음 쓰기 뒤(새로 쓴 번호를 안다). 쓰기 트랜잭션 밖에서 부른다
- 벡터: `memory/infra/MemoryEmbeddingRepository.findByMemoryIdIn`, `memory/application/relevance/MemoryVectorCache`
- 부정 표지: `mcp/application/NegationMarkers`(memory 가 mcp 를 쓰지 못하면 같은 규칙을 `memory.application` 으로 옮기고 mcp 가 그것을 쓴다)

## 작업 항목

### 1. `SupersedeCandidates.java` 신규

- `memory/application/link/SupersedeCandidates.java` 신규: 새 항목 하나와 같은 주인(`USER` 는 `ownerUserId`, `GROUP` 은 `groupId`)의 대체되지 않은 `ACCEPTED` 항목의 벡터 코사인이 `assistant.memory.link.supersede-min-similarity`(기본 0.85) 이상이고, 두 글의 숫자열 집합이나 날짜 토큰 집합이나 부정 표지 유무가 다르면 후보다

### 2. 방향과 상한

- 방향은 나중에 고친 항목이 새 항목(`from`)이다. 후보는 항목마다 최대 2건, 이미 `REVOKED` 된 같은 쌍은 다시 만들지 않는다(사람이 거절한 것)

### 3. 링크 만들기

- `MemoryLinkService.proposeSupersede(..., AUTO, score)` 로 만든다. 로그에는 번호와 점수만

### 4. 설정

- 설정 `memory/application/link/MemoryLinkProperties`(`assistant.memory.link`) 와 `application.yml`

### 5. 이 phase 를 검증하는 테스트

- `SupersedeCandidatesTest`: 「딸 땅콩 알레르기 있음」 과 「없음」(부정 표지가 다름), 「주차 3층」 과 「주차 5층」(숫자가 다름)은 후보다. 값이 같은 비슷한 글, 다른 주인, `GROUP` 과 `USER` 사이는 후보가 아니다. 거절한 쌍은 다시 만들지 않는다
- `MemoryEmbeddingRefresherTest` 에 새로 쓴 항목에 대해서만 탐지가 돌고, 임베딩이 꺼져 있으면 돌지 않는 경우

## 검증

```bash
cd backend && ./gradlew test --tests '*SupersedeCandidates*' --tests '*MemoryEmbeddingRefresherTest' --tests '*MemoryLinkServiceTest'
cd backend && ./gradlew archTest checkstyleMain checkstyleTest
```

## 변경 파일

| 파일 | 변경 |
|---|---|
| `backend/src/main/java/com/bifos/assistant/memory/application/link/SupersedeCandidates.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/memory/application/link/MemoryLinkProperties.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/memory/application/embedding/MemoryEmbeddingRefresher.java` | 수정 |
| `backend/src/main/resources/application.yml` | 수정 |
| `backend/src/test/java/com/bifos/assistant/memory/link/SupersedeCandidatesTest.java` | 신규 |
| `backend/src/test/java/com/bifos/assistant/memory/embedding/MemoryEmbeddingRefresherTest.java` | 수정 |
