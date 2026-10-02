# Phase 01. 문서를 공통, backend, frontend 로 옮긴다

**Execution profile**: deep

## 목표

`docs/flow.md`, `docs/code-architecture.md`, `docs/connectors.md`, `docs/data-schema.md` 와 `docs/hermes/` 의 큰 파일 셋을 주제별 파일로 옮긴다.
한 주제의 구조와 흐름을 한 파일에서 읽게 하기 위해서다.

**이 phase 는 절을 통째로 옮기기만 한다. 문장을 고치지 않는다.**
바뀌어도 되는 줄은 아래 「바뀌어도 되는 줄」 에 적은 것뿐이다.

**범위 외**

- 색인 `docs/README.md` 를 쓰는 일과 `AGENTS.md` 의 읽기 순서 표를 바꾸는 일은 phase 02 다.
- 코드와 어긋난 서술, 중복, 헤딩 이름, ADR 표시를 고치는 일은 phase 04 부터 06 까지다.
- `docs/adr/` 의 파일은 옮기지 않는다. 그 안의 링크만 새 경로로 고친다.

## 컨텍스트

지금 한 파일이 400줄을 넘는 문서가 여섯이다. `docs/flow.md`, `docs/code-architecture.md`, `docs/data-schema.md`, `docs/hermes/delegation.md`, `docs/hermes/tools-and-skills.md`, `docs/connectors.md` 다.
`flow.md` 와 `code-architecture.md` 가 같은 주제를 따로 적다 어긋난 곳이 열 군데가 넘는다.
같은 주제의 절을 한 파일에 모으면 겹친 문단이 나란히 놓이고, 뒤 phase 가 그것을 정리한다.

층은 디렉터리로, 주제는 파일로 나눈다.

- `docs/` 바로 아래: 두 층에 걸친 공통 문서
- `docs/backend/`: Control Plane 의 패키지와 흐름
- `docs/backend/schema/`: 저장 모델
- `docs/frontend/`: 화면
- `docs/hermes/`: 그대로 둔다. 외부 런타임의 동작을 적는 자리다
- `docs/adr/`: 그대로 둔다

`docs/code-architecture.md`, `docs/flow.md`, `docs/connectors.md` 는 이름과 자리를 남긴다. 공통으로 남는 내용이 있다.
`docs/data-schema.md` 는 없어진다.

**옛 경로에 안내 파일을 두지 않는다.** 가리키는 자리를 이 커밋에서 전부 고친다.

**근거 문서**: `AGENTS.md` 의 「주제가 아니라 내용으로 나눈다」 절, `docs/code-architecture.md` 의 「경계」 절

## 의도 메모

- ADR 을 층 디렉터리로 옮기는 안은 버렸다. ADR 의 절반 이상이 두 층에 걸치고 ADR 경로를 가리키는 자리가 문서와 코드에 500곳이 넘는다.
- 옛 경로에 「여기로 옮겼다」 파일을 두는 안은 버렸다. 그 파일이 둘째 색인이 되고, 절 이름으로 가리키는 참조는 고쳐 주지 못한다.
- 손으로 잘라 붙이지 않는다. 아래 이동표를 입력으로 받는 일회용 스크립트로 조립한다. 스크립트는 저장소에 넣지 않는다.
- 이 phase 가 끝난 직후의 파일은 소개문 없이 절이 이어져 어색하다. 그것은 뒤 phase 가 고친다. 여기서 다듬지 않는다.

## Blocked 조건

- 이동표에 적힌 헤딩이 원본에 없다 → 헤딩 이름이 바뀐 것인지 `git log -p` 로 확인하고, 같은 절이 다른 이름으로 있으면 그 이름으로 옮긴다. 절 자체가 없으면 `PHASE_BLOCKED: {헤딩}` 을 출력하고 멈춘다.
- 원본에 이동표가 다루지 않는 `##` 절이 있다 → 주제가 가장 가까운 파일로 보내고 결과 보고에 적는다.

## 작업 항목

### 1. 옮기기 전 상태를 저장소 밖에 떠 둔다

비교에 쓴다. `$SCRATCH` 는 저장소 밖의 임시 디렉터리다.

```bash
# cwd: 저장소 root
mkdir -p "$SCRATCH/before" && cp -R docs "$SCRATCH/before/docs"
```

### 2. 이동표대로 새 파일을 조립한다

