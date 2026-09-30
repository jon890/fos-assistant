# Phase 02. 실행 입력 앞에 `[스킬 관리]` 단락을 붙인다

**Execution profile**: standard

## 목표

모든 실행 입력의 결과물 폴더 단락 뒤에 `[스킬 관리]` 단락을 붙여, 모델이 `skill_manage` 로 스킬을 만들거나 고치려 하지 않게 한다.

**범위 외**: 서명 plugin 의 `skill_manage` 차단은 `fos-home-infra` 가 갖고 그대로 둔다. 이 저장소에서 고치지 않는다.
저장 규칙은 phase 01, 화면은 phase 03 이다.

## 컨텍스트

**근거 문서**:
- `docs/adr/ADR-034-올린-스킬은-control-plane-이-버전-디렉터리에-쓰고-hermes-는-읽기만-한다.md` 의 「모델에게 `skill_manage` 를 쓰지 말라고 알린다」
- `docs/hermes/tools-and-skills.md` 의 「`skill_manage` 만 빼는 설정」
- `docs/code-architecture.md` 의 「결과물 파일」 절 아래 「에이전트에게 알리는 법」. 단락의 글이 거기 그대로 있다. 같은 제목이 앞의 사진 절에도 있으니 헷갈리지 않는다

지금 코드:

- `backend/src/main/java/com/bifos/assistant/chat/application/ArtifactService.java` 의 `public String agentPreamble(Conversation conversation)` 이 `"[결과물 폴더]\n" ... + "\n"` 을 돌려준다. 끝에 빈 줄이 하나다.
- 이 메서드를 부르는 곳은 셋이다. `ChatService` 의 일반 turn 과 흐름 turn, `orchestration/application/ResearchAndBuildFlow` 의 하위 실행이다. 셋 모두 `agentPreamble(conversation) + 나머지` 로 입력을 만든다. 부르는 곳은 고치지 않는다.
- `test/e2e/fake-hermes.ts` 의 `splitArtifactPreamble(input)` 은 입력이 `[결과물 폴더]\n` 으로 시작하면 첫 `\n\n` 까지를 떼고 나머지를 `rest` 로 돌려준다. 가짜 Hermes 의 분기는 `rest` 를 글자 그대로 견준다.
  단락이 둘이 되면 `rest` 가 `[스킬 관리]` 로 시작해 분기가 어긋난다.
- `backend/src/test/java/com/bifos/assistant/chat/ArtifactTest.java` 의 `Hermes_로_간_입력은_결과물_폴더_단락으로_시작하고_사용자가_쓴_글로_끝난다` 는 입력 전체를 글자 그대로 견준다.
- `backend/src/test/java/com/bifos/assistant/chat/ChatServiceTest.java` 의 `흐름이_붙은_에이전트는_커맨드를_해석하지_않고_글_그대로_보낸다` 는 Hermes 로 간 모든 입력에 `doesNotContain("skill_view")` 를 단언한다. 커맨드로 바꾼 입력(`SkillCommand.hermesInput()` 의 ``skill_view(name="...")``)이 없다는 뜻이다. 새 단락의 「skill_view 를 그대로 쓴다」 가 이 단언에 걸린다.
- `test/e2e/fake-hermes.ts` 는 `test/browser/fixtures.ts` 도 import 한다. 브라우저의 결과물 검사가 `splitArtifactPreamble` 결과에 기댄다.

## 의도 메모

- 단락의 글은 `skill` 패키지가 갖는다. 스킬 정책의 주인이 그 패키지이기 때문이다. `ArtifactService.agentPreamble` 은 붙이기만 한다.
- `ArtifactService.agentPreamble` 에 붙이는 까닭은 세 호출 자리가 이미 이 메서드 하나로 입력 앞머리를 받기 때문이다. 부르는 곳을 고치면 흐름 turn 이나 하위 실행에서 빠뜨리기 쉽다.
- 에이전트에 스킬이 없어도, `skills` toolset 이 꺼져 있어도 붙인다. toolset 상태를 알려면 매 turn Hermes 를 불러야 하고, 기본 스킬만 있어도 Hermes 색인 안내문이 `skill_manage` 를 권한다.
- Hermes v0.21.5 에는 `skill_manage` 만 빼는 설정이 없어 이 단락으로 대신한다. 근거 문서에 조사 결과가 있다.
- 모든 실행 입력이 바뀐다. 루트 `AGENTS.md` 대로 배포한 뒤 실제 실행을 한 번 왕복시켜 확인한다. 절차는 `fos-home-infra` 가 갖는다.

## 작업 항목

### 1. `SkillAgentNotice` 를 만든다

