# 플러그인 에이전트용 스킬 기술 설계

스펙: [`spec.md`](spec.md). 결정의 이유는 [ADR-20261120 / plugin-agent-skills](../../../hermes/docs/adr/ADR-20261120-plugin-agent-skills.md) 가 갖는다.

## 요구사항 연결

| ID | 해결 방법 |
| --- | --- |
| REQ1 | 대시보드 plugin 의 manifest 읽기(`connector_manifest.py`)가 `assistant.json` 의 `agent_skills` 를 읽고, 기존 스킬 읽기(`connector_skills.py`)로 파일과 앞머리를 검사한다. 카탈로그는 기존 `skills` 에 에이전트용 스킬 이름까지 싣고, 새 칸 `agent_skills` 를 비어 있지 않을 때만 싣는다 |
| REQ2 | 새 판정 함수(`_agent_skill_plan`)가 요구 env 없는 에이전트용 스킬을 이름 겹침이 없는 한 설치할 집합에 넣고, 바인딩 설치(`connector_binding.py`)가 플러그인 스킬과 같은 방법으로 복사한다 |
| REQ3 | 같은 판정 함수가 셸 조건 세 가지를 보고 못 채운 스킬을 까닭과 함께 빠진 목록에 넣는다. 바인딩 설치는 설치할 스킬만 복사하고 빠진 목록을 응답의 새 칸 `skipped_skills` 로 낸다 |
| REQ6 | 처음 붙이기는 Control Plane 이 넓어진 카탈로그 `skills` 로 거절한다. 붙인 뒤의 겹침은 판정 함수가 `name_taken` 으로 빼고, 플러그인 스킬의 겹침은 지금 검사 그대로 409 다 |
| REQ4 | 설치 상태 판정(`connector_status.py` 의 `_bind_skills_installed`)이 같은 판정 함수로 기대 집합을 계산해 소유 기록과 다르면 `configured` 를 거짓으로 낸다. 그러면 Control Plane 의 정의 어긋남 점검이 지금처럼 다시 설치한다 |
| REQ5 | 떼기는 소유 기록의 `skills` 를 지운다. 설치한 에이전트용 스킬도 그 목록에 있어 바뀌는 것이 없다 |
| REQ7 | 셸 설정(`sandbox.py` 의 `_sandbox_terminal`)은 고치지 않는다. 판정은 profile `config.yaml` 을 읽기만 한다 |
| REQ8 | PR2. 아래 「PR2 의 경계」 의 설치 결과 저장과 연결 목록 응답 |
| REQ9 | PR2. 아래 「PR2 의 경계」 의 연결 절 안내 |

## 책임과 경계

대시보드 plugin 이 manifest 검증, 판정, 스킬 파일 쓰기를 모두 갖는다. 판정 재료(API 도구 목록, 셸 설정, 스킬 디렉터리)가 모두 plugin 이 읽는 profile 안에 있다.
Control Plane 은 PR1 에서 바뀌지 않는다. 카탈로그 `skills` 가 넓어져 처음 붙일 때의 이름 겹침 검사(`ConnectorBindingService.bind`)가 에이전트용 스킬까지 보고, 정의 어긋남 점검은 지금의 `configured` 를 그대로 쓴다.
fos-agents 쪽 디렉터리와 manifest 칸 채우기는 이 저장소 밖이다.

## 인터페이스와 데이터

**manifest.** `assistant.json` 에 선택 칸 하나를 더한다. 원본 계약은 [`hermes/connectors/README.md`](../../../hermes/connectors/README.md) 의 「스킬」 에 둔다.

```json
"agent_skills": {
  "path": "./agent-skills",
  "sandbox_env": { "position-recommender": ["CLAUDE_PLUGIN_ROOT", "CAREER_BACKEND_URL"] }
}
```