**표 읽는 법**

- `CA` 는 `docs/code-architecture.md`, `F` 는 `docs/flow.md`, `C` 는 `docs/connectors.md`, `S` 는 `docs/data-schema.md` 다.
- 「절」 은 `##` 헤딩의 글이다. 그 절은 다음 `##` 앞까지 하위 절을 모두 데리고 간다.
- 「절 › 소절」 은 그 `##` 아래의 `###` 하나만 가리킨다. 그 소절은 다음 `###` 나 `##` 앞까지다.
- 「절 (소절 X 제외)」 는 그 절에서 X 소절만 빼고 옮긴다.
- 새 파일 안의 순서는 표에 적힌 순서다.

**새 파일의 모양**

- 첫 줄은 표의 「h1」 칸에 적은 `# 제목` 이다. 그 아래에 빈 줄 하나를 두고 절을 잇는다.
- 원본에서 `##` 였던 절은 `##` 그대로 둔다.
- 부모 절에서 떨어져 나온 `###` 소절은 `##` 로 올리고, 그 아래의 `####` 는 `###` 로 올린다. 헤딩의 글은 바꾸지 않는다.
- 원본 h1 바로 아래의 소개 문단(첫 `##` 앞의 글)은 표에 「머리말」 로 적었다.

#### 공통에 남는 것

| 파일 | h1 | 남는 절 |
| --- | --- | --- |
| `docs/code-architecture.md` | `# 구조` (그대로) | CA 「경계」, 「Hermes 쪽 코드 (`hermes/`)」, 「Memory › 다음」, 「web 화면 구조 › 화면을 검증하는 방법」, 「비밀값을 두는 곳」, 「문서」, 「아직 만들지 않은 것」 |
| `docs/flow.md` | `# 흐름` (그대로) | F 머리말, 「두 방향과 두 토큰」, 「대화 한 번」, 「실행이 실패할 때」 |
| `docs/connectors.md` | `# 커넥터 연결` (그대로) | C 머리말, 「connector.json」, 「Control Plane API」, 「승인」, 「저장과 비밀값」 |
| `docs/prd.md`, `docs/model-tiers.md` | | 그대로 둔다 |

`flow.md` 의 머리말은 지금 `## 커넥터 연결` 절 뒤에 끼어 있는 두 줄(「화면 전환과 호출 순서를 담는다.」 로 시작)이다. 그 두 줄을 h1 바로 아래로 올린다.
「Memory › 다음」 과 「web 화면 구조 › 화면을 검증하는 방법」 은 `##` 로 올린다.

#### backend

| 새 파일 | h1 | 옮겨 올 절 |
| --- | --- | --- |
| `docs/backend/packages.md` | `# backend 패키지` | CA 「backend 패키지」, 「한 번의 대화가 지나는 길」, 「가격표」, 「web 화면 구조 › 합계 질의는 다른 도메인의 엔티티를 조인해도 된다」 |
| `docs/backend/conversation.md` | `# 대화와 실행 사건` | CA 「모델 단계와 실행 정보 보완」, 「대화 (소절 응답 중 대기열, 중지 제외)」, 「실행 사건」. F 「모델 단계와 자식 기록」. `docs/hermes/runs-api.md` 「실행 이벤트가 실제로 오는 형태 › 도구 내용 가리기」 |
| `docs/backend/turn-control.md` | `# 대기열과 중지` | CA 「대화 › 응답 중 대기열」, 「대화 › 중지」. F 「기동할 때 남은 실행 정리」, 「응답 중에 보낼 때」, 「중지할 때」 |
| `docs/backend/memory.md` | `# Memory` | CA 「Memory (소절 다음 제외)」. F 「Memory 본문을 읽는 길」 |
| `docs/backend/agent.md` | `# 에이전트` | CA 「페르소나」, 「에이전트 도구」, 「에이전트 만들기와 지우기」. F 「페르소나를 고칠 때」, 「에이전트 도구를 고를 때」, 「에이전트를 만들 때」 |
| `docs/backend/skill.md` | `# 스킬` | CA 「스킬」. F 「스킬을 저장할 때」, 「스킬 커맨드로 보낼 때」 |
| `docs/backend/attachment.md` | `# 사진 첨부` | CA 「사진 첨부」. F 「사진을 올려 보낼 때」 |
| `docs/backend/artifact.md` | `# 결과물 파일` | CA 「결과물 파일」. F 「결과물을 MCP 로 쓸 때」, 「결과물 파일을 볼 때」 |
| `docs/backend/mcp-caller.md` | `# MCP 요청자` | CA 「MCP 요청자」. F 「MCP 호출의 요청자를 정할 때」. `docs/hermes/tools-and-skills.md` 「Control Plane MCP」, 「결과물 쓰기 도구」 |
| `docs/backend/agent-delegation.md` | `# 다른 에이전트에게 맡기기` | CA 「다른 에이전트에게 맡기기」. F 「다른 에이전트에게 맡길 때」, 「위임 결과가 도착했을 때」 |
| `docs/backend/people.md` | `# 사용자를 더할 때` | CA 「사용자를 더할 때」. F 「사람을 더할 때」 |
| `docs/backend/connector-install.md` | `# 커넥터 설치` | C 「대시보드 plugin 계약」, 「설치와 실패 처리」, 「커넥터 에이전트의 경계」, 「MCP SDK 계약」. F 「커넥터 연결」 |
| `docs/backend/connector-tool-policy.md` | `# 커넥터 도구 정책` | C 「도구 정책」, 「사용자별 호출 제한」. F 「커넥터 도구를 부를 때」, 「승인이 필요한 호출」 |

