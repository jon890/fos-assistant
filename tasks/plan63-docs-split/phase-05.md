# Phase 05. 두 문서가 함께 적던 내용을 한 곳에 남긴다

**Execution profile**: deep

## 목표

같은 사실을 두 곳이 적은 문단을 한 곳에만 남기고, 헤딩과 문서의 머리를 읽을 수 있게 정리한다.
두 곳이 따로 고쳐지며 어긋나는 것을 막기 위해서다.

**범위 외**

- ADR 본문을 줄이는 일은 하지 않는다. 결정의 근거를 건드리므로 따로 본다.
- `docs/hermes/` 안에서 여러 파일이 되풀이하는 설명(`disabled_toolsets`, `command_allowlist`, cache 칸, 포트 오류, 스킬 우선순위)과 부하 측정 표는 이 phase 가 줄이지 않는다.
- `AGENTS.md` 세 파일을 줄이는 일은 phase 07, 08, 09 다. 이 phase 는 `docs/` 쪽에서 `AGENTS.md` 와 겹친 것만 지우고 링크한다.

## 컨텍스트

앞 phase 가 `docs/flow.md` 와 `docs/code-architecture.md` 의 같은 주제 절을 `docs/backend/*.md`, `docs/frontend/*.md` 한 파일에 모았다.
그래서 한 파일 안에 「구조」 를 적은 절과 「흐름」 을 적은 절이 나란히 있고, 같은 표와 같은 단계 목록이 두 번 나온다.

남길 쪽을 고르는 기준이다.

1. 코드나 설정 파일이 소유자면 문서는 가리키기만 한다.
2. 둘 가운데 코드와 맞는 쪽을 남긴다. 둘 다 맞으면 더 자세한 쪽을 남긴다.
3. 지운 자리에는 남긴 절을 가리키는 한 줄을 둔다. 같은 파일 안이고 바로 위아래면 그 한 줄도 두지 않는다.

**지우기 전에 한쪽에만 있는 사실이 있는지 본다.** 있으면 남기는 쪽으로 옮긴 뒤 지운다.

**근거 문서**: `docs/code-architecture.md` 의 「경계」 절, `docs/connectors.md`, `AGENTS.md` 의 「주제가 아니라 내용으로 나눈다」 절

## 의도 메모

- 중복을 인용 블록이나 접힌 블록으로 남기는 안은 버렸다. 남아 있으면 다시 어긋난다.
- 헤딩 이름을 바꾸면 그 절을 「」 로 가리키는 코드 주석이 깨진다. `test/unit/doc-references.test.ts` 가 잡는다. 코드 주석이 가리키는 헤딩은 이름을 바꾸지 않는 쪽을 먼저 고르고, 바꿔야 하면 주석도 함께 고친다.
- 문서가 400줄을 넘으면 이 phase 에서 줄인다. 중복을 지운 뒤에도 넘으면 주제 경계에서 파일을 나누고 `docs/README.md` 에 줄을 더한다.

## 작업 항목

### 1. 한 파일 안에 모인 중복을 지운다