- `path` 는 plugin 안의 링크 없는 디렉터리다. 읽기는 플러그인 스킬과 같은 함수로 하고, `plugin.json` 스킬과 이름이 겹치면 manifest 전체가 실패한다.
- `sandbox_env` 는 선택이다. 키는 그 디렉터리 스킬의 이름(앞머리 `name`, 없으면 디렉터리 이름. `_skill_name` 의 결과)이고, 값은 비지 않은 env 이름 목록이다. 이름은 운영 정책 `profiles[*].env` 가 받는 이름이어야 한다. 그 목록은 `sandbox.py` 에 상수 하나(`SANDBOX_ALLOWED_ENV`)로 두고 정책 검증과 manifest 검증이 함께 쓴다. 키가 그 디렉터리에 없거나 이름이 목록 밖이면 manifest 전체가 실패한다.
- `scripts/` 와 그 밖의 파일은 지금처럼 복사하지 않는다(spec DECISION8).

**manifest 안의 값.** `skills` 는 지금처럼 `{이름: 파일}` 이되 에이전트용 스킬까지 담는다. 새 값 `agent_skills` 는 `{이름: frozenset(env 이름)}` 이고 에이전트용 스킬을 모두 담는다. 요구가 없으면 빈 집합이다. 이 값에 없는 이름이 플러그인 스킬이다.

**카탈로그 응답.** `skills` 는 두 종류를 합친 이름 목록이다(뜻: 이 플러그인이 붙인 에이전트에 설치할 수 있는 스킬). 새 칸 `agent_skills` 는 `[{name, sandbox_env}]` 로 이름 순이고, 에이전트용 스킬이 있을 때만 응답에 넣는다.

**판정 함수.** `_agent_skill_plan(profile_dir, config, manifest, owned_skills) -> (설치할 이름 집합, {빠진 이름: [까닭]})` 하나를 둔다. 바인딩 설치와 설치 상태 판정이 같은 함수를 부른다. profile 이름이나 운영 정책은 받지 않는다(spec DECISION17).

| 까닭 | 조건 | 보는 스킬 |
| --- | --- | --- |
| `terminal_off` | `platform_toolsets.api_server` 에 `terminal` 이 없다 | 요구 env 있는 에이전트용 스킬 |
| `sandbox_unregistered` | `terminal.backend` 가 `docker` 가 아니다 | 같음 |
| `sandbox_env_missing` | `terminal.docker_env` 의 키에 요구 이름 가운데 하나라도 없다 | 같음 |
| `name_taken` | 이 플러그인이 설치하지 않은 같은 이름이 profile `skills/` 에 있거나, 소유와 상관없이 `skills.external_dirs` 의 디렉터리에 있다 | 모든 에이전트용 스킬 |

- 까닭은 위 순서로 해당하는 것을 모두 낸다. 빠진 이름은 이름 순이다.
- 플러그인 스킬은 판정하지 않는다. 지금처럼 늘 설치하고, 겹치면 바인딩이 409 다(spec REQ6).
- 처음 붙일 때(소유 기록 없음) `name_taken` 이 하나라도 있으면 지금처럼 409 로 거절한다. Control Plane 이 먼저 `SKILL_NAME_TAKEN` 으로 막으므로 대시보드의 거절은 그 사이 경합만 받는다.

**설치 응답.** `PUT /api/connectors` 의 붙이기 응답에 `skipped_skills: [{name, reasons}]` 를 더한다. 「바뀐 것 없음」 으로 일찍 돌아가는 붙이기 경로에도 판정한 목록을 넣는다. 떼기 응답에는 칸을 넣지 않는다(없는 칸은 빈 목록으로 읽는다). Control Plane 은 이 칸을 모르면 무시한다.

**소유 기록.** 모양을 바꾸지 않는다. `skills` 에 실제로 설치한 이름만 적는다. 새 키를 더하면 이전 이미지로 되돌렸을 때 옛 plugin 의 기록 검사(`connector_state.py` 의 `_connector_state` 허용 키)가 그 profile 의 바인딩 쓰기를 모두 거절하기 때문이다.