h1 과 첫 절의 글이 같아지는 파일(`packages.md`, `memory.md`, `skill.md`, `attachment.md`, `artifact.md`, `mcp-caller.md`, `agent-delegation.md`, `people.md`)이 있다. 이 phase 에서는 그대로 둔다. phase 05 가 정리한다.

#### backend/schema

| 새 파일 | h1 | 옮겨 올 절 |
| --- | --- | --- |
| `docs/backend/schema/README.md` | `# 저장 모델` | S 머리말(첫 `##` 앞에 글이 있으면), 「모델 단계와 재조회」, 「지울 때」 |
| `docs/backend/schema/users-agents.md` | `# 사용자와 에이전트` | S 「app_user」, 「allowed_person」, 「agent」, 「agent_token」 |
| `docs/backend/schema/chat.md` | `# 대화` | S 「conversation」, 「chat_message」, 「chat_pending_message」, 「chat_attachment」, 「chat_artifact」 |
| `docs/backend/schema/execution.md` | `# 실행` | S 「agent_execution」, 「execution_event」, 「execution_skill_use」, 「hermes_session_binding」 |
| `docs/backend/schema/memory.md` | `# Memory` | S 「memory」, 「memory_revision」, 「memory_collection」, 「agent_memory_collection」 |
| `docs/backend/schema/connector.md` | `# 커넥터` | S 「connector_connection」, 「connector_action」, 「connector_tool_grant」 |

`docs/data-schema.md` 는 지운다.
`docs/data-schema.md` 에 위 표에 없는 `##` 절(새로 생긴 표)이 있으면 그 표를 쓰는 backend 패키지에 맞는 파일로 보낸다. `user`, `people`, `agent` 는 `users-agents.md`, `chat` 은 `chat.md`, `usage` 는 `execution.md`, `memory` 는 `memory.md`, `connector` 는 `connector.md` 다.
「모델 단계와 재조회」 는 여러 표에 걸친 절이라 이 phase 에서는 `README.md` 에 통째로 둔다. 표마다 나눠 싣는 일은 phase 04 다.

#### frontend

| 새 파일 | h1 | 옮겨 올 절 |
| --- | --- | --- |
| `docs/frontend/structure.md` | `# web 화면 구조` | CA 「web 화면 구조 (소절 합계 질의는 다른 도메인의 엔티티를 조인해도 된다, 화면을 검증하는 방법 제외)」 |
| `docs/frontend/shell.md` | `# 화면 틀과 목록` | F 「대화 이력」, 「대화 목록」, 「화면 틀」, 「기다리는 동안 보이는 것」, 「밝기 모드」 |
| `docs/frontend/chat.md` | `# 대화 화면` | F 「모델을 고를 때」, 「에이전트가 물을 때」, 「다른 창에서 답하는 중일 때」, 「다시 생성」, 「메시지 동작」, 「새 대화 화면」 |
| `docs/frontend/activity.md` | `# 작업 과정` | F 「실행 하나를 다시 볼 때」, 「작업 과정」 |

