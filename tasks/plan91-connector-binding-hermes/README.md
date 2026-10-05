# plan91 커넥터 바인딩의 Hermes 쪽

일반 에이전트의 profile 에 커넥터를 붙이는 Hermes 쪽 경로를 연다. 대시보드 plugin 의 보관 파일과 바인딩 설치, `fos-ctx` 의 바인딩 profile 판정이다.
결정과 근거는 `docs/adr/ADR-083-커넥터는-사용자가-한-번-연결하고-자기-에이전트에-여럿-붙여-그-에이전트가-도구를-직접-부른다.md` 에 있다.

이 plan 은 `plan92-connector-bindings` 보다 먼저 머지하고 먼저 배포한다. Hermes 묶음을 backend 보다 먼저 올리는 배포 순서([ADR-041](../../docs/adr/ADR-041-hermes-에-설치하는-plugin-과-profile-틀은-이-저장소가-소유한다.md))와 같다.
새 경로는 새 칸(`bind`, `vault`)으로만 열린다. 칸이 없는 요청은 지금처럼 처리하므로 옛 backend 와 함께 돈다.

## 순서

| phase | 하는 일 |
| --- | --- |
| 01 | 대시보드 plugin 의 보관 파일, 바인딩 설치와 떼기, 도구 목록 보존, 카탈로그의 스킬 이름 |
| 02 | `fos-ctx` 의 바인딩 profile 판정, 대시보드 plugin 계약 문서 |

브랜치는 `plan91-connector-binding-hermes` 이고 PR 하나로 올린다.

## 말

| 말 | 뜻 |
| --- | --- |
| 연결 | 사용자가 커넥터 하나에 계정을 연결한 것. 사용자와 커넥터마다 하나다 |
| 바인딩 | 에이전트에 연결을 붙인 것. 에이전트와 연결의 다대다다 |
| 보관 파일 | 연결의 칸 값을 대시보드 plugin 이 Hermes 쪽에 두는 파일. 연결마다 하나이고 이름은 `c<연결 id>` 다 |
| 옛 설치 | 커넥터마다 만든 전용 profile 에 하던 지금의 설치. 소유 기록 항목의 `mode` 가 없거나 `isolated` 다 |
| 바인딩 설치 | 일반 에이전트의 profile 에 하는 새 설치. 항목의 `mode` 가 `bind` 다 |

## 계약에서 맞출 이름

`plan92-connector-bindings` 가 이 이름을 그대로 부른다.

| 경로 | 본문 | 답 |
| --- | --- | --- |
| `PUT /api/connector-vault` | `{vault, connector, values}`. `values` 는 `fields[].key` 를 키로 한 값 | `{ok: true}` |
| `DELETE /api/connector-vault` | `{vault}` | `{changed}` |
| `POST /api/connector-vault/import` | `{vault, connector, profile}` | `{ok: true}` |
| `POST /api/connectors/<id>/call` | 지금의 `{tool, values}` 또는 `{tool, vault}` | 지금과 같다 |
| `PUT /api/connectors` | 지금의 `{profile, plugin, enabled}` 에 바인딩이면 `bind: {vault}` 를 더한다 | 지금과 같다 |
| `GET /api/connectors?profile=` | 지금과 같다 | 커넥터마다 `mode`(`bind`, `isolated`)를 더한다 |
| `GET /api/connectors/catalog` | 지금과 같다 | 커넥터마다 `skills`(이름 목록)를 더한다 |

- `vault` 는 `^c[1-9][0-9]{0,18}$` 다
- 바인딩 설치를 받는 profile 은 관리 표식(`.fos-assistant-managed`)이나 커넥터 표식(`.fos-connector-host`)이 있어야 한다. 커넥터 표식은 운영자가 사람이 만든 profile 에 두고 plugin 은 쓰지 않는다
- 이름 대응 파일 `.fos-connector-tools.json` 은 `v: 1` 그대로 두고 바인딩 설치는 `isolated: false` 를 싣는다. 칸이 없으면 참으로 읽는다
- 바인딩 설치의 `restart_required` 는 바뀐 것이 있으면 참, 떼기는 거짓이다
- 바인딩 설치는 `api_server` 목록이 있고 그 안에 Control Plane MCP 가 있는 profile 만 받는다
- `PUT /api/config` 는 소유 기록의 바인딩 서버 이름이 빠진 목록을 409 로 거절한다. Control Plane 이 그 이름을 함께 보낸다
- 바인딩 profile 의 대응 파일에는 소유 기록의 모든 커넥터 서버가 실린다. manifest 를 읽지 못한 서버는 빈 `tools` 로 실린다

## 모든 phase 에 걸리는 규칙

- 공개 저장소다. 홈서버 주소, 포트, 컨테이너 이름, 디렉터리의 실제 경로, 운영 저장소의 구조를 코드, 문서, 커밋, PR 본문에 적지 않는다. `scripts/check-public-safe.sh` 로 확인한다
- `hermes/plugins` 에 서비스 이름을 두지 않는다. `test/unit/connector-neutral.test.ts` 가 본다
- 칸 값과 보관 파일의 내용은 응답, 로그, 예외 메시지, 설정 백업에 없다
- 옛 설치의 동작은 바꾸지 않는다
- 커밋 범위는 `hermes` 와 `docs` 다

## 운영 반영

- 이 plan 의 Hermes 묶음을 배포하고 대시보드를 다시 띄운다. 묶음과 대시보드 재시작 절차는 운영 저장소가 갖는다
- 사람이 만든 profile 에 붙이려면 운영자가 그 profile 에 아래를 갖춘다. 그 profile 의 실제 이름과 경로는 운영 저장소에만 적는다
  - 커넥터 표식 파일
  - `fos-ctx` 를 켠 `plugins` 설정(`allow_tool_override: false`)
  - Control Plane MCP 등록과 그 토큰 env. hook 이 판정을 물을 때 그 토큰을 쓴다
  - `platform_toolsets.api_server` 목록에 Control Plane MCP
  - gateway 프로세스의 판정 주소 env(`FOS_CTX_POLICY_URL`)는 이미 모든 관리 profile 이 쓰는 것과 같다

## 범위 밖

- Control Plane 과 화면, 먼저 살펴보기. `plan92-connector-bindings` 가 한다
- 재시작 없이 profile 하나의 MCP 를 다시 발견하는 길

## 계획서 삭제

이 디렉터리는 이 plan 의 구현 PR 이 지운다. ADR-083 의 「아직 구현 전이다」 는 `plan92-connector-bindings` 가 지운다.
