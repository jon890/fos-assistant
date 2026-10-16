# Phase 04. 대체 승인 화면과 「대체됨」

**Execution profile**: standard

## 목표

사람이 `/memory` 에서 대체 제안을 받아들이거나 거절하고, 대체된 항목을 보고 되돌린다. 「지금 화면」 이 그 카드로 가는 줄을 보인다. 대화의 제안 카드가 바깥 글을 읽은 대화의 제안인지 보인다.

**범위 외**: 대화 답 아래의 대체 카드(사용자 결정 전), 링크 그래프 보기.

## 컨텍스트

**근거 문서**: `docs/features/memory.md` 의 「기억 사이의 연결」(화면), 「기억 화면」, 「대화에 보이는 것」.

- API: `memory/presentation/MemoryController`(`/api/v1/memories`, `accept`, `reject` 본보기), `MemoryDtos.MemoryView`
- 화면: `web/src/components/memory/memory-list.tsx`(「검토할 기억」, 「알고 있는 것」), `memory-proposal.tsx`(제안 카드 본보기), `web/src/components/chat/memory-capture-list.tsx` 의 `ProposalCard`, `web/src/lib/memory-capture-api.ts`
- 「지금 화면」: `attention/application/MemoryProposalCandidates`(NEEDS_ME 카드), `web/src/lib/attention.ts`
- 화면 규칙과 공통 부품은 `web/AGENTS.md` 를 따른다. 모델이 쓴 제목은 평문으로만 그린다(ADR-009)

## 작업 항목

### 1. 링크 API

- API(`MemoryController` 나 새 `MemoryLinkController`, DTO 는 `MemoryDtos`):
   - `GET /api/v1/memory-links/pending` → `[{id, newMemory:{id,title}, oldMemory:{id,title}, origin, createdAt}]`. 고칠 수 있는 옛 항목의 `PROPOSED` `SUPERSEDES` 만
   - `POST /api/v1/memory-links/{id}/accept`, `/reject`, `/revoke`. 남의 줄과 없는 줄은 404, 상태가 맞지 않으면 409
   - `MemoryView` 에 `supersededBy: {linkId, memoryId, title} | null` 을 더한다

### 2. 화면

- 화면:
   - `memory-supersede-proposal.tsx` 신규: 「새 기억이 옛 기억을 대신합니다」, 두 제목, [받아들이기] [거절]. 「검토할 기억」 의 위에 보인다
   - 「알고 있는 것」 의 대체된 줄에 「대체됨」 배지와 「대신한 기억: <제목>」, 고칠 수 있는 사람에게 [되돌리기]
   - `memory-capture-list.tsx` 의 제안 카드에 `externalRead` 면 「바깥 글을 읽은 대화에서 나온 제안입니다」 한 줄

### 3. 「지금 화면」 의 할 일 줄

- 「지금 화면」: `MemoryProposalCandidates` 가 대체 제안도 NEEDS_ME 카드로 세고, 링크는 `/memory` 다

### 4. 이 phase 를 검증하는 테스트

- `MemoryLinkControllerTest`(또는 `MemoryControllerTest`)에 목록, 받아들이기, 거절, 되돌리기, 남의 줄 404, 이미 받아들인 줄의 다시 받아들이기 409
- `test/browser/memory-supersede.spec.ts` 신규: 제안 카드에서 받아들이면 옛 줄이 「대체됨」 이 되고, [되돌리기] 를 누르면 배지가 사라진다. 제목이 글자 그대로 보인다
- `test/browser/memory-capture.spec.ts`(있는 spec)에 `externalRead` 문구
- `MemoryProposalCandidatesTest` 에 대체 제안이 NEEDS_ME 로 세지는 경우

## 검증

```bash
cd backend && ./gradlew test --tests '*MemoryLink*' --tests '*MemoryControllerTest' --tests '*MemoryProposalCandidates*'
cd web && npm run lint && npm run typecheck
cd web && npm run test:browser -- memory-supersede memory-capture
```

기대: 모두 통과.

## 변경 파일

| 파일 | 변경 |
|---|---|
| `backend/src/main/java/com/bifos/assistant/memory/presentation/MemoryLinkController.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/memory/presentation/MemoryDtos.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/attention/application/MemoryProposalCandidates.java` | 수정 |
| `web/src/lib/memory-link-api.ts` | 신규 |
| `web/src/components/memory/memory-supersede-proposal.tsx` | 신규 |
| `web/src/components/memory/memory-list.tsx` | 수정 |
| `web/src/components/chat/memory-capture-list.tsx` | 수정 |
| `web/src/lib/memory-capture-api.ts` | 수정 |
| `test/browser/memory-supersede.spec.ts` | 신규 |
| `backend/src/test/java/com/bifos/assistant/memory/MemoryLinkControllerTest.java` | 신규 |