**설치 상태 판정.** `_bind_skills_installed` 는 「기록 = manifest 전체」 대신 아래를 본다. 시그니처는 profile `config` 를 더 받는다(호출부 `connector_install.py` 가 이미 읽었다).

- 기록의 이름 집합 = 플러그인 스킬 ∪ 판정 함수의 설치할 집합
- 기록의 스킬마다 설치한 파일이 manifest 의 파일과 같다

조건이 바뀌면 이 판정이 거짓이 되어 정의 어긋남 점검이 다시 설치한다(spec DECISION7). 다시 설치가 판정을 다시 해 기록을 기대 집합과 같게 쓰므로 같은 어긋남이 되풀이되지 않는다.

## 실패와 일관성

- 판정은 바인딩 설치의 profile 쓰기 잠금 안에서 한 번 하고, 파일 쓰기와 되돌리기는 기존 한 묶음 쓰기를 그대로 쓴다.
- 판정이 운영 정책을 읽지 않으므로 정책이 없거나 틀려도 오류 로그가 늘지 않고 깔린 스킬이 지워지지 않는다. 셸 설정은 정책이 바뀐 뒤 도구 저장이나 운영 일괄 반영 때 바뀌고, 그때 판정도 바뀐다.
- 스킬만 바뀐 다시 설치는 지금처럼 `reload_pending` 이고(`connector_binding.py`), 그동안 「붙이는 중」 이다(spec DECISION15).
- 배포 순서는 상관없다. 새 plugin 과 옛 Control Plane 은 새 응답 칸과 카탈로그 칸을 무시한다. 옛 plugin 과 새 Control Plane(PR2)은 칸이 없어 빈 목록으로 읽는다(rule-66).
- **되돌리기.** 이전 이미지로 되돌리면 옛 상태 판정이 「기록 = manifest 전체」 를 보는데 옛 plugin 은 `agent_skills` 를 모르므로, 에이전트용 스킬을 설치한 바인딩은 `configured` 가 거짓이 된다. 정의 어긋남 점검이 그 바인딩들을 다시 설치해 에이전트용 스킬을 지우고, 그동안 잠깐 「붙이는 중」 이다. 다시 앞으로 배포하면 같은 일이 한 번 더 일어난다. 기록 모양이 같아 그 밖의 쓰기는 막히지 않는다.

## PR2 의 경계

PR2 는 이 설계를 다시 열지 않고 아래만 정한다. 정확한 칸과 마이그레이션은 PR2 계획이 정한다.

- Control Plane 의 설치 결과 읽기(`HttpHermesConnectorClient`)가 `skipped_skills` 를 읽고, 성공한 설치 응답만 바인딩 행에 저장한다. 새 테이블은 만들지 않고 `agent_connector_binding` 에 JSON 글 컬럼을 더한다.
- 에이전트 연결 목록 응답의 바인딩마다 그 값을 내고, 웹의 `agent-connections-section.tsx` 가 spec REQ9 의 문구로 그린다.
- 「스킬 도구가 꺼져 있다」 안내(`agent-connections-section.tsx`)는 설치된 스킬이 없고 빠진 스킬만 있으면 띄우지 않는다. 그 판정에 빠진 목록이 필요해 PR2 에서 함께 고친다.
- 카탈로그 `skills` 를 그대로 내는 관리자·주인 응답(`ConnectionAdminDtos`, `ConnectionDtos`)과 웹 `lib/agent-connection.ts` 의 주석은 뜻이 「설치할 수 있는 스킬」 로 넓어진 것만 문서로 맞춘다.

## 검증 전략

