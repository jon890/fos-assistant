# Phase 04. career 에이전트를 실제로 쓸 수 있게 한다

**Execution profile**: standard

## 목표

홈서버의 `career` profile 에 API server 를 켜고 에이전트 정의 저장소의 스킬을 붙여,
웹에서 그 에이전트를 골라 career-os 스킬을 부를 수 있게 한다.

이 phase 가 끝나면 스킬 호출 비용이 실행 단위로 기록된다.

**범위 외**

- 스킬별로 비용을 쪼개는 것은 하지 않는다. 원리적으로 안 되는 부분이 있다.
  실행 하나의 총 비용까지가 이 phase 의 범위다.
- 에이전트끼리 협업하는 것은 하지 않는다.
- `fos-agents` 저장소를 고치지 않는다.

## 컨텍스트

`career` profile 은 홈서버에 이미 있다.
`disabled_toolsets: []` 로 도구가 전부 열려 있어 `terminal` 과 `file` 을 쓸 수 있다.
career-os 스킬이 `bun career-os/scripts/...` 를 실행하므로 그 도구가 필요하다.

**그래서 이 에이전트는 반드시 `PRIVATE` 이다.**
가족 공개로 두면 구성원 누구나 웹 챗으로 홈서버 셸을 쓸 수 있다.

스킬을 붙이는 방법은 실측으로 확인했다.
설정 키가 아니라 심볼릭 링크다.

```
~/.hermes/profiles/<profile>/skills/<범주>/<스킬> -> <마운트된 스킬 디렉터리>
```

`fos-agents` 는 Hermes 컨테이너에 이미 붙어 있다. 그 마운트 경로는 `fos-home-infra` 가 소유한다.
링크를 만든 뒤 gateway 를 다시 띄우면 `GET /v1/skills` 에 나타난다. 실측으로 확인했다.

**`skill_paths` 와 `external_skill_paths` 는 설정 키가 아니다.**
Hermes 소스에 그런 키가 없다. 빌드 스크립트의 지역 변수로만 나온다.

따를 패턴을 경로로 짚는다.

| 무엇 | 경로 |
| --- | --- |
| profile 만들기와 API server 켜기 | `fos-home-infra` 의 `services/hermes-assistant/configure-member-profile.sh` |
| 이미 쓰는 포트 | `fos-home-infra` 의 `services/hermes-assistant/README.md` 가 목록을 갖는다 |
| 페르소나 사본 | `fos-home-infra` 의 `services/hermes-assistant/personas/career/SOUL.md` |

**근거 문서**: `docs/hermes-integration.md`, `docs/adr/ADR-007-에이전트가-모델과-도구를-함께-정한다.md`

## 의도 메모

- 새 profile 을 만들지 않는다. 이미 있는 `career` 를 쓴다.
  사람이 쓴 `SOUL.md` 와 memory 가 그 안에 있다.
- `configure-member-profile.sh` 를 그대로 쓰지 않는다.
  그 스크립트는 도구를 잠근 설정을 덮어쓴다. `career` 는 도구가 열려 있어야 한다.
  API server 를 켜는 부분만 따로 한다.
- 스킬 여섯 개를 모두 붙인다. 한 개만 붙일 이유가 없다.
- gateway 를 다시 띄워야 스킬이 인식된다. 링크만 만들고 끝내면 보이지 않는다.

## 작업 항목

### 1. career profile 의 API server 를 켜는 스크립트를 더한다

`fos-home-infra` 의 `services/hermes-assistant/enable-profile-api.sh` 를 만든다.
브랜치는 `feat/career-agent` 를 쓴다.

- 인자로 profile 이름과 포트를 받는다.
- 그 profile 의 `.env` 에 `API_SERVER_ENABLED`, `API_SERVER_HOST`, `API_SERVER_PORT`,
  `API_SERVER_MODEL_NAME`, `API_SERVER_KEY` 를 넣는다.
  `configure-member-profile.sh` 의 `set_env_value` 를 그대로 따른다.
- key 는 `$ASSISTANT_PROFILE_KEY_DIR/<profile>` 에 mode 600 으로 만든다.
  이미 있으면 다시 만들지 않는다.
- **`config.yaml` 을 건드리지 않는다.** 도구 설정과 모델을 그대로 둔다.
- 포트는 `fos-home-infra` 의 쓰이는 포트 목록에서 비어 있는 것을 고른다.

### 2. 스킬을 붙이는 스크립트를 더한다

`services/hermes-assistant/link-workspace-skills.sh` 를 만든다.

