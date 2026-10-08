# Phase 01. 받아들인 할 일의 출처 유지

**Execution profile**: deep

## 목표

에이전트 제안을 받아들여도 지금 화면에서 「대화에서」를 표시한다.

**범위 외**: 할 일 상태 전이, 제안 억제, 권한, 저장 스키마, Hermes 도구 변경.

## 컨텍스트

`FollowUpService.snapshots()`는 `FollowUp.proposedByAgent()`를 `FollowUpSnapshot.proposed`로 옮긴다. 이 값은 `proposed_by_execution_id != null`이며 수락 뒤에도 참이다.
`AttentionFollowUpRef.proposed`는 아직 받아들이지 않은 상태이다. 두 의미를 분리한다.

**근거 문서**: `docs/frontend/now.md`의 「항목 한 줄」, `docs/backend/follow-up.md`의 「API」, `docs/backend/attention.md`의 「API」.

## 의도 메모

- 상태 필드의 의미를 출처로 바꾸면 기존 응답 계약이 달라진다. 별도 `agentProposed`를 추가한다.
- `FOLLOW_UP_PROPOSED`와 `FOLLOW_UP_OPEN` trigger가 상태별 단추와 이유를 계속 정한다.
- 직접 만든 할 일은 대화를 연결해도 직접 더한 출처다. 대화 ID 유무로 출처를 추론하지 않는다.

## 작업 항목

### 1. 출처 전달과 표시

- `AttentionFollowUpRef` record 마지막에 `boolean agentProposed`를 추가한다. Javadoc은 실행 ID로 판정한 에이전트 출처라고 쓴다.
- `FollowUpAttentionSource.candidate`에서 상태 비교로 `proposed`를 유지하고 `row.proposed()`를 `agentProposed`로 전달한다.
- `AttentionDtos.FollowUpView`에 `boolean agentProposed`를 추가하고 `from`에서 같은 값을 매핑한다.
- `web/src/lib/attention.ts`의 응답 타입에 `agentProposed: boolean`을 추가하고 `originText`에서 이 값을 읽는다.
- `docs/backend/attention.md`의 followUp 응답 표에 칸과 두 boolean의 의미를 명시한다.
- `docs/frontend/now.md`에서 수락 뒤에도 출처가 유지되며 상태별 단추는 바뀐다고 명시한다.

### 2. 회귀 테스트

- `FollowUpAttentionSourceTest`에서 record 생성 인자를 갱신하고 제안 수락 전후 `proposed: true→false`, `agentProposed: true→true`, trigger 전이를 확인한다. 직접 만든 할 일은 출처가 거짓임을 확인한다. `AttentionDtos.ViewResponse.from`으로 응답 DTO를 만들고 두 값이 수락 전후에도 그대로 전달되는지 확인한다.
- `test/unit/attention.test.ts`에서 출처가 상태와 독립임을 확인한다. 수락한 제안은 「대화에서」이고 직접 만든 것은 「직접 더함」이다. 기존 fixture에 새 필드를 채운다.
- `test/browser/now.spec.ts`의 기존 제안 수락 시나리오에 수락 뒤 「대화에서」 유지와 「직접 더함」 부재를 추가하고 화면을 다시 열어 확인한다. 직접 더한 할 일의 출처 검사는 유지한다.

## 검증

저장소 루트에서 실행한다. 무거운 검사는 코디네이터가 지정한 heavy-lock으로 감싼다.

```bash
cd backend && ./gradlew test --tests com.bifos.assistant.attention.FollowUpAttentionSourceTest
node --test test/unit/attention.test.ts
pnpm --dir web typecheck
pnpm --dir web test:browser now.spec.ts --repeat-each=3 --retries=0
```

브라우저 검사는 빌드 서버와 두 폭에서 실행한다.

## 변경 파일

| 파일 | 변경 |
| --- | --- |
| `backend/src/main/java/com/bifos/assistant/attention/application/model/AttentionFollowUpRef.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/attention/application/FollowUpAttentionSource.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/attention/presentation/AttentionDtos.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/attention/FollowUpAttentionSourceTest.java` | 수정 |
| `web/src/lib/attention.ts` | 수정 |
| `test/unit/attention.test.ts` | 수정 |
| `test/browser/now.spec.ts` | 수정 |
| `docs/backend/attention.md` | 수정 |
| `docs/frontend/now.md` | 수정 |