`structure.md` 는 「web 화면 구조」 절의 머리 글(첫 `###` 앞)과 남은 `###` 소절을 그대로 싣는다. 이 파일에서는 `###` 를 `##` 로 올리지 않고, 원래 `##` 였던 「web 화면 구조」 줄만 지운다(h1 이 같은 글이다). 그러면 h1 다음이 `###` 가 되므로, 소절을 모두 한 단계씩 올린다.

#### docs/hermes

| 파일 | h1 | 절 |
| --- | --- | --- |
| `docs/hermes/delegation.md` | 그대로 | 「내장 delegation 이 실제로 하는 것」 과, 「Control Plane 의 MCP 도구로 다른 실행을 부를 때」 의 소절 가운데 「제한 시간은 우리가 정하지만 상한은 있다」 부터 끝까지 |
| `docs/hermes/fos-ctx.md` (새 파일) | `# _fos_ctx 와 session 등록` | `delegation.md` 「Control Plane 의 MCP 도구로 다른 실행을 부를 때」 의 머리 글과 소절 「도구 호출에는 실행을 가리키는 값이 없다」, 「부모 실행을 잇는 방법」, 「하위 에이전트는 부모 run 보다 오래 산다」 |
| `docs/hermes/tools-and-skills.md` | 그대로 | 「도구와 스킬과 승인 설정을 HTTP 로 쓰는 길」, 「v0.21.3 에서 확인한 쓰기 경로」 |
| `docs/hermes/skills.md` (새 파일) | `# 스킬` | `tools-and-skills.md` 「스킬을 profile 에 붙이는 방법」, 「스킬 커맨드와 API server」 |
| `docs/hermes/runs-api.md` | 그대로 | 「승인 방식 `smart` 는 추론 모델에서 `manual` 과 같아진다」 와 「도구 내용 가리기」 를 뺀 나머지 |
| `docs/hermes/connector-policy.md` | 그대로 | 지금 내용 끝에 `runs-api.md` 의 「승인 방식 `smart` 는 추론 모델에서 `manual` 과 같아진다」 를 `##` 로 올려 붙인다 |

`fos-ctx.md` 는 「Control Plane 의 MCP 도구로 다른 실행을 부를 때」 의 `##` 줄을 그대로 데려가고 소절은 `###` 로 둔다.
`delegation.md` 에 남는 뒤쪽 소절들은 부모 `##` 가 사라지므로, 그 앞에 원래 부모 헤딩 `## Control Plane 의 MCP 도구로 다른 실행을 부를 때` 를 한 번 더 두지 않고 「내장 delegation 이 실제로 하는 것」 아래의 `###` 로 이어 둔다. 헤딩 글과 단계는 바꾸지 않는다.

### 3. 문서 안의 Markdown 링크와 앵커를 새 경로로 고친다

대상은 `docs/**`, `AGENTS.md`, `backend/AGENTS.md`, `web/AGENTS.md`, `hermes/README.md` 다.

- 옮긴 절을 가리키는 링크는 새 파일로 바꾼다. 앵커가 붙은 링크는 그 헤딩이 간 파일을 가리킨다.
- 옮겨 간 파일 안에서 원래 같은 문서였던 다른 절을 가리키는 `#앵커` 링크는 상대 경로를 붙인다.
- 디렉터리 깊이가 달라졌으므로 상대 경로를 다시 센다. `docs/backend/*.md` 에서 ADR 은 `../adr/`, `docs/backend/schema/*.md` 에서는 `../../adr/` 다.
- 링크의 글(대괄호 안)은 바꾸지 않는다. 다만 글이 옛 파일 이름 자체(`` [`data-schema.md`](data-schema.md) ``)면 새 파일 이름으로 바꾼다.
- `AGENTS.md` 의 읽기 순서 표는 이 phase 에서 경로가 깨지지 않으므로 건드리지 않는다.

### 4. 코드 주석과 프롬프트의 경로 글자열을 새 경로로 고친다

찾는 명령이다.

```bash
# cwd: 저장소 root
git ls-files | grep -v '^docs/' \
  | xargs grep -n 'docs/\(code-architecture\|flow\|connectors\|data-schema\|hermes/delegation\|hermes/tools-and-skills\|hermes/runs-api\)\.md'
```

