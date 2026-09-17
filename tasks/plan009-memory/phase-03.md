# Phase 03. 화면에서 Memory 를 보고 제안을 받아들인다

**Execution profile**: standard

## 목표

개인 Memory 와 가족 공용 Memory 를 보고 고치는 화면을 만든다.
에이전트가 제안한 항목을 사람이 받아들이거나 물리는 자리를 그 화면에 둔다.

**범위 외**:
에이전트가 실제로 제안을 만들어 내는 경로는 이 phase 가 마지막 작업 항목으로 만든다.
`career` 의 Hermes 내장 memory 를 옮기는 것은 phase-04 가 한다.

## 컨텍스트

Memory 는 사람이 받아들인 것만 저장된다.
손으로만 적게 하면 쓰이지 않고, 에이전트가 알아서 쌓으면 틀린 사실이 남아
그 뒤의 모든 답을 함께 틀리게 만든다.

실측한 근거가 있다. 작업 영역이 사람이 등록해야만 쓰이는 구조였고,
등록된 행이 0 인 채로 남았다. 제안하는 길이 없으면 같은 일이 반복된다.

화면 구조와 색 규칙은 이미 정해져 있다.
색을 인라인으로 적지 않고 `globals.css` 의 `@theme` 토큰을 Tailwind 클래스로 쓴다.
브랜드 색을 본문 글자에 쓰지 않는다.

**근거 문서**: `docs/adr/ADR-012-memory-는-사람이-승인한-것만-남는다.md`,
`docs/code-architecture.md` 의 「web 화면 구조」 와 「Memory」 절,
`docs/data-schema.md` 의 「memory」 절

## 의도 메모

- 제안을 대화 화면 안에서 받는 방식을 버렸다.
  답을 읽는 중에 승인 단추가 끼면 읽는 흐름이 끊긴다.
  모아서 한 화면에서 처리하게 한다.
- 제안이 쌓이기만 하고 처리되지 않으면 손으로 적는 것과 같은 자리로 돌아간다.
  그래서 처리하지 않은 제안 수를 머리에 보인다.

## 작업 항목

### 1. `/memory` 경로를 만든다

`web/src/app/memory/page.tsx` 다.
`/usage` 화면이 쓰는 배치와 같은 모양을 따른다.

절이 셋이다.

| 절 | 담는 것 |
| --- | --- |
| 받을지 정할 것 | `PROPOSED` 인 항목. 하나도 없으면 이 절을 그리지 않는다 |
| 우리 가족이 함께 아는 것 | `FAMILY` 이고 `ACCEPTED` 인 항목 |
| 나에 대해 아는 것 | `USER` 이고 `ACCEPTED` 인 항목 |

### 2. `web/src/components/memory/` 아래에 부품을 만든다

| 파일 | 하는 일 |
| --- | --- |
| `memory-list.tsx` | 한 절의 목록 |
| `memory-item.tsx` | 항목 하나. 고치기와 지우기 |
| `memory-proposal.tsx` | 제안 하나. 받아들이기와 물리기 |
| `memory-form.tsx` | 손으로 새 항목 적기 |

`memory-form.tsx` 는 `scope` 를 반드시 고르게 한다.
**기본으로 선택된 값을 두지 않는다.** 고르지 않으면 보내기 단추를 잠근다.
`agent-form.tsx` 가 `visibility` 를 다루는 방식과 같다.

`FAMILY` 를 고르는 것은 관리자에게만 보인다.
구성원에게는 그 선택지를 그리지 않는다. 서버도 거절하지만 화면에서 먼저 막는다.

### 3. 서버 라우트를 만든다

`web/src/app/api/memories/route.ts` 와 `web/src/app/api/memories/[id]/route.ts` 다.
`web/src/app/api/workspaces/route.ts` 가 쓰던 모양을 따른다.
그 파일은 plan008 이 지웠으므로 `web/src/app/api/chat/route.ts` 를 본보기로 삼는다.

브라우저가 Control Plane 토큰을 갖지 않는다.
서버 라우트가 세션에서 메일 주소를 꺼내 매 요청마다 토큰을 새로 만든다.

### 4. 머리에 `/memory` 를 더한다

지금 머리에 `사용량` 과 `관리` 가 있다.
`기억` 을 더한다.

처리하지 않은 제안이 있으면 그 수를 함께 보인다.
0 이면 아무것도 보이지 않는다.

### 5. 에이전트가 제안하는 경로를 만든다

실행이 끝난 뒤 그 답에서 남길 만한 사실을 뽑아 `PROPOSED` 로 넣는다.

`backend/src/main/java/com/bifos/assistant/memory/application/MemoryProposer.java` 를 만든다.

```java
/**
 * 실행이 끝난 답에서 남길 사실을 제안한다.
 *
 * <p>제안은 사람이 받아들여야 저장 대상이 된다. 여기서 만든 것은 모두 PROPOSED 다.
 */
@Service
public class MemoryProposer {

    /** 제안을 만들지 못하면 아무것도 만들지 않는다. 실행을 실패시키지 않는다. */
    public void proposeFrom(CurrentUser user, AgentExecution execution, String answer);
}
```