- 인자로 profile 이름과 워크스페이스 이름을 받는다.
- `<profile>/skills/<워크스페이스>/` 를 만들고 그 아래에 스킬마다 심볼릭 링크를 만든다.
- 링크 대상은 컨테이너 안의 경로다. 그 경로는 `fos-home-infra` 가 소유한다.
- 붙인 뒤 gateway 를 다시 띄운다.
- `GET /v1/skills` 로 확인해 붙은 스킬 수를 출력한다.

컨테이너 안 경로를 링크 대상으로 쓰는 것이 중요하다.
호스트 경로로 링크하면 컨테이너 안에서 끊긴 링크가 된다.

### 3. 문서를 고친다

`services/hermes-assistant/README.md` 에 절을 더한다.

- 스킬을 붙이는 방법이 설정 키가 아니라 심볼릭 링크라는 것.
- `skill_paths` 와 `external_skill_paths` 는 설정 키가 아니라는 것.
- 도구가 열린 profile 을 가족 공개 에이전트로 두면 안 되는 이유.
- 쓰이는 포트 목록에 이번에 고른 포트를 더한다.

`services/assistant/README.md` 의 새 서비스를 붙일 때 절에도 포트를 반영한다.

### 4. 홈서버에 적용한다

스크립트를 홈서버로 옮겨 실제로 돌린다.

```bash
# cwd: fos-home-infra 저장소 root
scp -P <포트> services/hermes-assistant/*.sh <사용자>@<홈서버>:/home/bifos/apps/fos-home-infra/services/hermes-assistant/
```

그다음 홈서버에서 차례로 돌린다.

1. API server 를 켠다
2. 스킬을 붙인다
3. gateway 를 띄운다
4. `GET /v1/skills` 로 career-os 스킬 여섯 개를 확인한다

### 5. 에이전트로 등록한다

Control Plane 의 관리 엔드포인트로 등록한다.

| 값 | 무엇 |
| --- | --- |
| `code` | `career` |
| `hermesProfile` | `career` |
| `apiBaseUrl` | 그 profile 의 API server 주소. `fos-home-infra` 가 정한다 |
| `visibility` | **`PRIVATE`** |
| `ownerEmail` | 홈서버 주인 |
| `costMode` | `SUBSCRIPTION` |
| `credentialScope` | `SHARED_HOUSEHOLD` |

`model` 은 받지 않는다. phase-02 가 Hermes 에 물어 채운다.

### 6. 이 phase 를 검증하는 테스트

스크립트에 단위 테스트를 만들지 않는다. 셸 스크립트이고 대상이 홈서버다.
대신 **실제로 돌려 확인한 것을 보고에 적는다.**

아래를 모두 확인해야 이 phase 가 끝난 것이다.

- `GET /v1/skills` 에 career-os 스킬 여섯 개가 `career-os` 범주로 나온다.
- 웹에서 `career` 에이전트를 골라 대화가 왕복한다.
- 그 대화가 career-os 스킬을 부르고, 실행 기록에 토큰과 환산 비용이 남는다.
- 사용량 화면에서 그 실행의 에이전트가 `career` 로 보인다.
- **다른 구성원의 토큰으로는 그 에이전트가 목록에 보이지 않는다.**

마지막 것이 가장 중요하다. 도구가 열린 에이전트이므로 그것이 새면 셸이 새는 것이다.

## 검증

```bash
# cwd: fos-home-infra 저장소 root
bash -n services/hermes-assistant/enable-profile-api.sh
bash -n services/hermes-assistant/link-workspace-skills.sh
```

```bash
# cwd: fos-home-infra 저장소 root
~/personal/fos-skills/korean-check/scripts/check.sh services/hermes-assistant/README.md
```

실제 확인은 홈서버에서 돌린 명령과 그 출력을 보고에 적는다.
돌리지 않은 것을 확인했다고 적지 않는다.

## Critical Files

| 파일 | 변경 |
| --- | --- |
| `fos-home-infra` 의 `services/hermes-assistant/enable-profile-api.sh` | 신규 |
| `fos-home-infra` 의 `services/hermes-assistant/link-workspace-skills.sh` | 신규 |
| `fos-home-infra` 의 `services/hermes-assistant/README.md` | 수정 |
| `fos-home-infra` 의 `services/assistant/README.md` | 수정 |

## 끝낸 뒤

`tasks/plan003-agents/index.json` 의 이 phase 를 `completed` 로 바꾸고
plan 의 `status` 를 `completed` 로 바꾼다.