- 경로 뒤에 「절 이름」 이 오면 그 절이 간 파일로 경로를 바꾼다. 절 이름은 바꾸지 않는다.
- 절 이름이 없으면 그 주석이 말하는 내용이 간 파일을 가리킨다. 판단이 서지 않으면 그 주석이 붙은 코드의 패키지에 맞는 파일을 고른다.
- `docs/data-schema.md` 는 표 이름이 간 `docs/backend/schema/*.md` 로 바꾼다.
- 대상은 `backend/src/**` 의 Java 주석과 마이그레이션 주석, `hermes/plugins/**`, `hermes/tests/**`, `hermes/README.md`, `test/e2e/**`, `web/src/**`, `.github/workflows/*.txt`, `backend/AGENTS.md`, `AGENTS.md`, `CLAUDE.md` 다. `CLAUDE.md` 가 `AGENTS.md` 의 심볼릭 링크면 한쪽만 고친다.
- **Java 는 주석만 고친다.** 코드 줄을 바꾸지 않는다. 한 줄이 포맷 한도를 넘으면 줄을 나눈다.
- `scripts/pr-risk-labels.sh` 는 Hermes 연동 라벨의 경로로 `docs/hermes/*` 를 본다. `docs/hermes/` 에 있던 절을 받는 파일이 둘 생긴다. `docs/backend/mcp-caller.md`(「Control Plane MCP」, 「결과물 쓰기 도구」)와 `docs/backend/conversation.md`(「도구 내용 가리기」)다. 이 두 경로를 그 스크립트의 Hermes 연동 경로 목록에 더한다. 기존 `docs/hermes/*` 줄은 그대로 둔다. `test/unit/pr-risk-labels.test.ts` 에 `docs/backend/mcp-caller.md` 가 그 라벨을 받는 경우를 더한다.

### 5. 옮기기만 했는지 기계로 비교한다

두 비교를 한다. 결과 숫자를 PR 본문에 적으므로 출력 전체를 `$SCRATCH/move-compare.txt` 에 남긴다.

**헤딩 목록 비교.** 옮기기 전 `docs/**/*.md`(`docs/adr/` 제외)의 모든 헤딩 줄에서 앞의 `#` 들을 떼어 글만 정렬한 목록과, 옮긴 뒤의 같은 목록을 비교한다.
차이는 새 파일의 h1 줄(이 phase 가 새로 쓴 것)과 `structure.md` 에서 지운 `web 화면 구조` 한 줄뿐이어야 한다.

**줄 단위 비교.** 옮기기 전과 뒤의 같은 범위 파일에서 빈 줄과 헤딩 줄을 뺀 본문 줄을 정렬해 비교한다.
달라진 줄은 모두 Markdown 링크나 문서 경로를 담은 줄이어야 한다. 그렇지 않은 줄이 하나라도 있으면 그 줄을 원문으로 되돌린다.

## 바뀌어도 되는 줄

- 새 파일의 h1 줄
- 헤딩의 `#` 개수(글은 그대로)
- Markdown 링크의 경로와 앵커
- 코드 주석과 프롬프트 안의 문서 경로 글자열
- `scripts/pr-risk-labels.sh` 의 경로 목록과 그 테스트

## 검증

```bash
# cwd: 저장소 root
# 1. 한 파일이 400줄을 넘지 않는다 (docs/adr 와 이 phase 가 쪼개지 않은 docs/hermes 파일은 제외)
wc -l docs/*.md docs/backend/*.md docs/backend/schema/*.md docs/frontend/*.md \
  docs/hermes/delegation.md docs/hermes/fos-ctx.md docs/hermes/tools-and-skills.md \
  docs/hermes/skills.md docs/hermes/runs-api.md docs/hermes/connector-policy.md

# 2. 옛 경로가 남지 않았다. 출력이 없어야 한다
git grep -n 'data-schema\.md' -- . ':!tasks'

# 3. Markdown 링크와 앵커. $DOCS_CHECK_DIR 은 docs-check 스킬 번들 경로다
python3 "$DOCS_CHECK_DIR/scripts/static_check.py" docs/adr docs
python3 "$DOCS_CHECK_DIR/scripts/static_check.py" docs/adr AGENTS.md
python3 "$DOCS_CHECK_DIR/scripts/static_check.py" docs/adr backend/AGENTS.md
python3 "$DOCS_CHECK_DIR/scripts/static_check.py" docs/adr web/AGENTS.md

# 4. 코드가 가리키는 문서 경로가 모두 있는 파일이다. 출력이 없어야 한다
git ls-files | grep -v '^docs/\|^tasks/' | xargs grep -oh 'docs/[A-Za-z0-9_/-]*\.md' | sort -u \
  | while read -r p; do [ -f "$p" ] || echo "없는 경로: $p"; done

# 5. 테스트와 품질 검사
node --test 'test/unit/**/*.test.ts'
python3 -m unittest discover -s hermes/tests
(cd backend && ./gradlew test)
node test/e2e/run.ts
scripts/quality.sh check
scripts/check-public-safe.sh
```