| 파일 | 겹친 것 | 남길 것 |
| --- | --- | --- |
| `docs/backend/connector-tool-policy.md` | 도구 호출 판정표와 승인 전이가 「도구 정책」 쪽과 흐름 쪽(「커넥터 도구를 부를 때」, 「승인이 필요한 호출」)에 둘 다 있고 사유 코드(`RISK_NOT_OPEN`, `ARGS_TOO_LARGE`)가 어긋난다 | 판정표는 「도구 호출 판정」 하나만 남긴다. 사유 코드는 backend 의 판정 enum 과 대조해 맞춘다. 흐름 쪽에는 그림과, 판정표에 없는 줄만 남긴다 |
| `docs/backend/connector-install.md` | 커넥터 등록 순서가 「설치와 실패 처리」 와 「커넥터 연결」 에 둘 다 있다 | 단계 목록은 한쪽만 남긴다 |
| `docs/backend/mcp-caller.md` | session 표가 구조 쪽과 흐름 쪽에 둘 다 있다. MCP 요청자 판정과 `artifact_write` 계약이 Hermes 문서에서 온 절과 겹친다 | 표는 하나만 남긴다. Control Plane 자체의 계약은 이 파일이 갖고, Hermes 가 어떻게 동작하는지만 `docs/hermes/` 에 남긴다 |
| `docs/backend/agent.md` | 에이전트 만들기 단계가 「에이전트 만들기와 지우기」 와 「에이전트를 만들 때」 에 둘 다 있다 | 단계 목록은 한쪽만 남긴다 |
| `docs/backend/memory.md` | Memory 층 구분이 둘 있다 | 하나만 남긴다 |
| `docs/backend/agent-delegation.md` | `agent_*` 도구 계약이 구조 쪽, 흐름 쪽, `docs/hermes/delegation.md` 에 있다. `AgentDelegationService` 를 설명한 표 칸 하나가 한 문단 1500자다 | 도구 계약은 이 파일이 갖는다. 그 칸은 경계 문장만 남기고 판정 순서는 흐름 절을 가리킨다 |
| `docs/backend/conversation.md`, `docs/backend/turn-control.md`, `docs/backend/artifact.md`, `docs/backend/skill.md`, `docs/backend/attachment.md`, `docs/backend/people.md` | 분기 표와 응답 코드 표 일부가 구조 쪽과 흐름 쪽에 둘 다 있다 | 파일마다 두 쪽을 나란히 읽고 같은 표는 하나만 남긴다 |

### 2. 다른 파일과 겹친 것을 소유자 쪽에 남긴다

| 겹친 두 곳 | 소유자 | 할 일 |
| --- | --- | --- |
| `docs/frontend/structure.md` 의 디렉터리 트리, 테마 토큰 규칙, 마크다운 규칙과 `web/AGENTS.md` 의 같은 내용 | 코드 규칙은 `web/AGENTS.md` | `structure.md` 에서 트리를 지우고 설명 두 문장만 남긴다. 토큰과 마크다운 규칙은 `web/AGENTS.md` 를 링크한다. `web/AGENTS.md` 쪽 트리는 phase 08 이 지운다 |
| `docs/code-architecture.md` 「Hermes 쪽 코드」 의 설치 묶음과 운영 값 표와 `hermes/README.md` 의 같은 표 | `hermes/README.md` | 두 표의 줄을 합쳐 `hermes/README.md` 에 둔다. `FOS_CTX_SUBAGENT_URL` 처럼 한쪽에만 있는 줄을 빠뜨리지 않는다. `code-architecture.md` 에는 경계를 설명하는 문장과 링크만 남긴다. 「Hermes 쪽 코드 (`hermes/`)」 헤딩은 `hermes/README.md` 가 「」 로 가리키므로 이름을 그대로 둔다 |
| `docs/backend/packages.md` 의 층 방향 규칙과 `backend/AGENTS.md` 의 같은 세 줄 | `ArchitectureRules.java` 가 강제한다 | `packages.md` 에 남기고 강제하는 규칙 이름을 적는다. `backend/AGENTS.md` 쪽은 phase 07 이 지운다 |
| `docs/backend/connector-tool-policy.md` 의 `fos-ctx` hook 처리 표와 `hermes/README.md` 의 같은 표 | plugin 옆의 `hermes/README.md` | 빠진 줄을 `hermes/README.md` 에 합치고 문서 쪽은 링크한다 |
| `docs/connectors.md` 「저장과 비밀값」 의 비밀 앞부분 규칙과 `docs/backend/schema/connector.md` 의 같은 규칙 | 저장 칸 설명은 `schema/connector.md` | `connectors.md` 에는 규칙의 뜻 한 줄과 링크만 남긴다 |
| `docs/connectors.md` 「Control Plane API」 표의 `model-defaults` 줄 | `docs/model-tiers.md` | 커넥터 경로가 아니다. 표에서 지우고 `model-tiers.md` 를 가리킨다 |
| `docs/hermes/profiles.md` 의 대시보드 plugin 경로 표와 `hermes/README.md` 의 같은 표 | `hermes/README.md` | 경로 목록은 `hermes/README.md` 가 갖는다. `profiles.md` 는 Hermes 쪽 동작만 적고 목록을 링크한다. `profiles.md` 의 「Control Plane 이 부르는 대시보드 plugin 경로」 헤딩은 코드 주석이 「」 로 가리키므로 남긴다 |
| `docs/backend/connector-install.md` 의 「배포한 뒤 확인할 것」 목록 | `docs/hermes/connector-policy.md` 의 같은 이름 절 | 그 절로 옮기고 링크한다 |