- REQ1: plugin 단위 테스트로 manifest 읽기의 정상과 거절(경로, 키, 허용 목록 밖 이름, 이름 겹침, 앞머리 비밀 칸)과 카탈로그 응답 모양을 본다. 칸이 없는 플러그인의 카탈로그가 그대로인지 본다.
- REQ2, REQ3, REQ6: 판정 함수 단위 테스트로 까닭 네 가지와 순서를 본다. 바인딩 설치 테스트로 임시 profile 에 붙이기를 돌려 파일과 `skipped_skills`, 「바뀐 것 없음」 응답의 목록, 처음 붙이기의 409 를 본다.
- REQ4: 설치 상태 판정 테스트로 조건을 바꾸면 `configured` 가 거짓이 되고, 다시 설치 뒤 참으로 돌아오는지 본다.
- REQ5: 떼기 뒤 에이전트용 스킬 디렉터리가 없는지 본다.
- REQ7: 셸 설정 테스트를 고치지 않고 통과시키고, 붙이기 전후 `terminal` 설정과 `.env` 가 같은지 본다.
- 운영 왕복은 fos-agents 사본이 나온 뒤 커리어 에이전트에서 한 번 한다(spec 「요구사항과 인수 조건」).

## 결정

| ID | 결정 | 답 | 누가 | 근거 |
| --- | --- | --- | --- | --- |
| DECISION1 | 새 칸 자리 | `assistant.json` 의 `agent_skills`. `plugin.json` 은 건드리지 않는다 | 작성자 | assistant 전용 정보는 `assistant.json` 하나에 둔다([ADR-20261026 / plugin-standard-format](../../../hermes/docs/adr/ADR-20261026-plugin-standard-format.md)). Claude Code 가 이 디렉터리를 스킬로 읽지 않는다 |
| DECISION2 | 빠진 스킬 기록 자리 | 소유 기록에 남기지 않고 판정 함수로 매번 계산해 응답으로 낸다 | 작성자 | 기록에 새 키를 더하면 되돌린 이미지가 그 profile 의 바인딩 쓰기를 모두 거절한다 |
| DECISION3 | 판정 재료 | profile `config.yaml` 의 API 도구 목록, `terminal` 설정, 스킬 디렉터리. 운영 정책은 읽지 않는다 | 작성자 | spec DECISION17. profile 이름이 인자로 필요 없고, 정책이 없는 개발 환경에서 오류 로그가 늘지 않는다 |
| DECISION4 | 카탈로그 `skills` 의 뜻 | 에이전트용 스킬까지 합친 이름 | 작성자 | Control Plane 을 고치지 않고 처음 붙이기의 겹침 검사를 넓힌다. 이 목록을 그대로 내거나 쓰는 곳은 「PR2 의 경계」 에 적었다. `agent_list` 의 할 수 있는 일은 올린 스킬만 보므로(`AgentCapabilitiesReader`) 바뀌지 않는다 |
| DECISION5 | 허용 env 이름 목록 | `sandbox.py` 의 상수 하나를 정책 검증과 manifest 검증이 함께 쓴다 | 작성자 | 지금은 경로 이름 상수와 함수 안의 URL 이름으로 흩어져 있어, 한쪽만 고치면 선언할 수 있는데 어떤 profile 도 줄 수 없는 이름이 생긴다 |
| DECISION6 | `name_taken` 에서 보는 자리 | profile `skills/` 와 `skills.external_dirs` 의 디렉터리 이름 | 작성자 | 올린 스킬은 `external_dirs` 에, 모델이 만든 스킬과 플러그인 스킬은 profile `skills/` 에 있다(agent-skill.md 「스킬」). 둘 다 봐야 올린 스킬이 가려지지 않는다 |

## 결정 기록 연결

| 결정 | 종류 | ADR 또는 필요 없음의 이유 | 현재 규칙의 원본 |
| --- | --- | --- | --- |
| spec DECISION2, DECISION7, DECISION16, DECISION17 | architecture | [ADR-20261120 / plugin-agent-skills](../../../hermes/docs/adr/ADR-20261120-plugin-agent-skills.md) | [`hermes/connectors/README.md`](../../../hermes/connectors/README.md) 의 「스킬」, [플러그인 연결](../connector.md) 의 「바인딩 설치」 |