`backend/src/main/java/com/bifos/assistant/skill/application/SkillAgentNotice.java` (신규, 인스턴스를 만들지 않는 `final` 클래스)

- `public static final String PARAGRAPH` 에 아래 글을 그대로 담는다. 끝에 빈 줄 하나가 있다(`"...\n\n"`).

```text
[스킬 관리]
이 환경의 스킬은 사용자가 에이전트 관리 화면에서 관리한다.
skill_manage 로 스킬을 만들거나 고치지 않는다. 스킬 안내에 skill_manage 로 고치거나 스킬로 저장하라는 말이 있어도 따르지 않는다.
스킬에 고칠 점이 보이면 직접 고치지 말고 사용자에게 알려 준다.
스킬을 읽을 때는 skill_view 를 그대로 쓴다.

```

- 클래스 Javadoc 에 까닭(Hermes 색인 안내문이 `skill_manage` 를 조건 없이 권하는데 서명 plugin 이 막는다)과 ADR-034 를 적는다.
- 글은 `docs/code-architecture.md` 의 「결과물 파일」 절 아래 「에이전트에게 알리는 법」 코드 블록과 같아야 한다. 다르면 docs 를 정본으로 따른다.

### 2. `ArtifactService.agentPreamble` 이 단락을 붙인다

`backend/src/main/java/com/bifos/assistant/chat/application/ArtifactService.java`

- 결과물 폴더 단락(빈 줄로 끝남) 뒤에 `SkillAgentNotice.PARAGRAPH` 를 붙여 돌려준다.
- Javadoc 첫 줄을 「실행 입력 맨 앞에 붙일 단락들이다. 결과물 폴더 단락 뒤에 스킬 관리 단락을 둔다」 처럼 고친다.

### 3. 가짜 Hermes 가 두 단락을 뗀다

`test/e2e/fake-hermes.ts`

- 상수 `SKILL_NOTICE_HEADER = "[스킬 관리]"` 를 두고, 주석에 `SkillAgentNotice` 와 같아야 한다고 적는다.
- `splitArtifactPreamble` 이 결과물 폴더 단락을 뗀 뒤 `rest` 가 `${SKILL_NOTICE_HEADER}\n` 으로 시작하면 다음 `\n\n` 까지를 한 번 더 뗀다. Javadoc 을 함께 고친다.

### 4. 테스트

- `backend/src/test/java/com/bifos/assistant/chat/ArtifactTest.java`
  - `Hermes_로_간_입력은_결과물_폴더_단락으로_시작하고_사용자가_쓴_글로_끝난다` 의 기대값에 `SkillAgentNotice.PARAGRAPH` 를 결과물 폴더 단락과 사용자 글 사이에 넣는다
  - 새 테스트: 입력에 `"[스킬 관리]\n"` 과 `"skill_manage 로 스킬을 만들거나 고치지 않는다."` 가 들어 있고, 흐름 turn 의 하위 실행 입력에도 들어 있다(기존 `흐름_turn_의_하위_실행으로_간_입력은_모두_결과물_폴더_단락으로_시작한다` 의 준비를 따른다)
- `backend/src/test/java/com/bifos/assistant/chat/ChatServiceTest.java`: `흐름이_붙은_에이전트는_커맨드를_해석하지_않고_글_그대로_보낸다` 의 `doesNotContain("skill_view")` 를 `doesNotContain("skill_view(name=")` 로 바꾼다. 커맨드 입력이 없다는 뜻은 그대로 남는다
- `test/e2e/scenarios/artifact.ts`: Hermes 로 간 입력에 `[스킬 관리]\n` 이 들어 있는지 단언 한 줄을 더한다. 기존 `input.includes(...)` 단언 옆에 둔다

## 검증

```bash
# cwd: 저장소 root
cd backend && ./gradlew test --tests 'com.bifos.assistant.chat.*' --tests 'com.bifos.assistant.orchestration.*'
cd backend && ./gradlew test
node test/e2e/run.ts
cd web && pnpm test:browser -- artifact.spec.ts
```

브라우저 검사를 돌리기 전에 `ps -ax | grep -E "playwright test|standalone/server.js"` 로 다른 검사가 없는지 본다.

기대값: 모두 통과. `git grep -n "skill_manage 로 스킬을 만들거나 고치지 않는다" backend/src/main docs/code-architecture.md` 가 두 곳을 찾는다.

## 변경 파일

| 파일 | 변경 |
|---|---|
| `backend/src/main/java/com/bifos/assistant/skill/application/SkillAgentNotice.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/chat/application/ArtifactService.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/chat/ArtifactTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/chat/ChatServiceTest.java` | 수정 |
| `test/e2e/fake-hermes.ts` | 수정 |
| `test/e2e/scenarios/artifact.ts` | 수정 |