### 3. 계약이 아닌 것을 덜어 낸다

| 파일 | 무엇 | 할 일 |
| --- | --- | --- |
| `docs/backend/*.md`(구조 쪽에서 온 절) | `더한다`, `넓혀 쓴다`, `열었다` 같은 변경 지시 말투 | `갖는다`, `쓴다`, `있다` 로 고친다. 사실이 바뀌지 않게 한다 |
| `docs/backend/artifact.md` | 없앤 것과 검토 과정을 적은 문단 | 결정의 까닭이면 `docs/adr/ADR-027-*.md` 의 맥락으로 옮기고, 아니면 지운다 |
| `docs/backend/memory.md` 「이 왕복은 비싸다」 의 손익분기 측정표 | 흐름이 아니라 근거다 | `docs/hermes/tools-and-skills.md` 의 비용을 다루는 절로 옮기고 링크한다 |
| `docs/frontend/activity.md` 「도구를 보이는 말」 의 문구 표 | `web/src/lib/tool-label.ts` 와 1:1 이다 | 표를 지우고 규칙만 남긴 뒤 그 파일을 가리킨다 |
| `docs/frontend/shell.md`, `docs/frontend/chat.md` | 단축키와 길이 상한을 숫자로 옮겨 적은 줄 | 코드 상수와 같은 값이면 규칙만 남긴다 |
| `docs/backend/schema/*.md` | 마이그레이션 이력을 길게 적은 문단 | 칸의 뜻에 필요한 한 줄로 줄인다. 같은 설명이 마이그레이션 주석에 있다 |

### 4. 헤딩과 문서 머리를 정리한다

- **h1 바로 아래에 소개 문단을 둔다.** `docs/backend/*.md`, `docs/frontend/*.md`, `docs/backend/schema/*.md`, `docs/hermes/fos-ctx.md`, `docs/hermes/skills.md` 마다 그 파일이 무엇을 소유하는지 두세 줄로 적는다.
- **h1 과 같은 글의 `##` 를 푼다.** 그 `##` 줄을 지우고 그 아래를 머리 글로 삼거나, `##` 에 「구조」 처럼 구분되는 이름을 준다. 코드 주석이 「」 로 가리키는 헤딩(「backend 패키지」, 「중지」, 「사용자를 더할 때」, 「다른 에이전트에게 맡기기」, 「결과물 파일」, 「페르소나」)은 `test/unit/doc-references.test.ts` 가 통과하는 쪽으로 정한다. h1 도 헤딩이라 h1 의 글이 같으면 통과한다.
- **되풀이되는 헤딩에 주제를 붙인다.** `갈리는 지점`, `어느 클래스가 무엇을 하나`, `경로` 가 한 파일에 두 번 이상 나오면 「스킬 저장이 갈리는 지점」 처럼 앞에 주제를 붙인다. 한 번만 나오는 파일은 그대로 둔다.
- **방향어를 뺀다.** `아래`, `위`, `다음 절` 이 다른 파일로 간 절을 가리키면 절 이름과 링크로 바꾼다.
- **`docs/hermes/concurrency.md`**: h1 다음이 `###` 다. 헤딩을 한 단계씩 올린다. 글은 바꾸지 않는다.
- **`docs/hermes/runs-api.md`**: h1 바로 아래에 같은 글의 `## Runs API` 가 있다. 그 줄을 지우고 그 절의 `###` 를 `##` 로 올린다.
- **`docs/connectors.md`**: 다른 절을 가리킨 이름이 실제 헤딩과 다른 자리를 맞춘다.
- **`docs/backend/packages.md`**: 패키지 표보다 앞에 있는 `connector` 세부 설명을 `### connector` 소절로 표 아래에 내린다.

