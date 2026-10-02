# 스킬

Hermes 가 스킬을 어디서 읽고 입력에 얼마나 싣는지, API server 가 스킬 커맨드를 어떻게 다루는지를 갖는다.
Control Plane 이 올린 스킬을 저장하고 게시하는 규칙은 [`backend/skill.md`](../backend/skill.md) 가 갖는다.

## 스킬을 profile 에 붙이는 방법

**정식 설정 키는 `skills.external_dirs` 다.**
`hermes_cli/config_defaults.py` 에 있고 기본값은 빈 목록이다.
`skill_paths` 와 `external_skill_paths` 라는 이름의 키는 없다.

스킬이 실리는 경로가 셋이다.

| 방법 | 내용 |
| --- | --- |
| `config.yaml` 의 `skills.external_dirs` | 디렉터리 목록을 그대로 읽는다 |
| `hermes skills trust <경로>` | 그 저장소의 `./.hermes/skills` 와 `./.agents/skills` 를 읽는다 |
| profile 의 `skills/` 에 심볼릭 링크 | 외부 디렉터리를 직접 가리킨다 |

스킬 설명이 매 대화마다 입력으로 함께 실린다.
그러므로 에이전트마다 그 에이전트가 쓰는 스킬만 붙인다.

### 스킬의 출처와 색인 입력

2026-09-28 에 v0.21.0 소스와 색인 계산으로 확인했다.

| 출처 | 연결·제외 단위 |
| --- | --- |
| profile 의 `skills/` | 범주 아래 스킬 디렉터리와 링크를 읽는다. `skills.disabled` 로 제외한다 |
| Hermes 번들 | profile 생성과 `tools/skills_sync.py` 의 기동 동기화가 심는다. `.no-bundled-skills` 는 일반 번들을 막지만 필수 스킬은 남긴다 |
| `skills.external_dirs` | 연결 디렉터리 아래 `SKILL.md` 를 읽는다. 경로를 빼거나 `skills.disabled` 로 제외한다 |
| 신뢰한 저장소 | `./.hermes/skills`, `./.agents/skills` 를 읽는다. 신뢰를 거두면 제외된다 |
| hub 설치 | 스킬 하나를 설치·제거하거나 비활성화한다 |

같은 이름이면 신뢰한 저장소, profile 로컬, 외부 디렉터리 순으로 앞의 것을 쓴다.
`agent/system_prompt.py` 는 실행 도구에 `skills_list`, `skill_view`, `skill_manage` 중
하나라도 있을 때만 `build_skills_system_prompt` 를 부른다.
**`skills` toolset 을 닫으면 스킬 파일이 있어도 색인이 입력에 들어가지 않는다.**

색인은 고정 안내문, 범주별 한 줄, 스킬별 이름과 설명 한 줄로 구성된다.
설명은 `SKILL_PROMPT_DESC_LIMIT` 인 60자에서 자른다.
본문은 `skill_view` 로 읽을 때 들어가고, 조건부 스킬은 `_skill_should_show` 가
도구와 platform 으로 거른다.

`o200k_base` 로 색인을 계산한 크기다. provider 의 토크나이저에 따라 달라질 수 있다.

| 설명 구성 | 스킬 수 | 전체 색인 | 스킬 한 줄 | 고정 부분 |
| --- | --- | --- | --- | --- |
| 한국어 위주 | 6 | 476 토큰 | 33~42 토큰 | 238 토큰 |
| 한국어·영어 혼합 | 7 | 490 토큰 | 18~44 토큰 | 292 토큰 |

스킬 하나는 API 호출 한 번의 입력에 대략 20~45 토큰을 더한다.
한국어 설명은 40 토큰 안팎이며 실행 안의 API 호출마다 다시 실린다.

**`skills` 는 읽기 전용 toolset 이 아니다.**
`skill_manage` 는 profile 로컬 스킬을 만들고 고친다.
외부 디렉터리가 쓰기 가능하면 사용자가 지시한 외부 스킬 수정도 가능하다.
`tools/skill_manager_tool.py` 의 외부 스킬 보호는 백그라운드 curator 의 쓰기만 막는다.

