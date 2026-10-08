# Phase 01. 대화 메시지 타입을 별도 파일로 옮긴다

**Execution profile**: standard

## 목표

메시지 타입을 옮겨 답 표시 컴포넌트의 기존 파일 길이를 줄인다.

**범위 외**: 타입 내용, 실행 동작, API와 원문 열람 기능 변경.

## 컨텍스트

**근거 문서**: `docs/frontend/chat.md`의 「이번에 연 원문」.
`web/src/components/chat/message-bubble.tsx`는 415줄이며 기존 상한까지 길어졌다. 새 기능 추가 전에 기존 타입을 옮겨 그 타입을 쓸 자리를 만든다.

## 작업 항목

### 1. 메시지 타입 이동

기존 `MessageAttachment`, `MessageArtifact`, `MessageDelivery`, `Turn` 타입과 주석만 message-types.ts로 옮긴다. `ActivitySummary`는 type import를 사용한다.
message-bubble.tsx는 이 파일의 타입을 import하고 동일 이름을 re-export한다. 소비자 import 경로는 유지하며 런타임 동작은 바꾸지 않는다.

### 2. 기존 테스트와 타입 검사

`test/unit/message-versions.test.ts`의 기존 메시지 판 묶음 단위 테스트를 실행하고 web typecheck로 타입을 쓰는 소비자의 호환성을 확인한다. 새 동작이 없어 테스트를 더하지 않는다.

## 검증

```bash
pnpm --dir web typecheck
node --test test/unit/message-versions.test.ts
node scripts/check-file-length.mjs
pnpm --dir web lint
pnpm --dir web format:check
```

## 변경 파일

| 파일 | 변경 |
| --- | --- |
| `web/src/components/chat/message-types.ts` | 신규 |
| `web/src/components/chat/message-bubble.tsx` | 수정 |
| `test/unit/message-versions.test.ts` | 수정 |

기존 테스트가 그대로 타입 이동을 검증하므로 테스트 본문 변경이 필요 없으면 이 파일은 staged 대조 경고로 남기고 이유를 보고한다.