### 5. `test/unit/doc-references.test.ts` 에 헤딩 검사를 더한다

테스트 하나를 더한다. 이름은 `한 문서 안에 같은 헤딩이 두 번 나오지 않는다` 다.

- 대상: `docs/*.md`, `docs/backend/*.md`, `docs/backend/schema/*.md`, `docs/frontend/*.md`. `docs/adr/` 와 `docs/hermes/` 는 넣지 않는다
- 파일마다 헤딩 줄에서 `#` 을 뗀 글을 모으고, 같은 글이 둘 이상이면 `파일  「헤딩」` 을 실패 목록에 넣는다. 코드 펜스 안의 `#` 줄은 헤딩이 아니다
- 순수 함수 `duplicateHeadings(markdown)` 로 빼고 문자열 입력으로 확인한다. 정상: 헤딩이 모두 다르면 빈 배열. 실패: `## 갈리는 지점` 이 둘이면 `갈리는 지점` 하나를 낸다. 코드 펜스 안의 `# 주석` 둘은 세지 않는다

같은 헤딩이 되풀이되면 앵커가 순서 번호에 묶여, 절 하나를 더하거나 빼면 링크가 다른 절로 간다.

헤딩 이름을 바꾸거나 절을 지우면 그 절을 「」 로 가리키는 코드 주석이 깨진다.
같은 파일의 기존 검사가 그 자리를 `파일:줄` 로 낸다. 나온 주석의 경로와 절 이름을 새 헤딩에 맞춘다.
Java 주석을 고쳤으면 `scripts/quality.sh check` 를 돌린다.

## 검증

```bash
# cwd: 저장소 root
# 1. 한 파일이 400줄을 넘지 않는다
wc -l docs/*.md docs/backend/*.md docs/backend/schema/*.md docs/frontend/*.md \
  docs/hermes/delegation.md docs/hermes/fos-ctx.md docs/hermes/tools-and-skills.md \
  docs/hermes/skills.md docs/hermes/runs-api.md docs/hermes/connector-policy.md

# 2. 한 파일 안에 같은 헤딩이 두 번 나오지 않는다. 출력이 없어야 한다
for f in docs/*.md docs/backend/*.md docs/backend/schema/*.md docs/frontend/*.md; do
  grep '^#' "$f" | sed 's/^#* //' | sort | uniq -d | sed "s|^|$f: |"
done

# 3. 링크와 앵커와 헤딩 단계. $DOCS_CHECK_DIR 은 docs-check 스킬 번들 경로다
python3 "$DOCS_CHECK_DIR/scripts/static_check.py" docs/adr docs
python3 "$DOCS_CHECK_DIR/scripts/static_check.py" docs/adr web/AGENTS.md
python3 "$DOCS_CHECK_DIR/scripts/static_check.py" docs/adr backend/AGENTS.md

# 4. 코드가 가리키는 절 이름, 품질 검사, 공개 정보 검사
node --test 'test/unit/**/*.test.ts'
scripts/quality.sh check
scripts/check-public-safe.sh
```

기대값: 1번 모든 파일 400줄 이하. 2번 출력 없음. 3번은 `INDEX_DESYNC` 말고 위반 0건. 4번 종료 코드 0.
`node --test` 는 `test/unit/doc-references.test.ts` 를 포함한다.

결과 보고에 지운 중복마다 「어느 쪽을 남겼는가」 를 표로 남긴다.

## 변경 파일

| 파일 | 변경 |
|---|---|
| `docs/*.md` | 수정 |
| `docs/backend/*.md` | 수정 |
| `docs/backend/schema/*.md` | 수정 |
| `docs/frontend/*.md` | 수정 |
| `docs/hermes/*.md` | 수정 |
| `docs/adr/ADR-027-*.md` | 수정 |
| `hermes/README.md` | 수정 |
| `backend/src/**/*.java` | 수정 |
| `test/unit/doc-references.test.ts` | 수정 |