근거는 [v0.21.0 system_prompt.py](https://github.com/NousResearch/hermes-agent/blob/v2026.8.31/agent/system_prompt.py),
[skill_utils.py](https://github.com/NousResearch/hermes-agent/blob/v2026.8.31/agent/skill_utils.py),
[skill_manager_tool.py](https://github.com/NousResearch/hermes-agent/blob/v2026.8.31/tools/skill_manager_tool.py) 다.

### `skill_manage` 만 빼는 설정

v0.21.5 소스로 확인했다. **`skill_view` 를 두고 `skill_manage` 만 도구 목록에서 빼는 공식 설정은 없다.**

| 방법 | 결과 |
| --- | --- |
| `agent.disabled_toolsets` | toolset 이름만 받는다. `skill_manage` 하나만 든 toolset 은 없고, 도구 이름을 넣으면 모르는 이름으로 무시한다. `skills` 나 옛 이름 `skills_tools` 를 넣으면 `skill_view` 와 색인까지 빠진다 |
| `platform_toolsets.api_server` | 같은 toolset 이름 목록이라 같은 결과다 |
| plugin 의 `registry.deregister` | 자기가 등록하지 않은 도구는 `plugins.entries.<plugin>.allow_tool_override: true` 가 있어야 해제된다. profile 범위 plugin 은 전역 도구를 해제하지 못하고, 전역 plugin 이 해제하면 같은 프로세스의 모든 profile 에 걸린다 |
| 한 번 묻고 끝나는 실행의 도구 숨김 | `agent/oneshot_footprint.py` 는 `hermes chat -q` 같은 한 번 실행에서만 `skill_manage` 를 숨긴다. API server 실행은 해당하지 않는다 |

도구를 빼도 안내문은 남는다.
`agent/prompt_builder.py` 의 색인 안내문은 `skill_manage` 가 있든 없든
「If a skill has issues, fix it with skill_manage(action='patch').」 와
「After difficult/iterative tasks, offer to save as a skill.」 를 싣는다.
`skill_manage` 가 있을 때만 붙는 것은 `SKILLS_GUIDANCE` 와 memory 안내의 `skill_manage` 문장이다.

그래서 Control Plane 은 실행 입력 앞 단락으로 모델에게 쓰지 말라고 알리고, 서명 plugin 이 호출을 막는다.
근거는 [toolsets.py](https://github.com/NousResearch/hermes-agent/blob/v2026.9.24/toolsets.py),
[model_tools.py](https://github.com/NousResearch/hermes-agent/blob/v2026.9.24/model_tools.py) 의 `_apply_toolset_selection`,
[tools/registry.py](https://github.com/NousResearch/hermes-agent/blob/v2026.9.24/tools/registry.py) 의 `deregister`,
[agent/system_prompt.py](https://github.com/NousResearch/hermes-agent/blob/v2026.9.24/agent/system_prompt.py) 의 `_tool_guidance_block`,
[agent/prompt_builder.py](https://github.com/NousResearch/hermes-agent/blob/v2026.9.24/agent/prompt_builder.py) 다.

### 스킬 수를 줄인 실측

**다만 스킬이 입력 비용의 대부분은 아니다.**
같은 문장을 `career` profile 에 보내 스킬 97개일 때와 6개일 때를 측정했다.

| 스킬 수 | 입력 토큰 |
| --- | --- |
| 97 | 14,949 |
| 6 | 12,388 |

줄어든 것이 2,561 토큰으로 17% 였다.

### 도구 정의가 더 크다

같은 방법으로 도구 범위를 바꿔 가며 측정한 것이다.
바이트는 `hermes prompt-size --platform api_server --json` 이 보고한 값이고,
이 명령은 API 를 부르지 않고 계산한다.

| 구성 | 도구 수 | 도구 정의 바이트 | 입력 토큰 |
| --- | --- | --- | --- |
| 전부 열림 | 18 | 31,147 | 12,388 |
| terminal·file·skills·memory·web | 14 | 20,364 | 10,180 |
| terminal·file·skills·memory | 12 | 18,454 | 9,792 |
| 전부 잠금 | 0 | 2 | 4,278 |

도구를 전부 잠그면 65.5% 가 줄어든다.
이 값은 도구 정의 JSON 만이 아니라 system prompt 의 도구 안내까지 함께 줄어든 결과다.
system prompt 가 22,799 글자에서 13,123 글자로 줄었다.

`career` 에서 실제로 뺄 수 있는 것은 2,596 토큰, 21% 다.
`career-os` 스킬이 `bun` 을 부르므로 `terminal` 이, 파일을 읽으므로 `file` 이 필요하다.
`sync-profile` 이 쓰는 브라우저는 Hermes 의 browser toolset 이 아니라
외부 스크립트이고 `terminal` 로 돌리므로 그 toolset 은 빼도 된다.

비용을 줄이려면 스킬만 보지 말고 도구 설정을 함께 본다.

### 실행 한 번의 비용은 이보다 훨씬 크다

위 숫자는 짧은 대화 한 번의 고정 비용이다.
실제 작업을 시키면 그 고정 비용이 턴마다 다시 실린다.

`career` 에 포지션 추천을 한 번 시킨 실측이다.

| 항목 | 값 |
| --- | --- |
| 입력 | 3,460,816 토큰 |
| 출력 | 15,301 토큰 |
| `gpt-5.6-sol` 공개 가격 환산 | 14.15달러 |

짧은 대화 한 번의 279배다. 그 실행은 활성 공고 122건을 비교했다.
`career` 의 `max_turns` 는 60 이다.

v0.21.0 과 v0.21.3 Runs API 의 `usage` 에는 cache 항목이 없다.
`input_tokens`, `output_tokens`, `total_tokens` 뿐이다.
`cached_input_tokens` 가 실행 기록에서 비어 있는 것은
prompt cache 가 붙지 않아서가 아니라 이 API 가 보고하지 않기 때문이다.

에이전트 정의 저장소의 스킬은 `.claude/skills` 에 있어 `trust` 가 보는 경로가 아니다.
그래서 심볼릭 링크를 쓴다. 구조는 `skills/<범주>/<스킬>` 이다.

```
~/.hermes/profiles/<profile>/skills/<범주>/<스킬>
  -> <컨테이너에 마운트된 에이전트 정의 저장소>/<범주>/.claude/skills/<스킬>
```

마운트 경로는 `fos-home-infra` 가 정한다.

링크 대상은 **컨테이너 안의 경로**여야 한다. 호스트 경로로 걸면 컨테이너 안에서 끊긴 링크가 된다.
붙인 뒤 gateway 를 다시 띄워야 인식된다. 대시보드 `GET /api/skills?profile=` 로 확인한다.
API server 의 `GET /v1/skills` 는 v0.21.5 에서 늘 500 이다. 아래 「스킬 커맨드와 API server」 를 본다.
실측으로 확인했다.
이미 연결한 스킬의 본문과 색인 변경은 「변경이 적용되는 시점」 에서 구분한다.

## 스킬 커맨드와 API server

2026-09-29 에 v0.21.5(태그 `v2026.9.24`) 소스로 확인했다. 링크 접두사는 `https://github.com/NousResearch/hermes-agent/blob/v2026.9.24/` 이다.

### CLI 와 gateway 는 `/<스킬>` 을 스킬 호출로 바꾼다

- gateway 는 내장 명령과 plugin 명령을 먼저 본 뒤 `_hm_skill_slash_rewrite`(`gateway/run_inbound.py`)가 `/<스킬> 할 일` 을 스킬 호출 메시지로 **통째로 바꿔** 일반 메시지로 넘긴다. 등록되지 않은 이름은 모델에 보내지 않고 `Unknown command` 로 답한다
- CLI(`cli.py` 의 `_run_skill_slash_command`)와 TUI 도 같은 builder 를 쓴다
- 메시지는 `agent/skill_commands.py` 의 `build_skill_invocation_message` 가 만든다. 「사용자가 이 스킬을 호출했다」는 첫 줄, 전처리한 `SKILL.md` 본문, 스킬 디렉터리, 보조 파일 목록과 `skill_view(name, file_path)` 로 읽으라는 안내, 사용자가 붙인 할 일 순이다
- 본문은 `skill_view` 를 **Python 함수로 직접** 불러 읽는다. 도구 호출을 거치지 않으므로 `skills` toolset 이 닫혀 있어도 동작한다
- 슬래시 이름은 소문자로 바꾸고 공백과 `_` 를 `-` 로 바꾼 것이다(`slugify_skill_name`). 내장 명령과 겹치면 자동 등록하지 않는다
- 전역 `skills.disabled` 와 `skills.platform_disabled.<platform>` 에 있는 스킬은 막힌다. 색인에서 숨기는 조건부 스킬 조건(`requires_toolsets` 등)은 슬래시 경로에 적용되지 않는다

### API server 는 `/` 를 해석하지 않는다

- `/v1/runs` 는 `input` 을 그대로 `agent.run_conversation(user_message=...)` 에 넘긴다(`gateway/platforms/api_server_runs.py` 의 `_handle_runs`, `_run_agent_sync`). `/v1/chat/completions` 도 마지막 user 메시지를 그대로 쓴다
- API server 는 gateway 의 명령 처리와 `pre_gateway_dispatch` hook 을 거치지 않는다. `/<스킬> 할 일` 이 평문으로 모델에 간다
- API server 실행은 `platform="api_server"` 로 묶여 `skill_view` 와 색인이 `skills.platform_disabled.api_server` 를 적용받는다

그래서 웹 입력창의 스킬 커맨드는 Control Plane 이 해석한다. 결정은 [ADR-035](../adr/ADR-035-대화창의-스킬-커맨드는-control-plane-이-해석해-hermes-에-넘긴다.md) 에 있다.
Control Plane 이 「`skill_view` 로 읽고 따르라」는 입력으로 바꿔 보내면 아래 조건이 걸린다.

| 조건 | 까닭 |
| --- | --- |
| `skills` toolset 이 켜져 있어야 한다 | 닫히면 `skill_view` 가 도구 목록에 없어 호출이 오류 결과가 된다 |
| 스킬이 전역이나 `api_server` 로 꺼져 있지 않아야 한다 | `skill_view` 가 `Skill 'x' is disabled` 로 거절한다 |
| 색인에서 숨은 조건부 스킬도 이름을 알면 읽힌다 | `skill_view` 는 조건부 표시 조건을 보지 않는다. OS 가 맞지 않는 것만 막는다 |

모델이 도구를 부르지 않는 일이 실제로 보이면, profile 플러그인의 `pre_llm_call` 이 돌려주는 `{"context": ...}` 로 `build_skill_invocation_message` 결과를 붙이는 방법이 있다. `pre_llm_call` 은 API server 실행에서도 불린다(`agent/turn_context.py`).

### 모델이 스킬을 읽은 것을 아는 법

`skill_view` 도구 호출의 `tool.started` 사건 `preview` 는 스킬 이름이다. 참고 파일을 읽으면 `이름 → 파일 경로` 모양이다(`agent/display.py` 의 `_preview_skill_view`).
이름 규칙에 맞지 않으면 버린다.

미리보기는 길이 상한에서 잘릴 수 있다. v0.21.5 소스로 확인했다.

- `_preview_skill_view` 는 미리보기를 만든 뒤 `_tail_trunc` 로 자른다
- `_tail_trunc` 는 상한을 넘으면 앞에서 상한보다 3자 적게 남기고 끝에 `...` 을 붙인다. 상한이 3 이하이면 점만 남긴다
- 상한은 설정 `display.tool_preview_length` 이고 기본값은 0 이다. 0 이면 자르지 않는다

이름 규칙이 점을 받으므로 이름 중간에서 잘린 값도 규칙에는 맞는다.
그래서 이름 부분이 `...` 로 끝나면 잘린 것으로 보고 버린다.
파일 경로 쪽만 잘린 `이름 → 경로...` 는 이름이 온전하므로 받는다.
Control Plane 은 도구 내용을 가리기 전에 이 미리보기에서 이름을 꺼낸다([Runs API 계약](../backend/conversation.md#도구-내용-가리기)).

### 스킬 목록을 얻는 곳

| 경로 | 결과 |
| --- | --- |
| API server `GET /v1/skills` | v0.21.5 에서 늘 500. `_handle_skills` 가 `_find_all_skills` 에 없는 인자 `include_editorial` 을 넘겨 `TypeError` 가 난다. 테스트는 그 함수를 mock 으로 바꿔 두어 잡지 못한다 |
| 대시보드 `GET /api/skills?profile=` | 이름, 설명, 켜짐, 출처(`provenance`), 사용 횟수(`usage`)를 준다. `enabled` 는 전역 `disabled` 만 보고 `platform_disabled` 는 보지 않는다 |
| 대시보드 `GET /api/skills/content?name=&profile=` | `SKILL.md` 원문 |

슬래시로 부를 수 있는 이름의 목록을 주는 HTTP 경로는 없다. 이름 규칙대로 계산한다.