기대값이다.

- 1번: 모든 파일이 400줄 이하다. 넘는 파일이 있으면 결과 보고에 파일과 줄 수를 적는다. 이 phase 에서 문장을 줄여 맞추지 않는다.
- 3번: 출력에 `깨진 링크`, `없는 앵커` 가 든 줄이 0건이다. 종료 코드는 보지 않는다. `INDEX_DESYNC` 는 검사기의 알려진 오탐이고 그것 때문에 종료 코드가 늘 1 이다. `표 열 수 불일치` 도 0건이다. `docs/hermes/concurrency.md` 의 헤딩 건너뜀 한 건은 phase 05 가 고친다.
- 5번: 모두 종료 코드 0 이다. 주석만 고친 `hermes/tests`, `backend/src`, `test/e2e` 의 테스트가 그대로 통과한다. `gradlew` 는 `backend/` 에 있다. `node test/e2e/run.ts` 는 `gradlew test` 뒤에 돌린다.
- 「옮기기만 했는지」 의 두 비교가 위에 적은 차이만 낸다.

`node --test` 는 `test/unit/pr-risk-labels.test.ts` 를 포함해 `test/unit` 전체를 돌린다.

## 변경 파일

| 파일 | 변경 |
|---|---|
| `docs/code-architecture.md` | 수정 |
| `docs/flow.md` | 수정 |
| `docs/connectors.md` | 수정 |
| `docs/data-schema.md` | 삭제 |
| `docs/prd.md` | 수정 |
| `docs/model-tiers.md` | 수정 |
| `docs/backend/packages.md` | 신규 |
| `docs/backend/conversation.md` | 신규 |
| `docs/backend/turn-control.md` | 신규 |
| `docs/backend/memory.md` | 신규 |
| `docs/backend/agent.md` | 신규 |
| `docs/backend/skill.md` | 신규 |
| `docs/backend/attachment.md` | 신규 |
| `docs/backend/artifact.md` | 신규 |
| `docs/backend/mcp-caller.md` | 신규 |
| `docs/backend/agent-delegation.md` | 신규 |
| `docs/backend/people.md` | 신규 |
| `docs/backend/connector-install.md` | 신규 |
| `docs/backend/connector-tool-policy.md` | 신규 |
| `docs/backend/schema/README.md` | 신규 |
| `docs/backend/schema/users-agents.md` | 신규 |
| `docs/backend/schema/chat.md` | 신규 |
| `docs/backend/schema/execution.md` | 신규 |
| `docs/backend/schema/memory.md` | 신규 |
| `docs/backend/schema/connector.md` | 신규 |
| `docs/frontend/structure.md` | 신규 |
| `docs/frontend/shell.md` | 신규 |
| `docs/frontend/chat.md` | 신규 |
| `docs/frontend/activity.md` | 신규 |
| `docs/hermes/fos-ctx.md` | 신규 |
| `docs/hermes/skills.md` | 신규 |
| `docs/hermes/*.md` | 수정 |
| `docs/adr/*.md` | 수정 |
| `hermes/README.md` | 수정 |
| `AGENTS.md` | 수정 |
| `backend/AGENTS.md` | 수정 |
| `web/AGENTS.md` | 수정 |
| `backend/src/**/*.java` | 수정 |
| `backend/src/main/resources/db/migration/*.sql` | 수정 |
| `hermes/plugins/**/*.py` | 수정 |
| `hermes/tests/*.py` | 수정 |
| `test/e2e/**/*.ts` | 수정 |
| `web/src/**/*.ts` | 수정 |
| `.github/workflows/*.txt` | 수정 |
| `scripts/pr-risk-labels.sh` | 수정 |
| `test/unit/pr-risk-labels.test.ts` | 수정 |