**뽑는 방법은 이 phase 에서 가장 단순한 것으로 둔다.**
답 안에 사용자가 알려준 사실이 있는지를 같은 에이전트에게 한 번 더 물어 뽑는다.
그 호출도 실행 기록에 남으므로 `parentExecutionId` 를 그 실행의 `id` 로 준다.
`rootExecutionId` 도 같다. plan008 의 phase-02 가 그 칸을 만들어 두었다.

**무엇을 뽑을지의 기준을 그 프롬프트에 못 박는다.**

남길 것은 사람에 관한 사실이다. 다음에도 쓰일 선호, 상황, 결정이다.

남기지 않을 것을 함께 적는다.

- 커밋 해시, 브랜치 이름, 파일 경로, worktree 경로
- 어느 작업이 어디까지 진행됐는지
- 이번 대화에서만 쓰이고 끝나는 것
- 이미 저장된 것과 같은 사실

근거가 있다. `career` profile 의 `MEMORY.md` 가 이 기준 없이 자동으로 쌓여
39줄 10,192 바이트가 됐고, 그 내용이 대부분 지난 세션의 작업 로그다.
`커밋/푸시 9484996 ... 완료` 같은 줄과 `[score=0.815 recalls=0 ...]` 같은
메타데이터가 본문에 섞여 있다.
그 10,192 바이트가 「오늘 날씨 좋다」 같은 한 문장에도 매번 함께 실린다.

**한 번에 하나만 제안한다.** 여러 개를 한꺼번에 내면 사람이 훑어 넘기게 된다.
뽑을 것이 없으면 아무것도 만들지 않는다. 그것이 정상이다.

제안을 만드는 것이 실패해도 대화는 성공으로 끝난다.
`try` 로 감싸고 `log.warn` 만 남긴다.

**제안을 만들지 말지는 설정으로 끈다.** `assistant.memory.propose.enabled` 다.
기본값은 `false` 로 둔다.
이 경로가 매 대화마다 LLM 을 한 번 더 부르므로, 실제 비용과 제안의 질을 보고 켠다.
`context_chars` 와 같은 이유다. 숫자를 보고 정한다.

켤 때 무엇을 견줄지 미리 정해 둔다.

| 재는 것 | 어디서 |
| --- | --- |
| 제안 호출이 더 쓰는 토큰 | 그 호출의 실행 기록. `parentExecutionId` 로 묶인다 |
| 제안 중 받아들여진 비율 | `memory` 의 `status` |
| 주입한 글자 수의 변화 | 실행의 `context_chars` |

받아들여진 비율이 낮으면 기준을 고치거나 이 경로를 끈다.
제안을 많이 만드는 것이 목표가 아니다.

### 6. 이 phase 를 검증하는 테스트

`backend/src/test/java/com/bifos/assistant/memory/MemoryProposerTest.java` 를 새로 만든다.

- **정상 경로**: 제안이 만들어지면 `PROPOSED` 이고 `proposedByExecutionId` 가 그 실행을 가리킨다
- **이 phase 가 다루는 실패**: 제안을 만드는 도중 오류가 나도 예외가 밖으로 나가지 않는다
- 설정이 꺼져 있으면 아무것도 만들지 않고 LLM 을 부르지도 않는다

`test/browser/memory.spec.ts` 를 새로 만든다.

- `/memory` 가 세 절을 그리고, 제안이 없으면 첫 절이 없다
- 새 항목을 적을 때 `scope` 를 고르기 전에는 보내기 단추가 잠겨 있다
- `mobile` 과 `desktop` 두 폭에서 가로로 넘치지 않는다

## 검증

```bash
# cwd: 저장소 root
cd backend && ./gradlew test
cd web && pnpm typecheck && pnpm build
cd web && pnpm test:browser
node test/e2e/run.ts
```

```bash
# cwd: 저장소 root
grep -rn 'style={{' web/src/components/memory/ | grep -iE 'background|color|border' && echo "실패: 색 인라인 스타일" || echo "통과"
```

브라우저에서 390px 과 1280px 을 직접 열어 본 결과를 보고에 적는다.

## Critical Files

| 파일 | 변경 |
|---|---|
| `web/src/app/memory/page.tsx` | 신규 |
| `web/src/app/api/memories/route.ts` | 신규 |
| `web/src/app/api/memories/[id]/route.ts` | 신규 |
| `web/src/components/memory/memory-list.tsx` | 신규 |
| `web/src/components/memory/memory-item.tsx` | 신규 |
| `web/src/components/memory/memory-proposal.tsx` | 신규 |
| `web/src/components/memory/memory-form.tsx` | 신규 |
| `web/src/components/` 의 머리 부품 | 수정 |
| `backend/src/main/java/com/bifos/assistant/memory/application/MemoryProposer.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/chat/application/ChatService.java` | 수정 |
| `backend/src/main/resources/application.yml` | 수정 |
| `backend/src/test/java/com/bifos/assistant/memory/MemoryProposerTest.java` | 신규 |
| `test/browser/memory.spec.ts` | 신규 |
