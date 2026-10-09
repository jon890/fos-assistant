## ADR-088: 대시보드 plugin 은 감싸는 경로의 바꿔 끼우기를 두고, 기대는 Hermes 내부 지점을 계약 시험으로 확인한다

- **status**: `accepted`
- **결정**:
  - `dashboard-profile-api` 는 `token_auth_middleware` 를 바꿔 끼우는 방식을 유지한다. v2026.9.24 의 공개 확장점으로는 Hermes 처리기를 감싸는 경로를 대신할 수 없다.
  - plugin 이 기대는 Hermes 내부 지점을 `hermes/tests/hermes_contract.py` 한곳에 선언한다.
    `hermes/tests/test_hermes_contract.py` 가 두 가지를 확인한다. plugin 이 쓰는 Hermes 이름이 모두 선언에 있는지, 선언한 지점이 지정한 Hermes 소스에 그대로인지다.
  - Hermes 를 올리기 전에 새 판의 tag 로 `scripts/check-hermes-contract.sh` 를 돌린다. CI 의 hermes job 은 선언의 `HERMES_VERSION` 으로 같은 확인을 돌린다.
  - plugin 을 기능 모듈 여럿으로 나눈다. 나누는 경계는 아래 「모듈 경계」 를 따른다. plugin 이름은 바꾸지 않는다.
- **맥락**:
  처음에 이 plugin 은 대시보드의 profile 경로를 토큰으로 여는 일만 맡았다([ADR-018](../../../backend/docs/adr/ADR-018-사람을-더하는-것을-control-plane-이-끝낸다.md), [ADR-041](../../../docs/adr/ADR-041-hermes-에-설치하는-plugin-과-profile-틀은-이-저장소가-소유한다.md)).
  지금은 커넥터의 카탈로그, 설치, 실행, 바인딩, vault, SOUL, 도구와 실행 공간 설정([ADR-086](../../../docs/adr/ADR-086-셸과-파일-도구는-사용자별-docker-실행-공간에서만-돈다.md)), 자식 session 의 provider 읽기([ADR-067](../../../backend/docs/adr/ADR-067-native-하위-에이전트의-provider-는-대시보드-plugin-이-session-저장소에서-읽어-준다.md))까지 맡는다.
  그 결과 한 파일이 3,315줄이 됐다.
  - 위험이 둘 있다. 첫째는 Hermes 를 올릴 때다.
    이 plugin 은 내부 모듈 속성을 바꿔 끼우고, 밑줄로 시작하는 함수를 import 하고, session 저장소를 직접 읽는다.
    v0.21.5 로 올릴 때 `PUT /api/config` 가 500 으로 깨졌는데, 시험은 가짜 Hermes 모듈로 돌아 이것을 미리 잡지 못했다.
  - 둘째는 리뷰다. 보안 판정이 한 파일에 몰려 있어 어디까지 봐야 하는지가 흐리다. 나란히 진행한 PR 들이 이 파일에서 크게 충돌했다.
  - 2026-10-06 에 운영 판 v2026.9.24(v0.21.5)의 상류 tag 소스에서 공개 확장점을 조사했다. 판정 근거는 다음과 같다.

  | 확장점 | v2026.9.24 의 동작 | 우리 요구와의 관계 |
  | --- | --- | --- |
  | `register_token_route(path)` | 경로 문자열을 정확히 비교하고 메서드를 보지 않는다. 등록된 경로에서 토큰이 없으면 쿠키로 넘기지 않고 401 이다 | 사람의 쿠키 경로를 막는다. `PUT /api/env` 만 열 수 없고 `DELETE` 까지 열린다. 이름이 든 경로를 패턴으로 받지 못한다 |
  | `ctx.register_dashboard_auth_provider` | provider 를 등록한다. 경로를 열지 않는다 | 이미 쓴다 |
  | `ctx.register_hook`, `ctx.register_middleware` | agent, gateway 사건과 도구, LLM 호출에만 건다 | 대시보드 HTTP 요청에 걸 수 없다 |
  | 대시보드 plugin 의 `api` router | `/api/plugins/<이름>/` 아래 새 경로를 붙인다 | 우리 경로만 만들 수 있다. Hermes 처리기의 앞과 뒤에서 검사하지 못한다 |

  Hermes 처리기를 감싸는 경로는 처리기 앞에서 본문을 검사하고, 뒤에서 실패하면 쓴 설정을 되돌린다. 그러려면 요청 단위 hook 이 필요한데 공개 확장점에는 없다.
  상류의 plugin 호환 약속은 문서화한 `PluginContext` 메서드와 provider 인터페이스까지다. 내부 import 경로는 약속 밖이라고 적혀 있다.
- **대안 기각**:
  - `register_token_route` 로 경로를 등록한다. 위 표의 이유로 사람의 대시보드가 막히고 메서드 단위로 열 수 없다.
  - `/api/plugins/<이름>/` 아래에 대리 경로를 만들고 그 안에서 Hermes 처리기 함수를 직접 부른다. 쿠키 경로는 그대로 남지만, 처리기 함수 열한 개가 모두 내부 import 가 된다.
    이 처리기들은 2026년 9월의 모듈 분해 때 실제로 자리를 옮겼다. 바꿔 끼우기 하나를 내부 의존 열한 개로 바꾸는 셈이다.
  - plugin 이 직접 답하는 경로(커넥터, vault, `model-defaults`, 자식 session 의 provider)만 `api` router 로 옮긴다. 이 경로들은 옮길 수 있다.
    그러나 바꿔 끼우기는 감싸는 경로 때문에 남는다. 경로 인자를 query 로 옮겨야 해서 Control Plane 이 부르는 주소가 바뀌고, 운영 설정에서 plugin 을 켜야 한다.
    내부 의존을 줄이지 못하고 계약만 바꾸므로 지금은 옮기지 않는다.
  - 운영 저장소의 live 검사에만 맡긴다. live 검사는 새 이미지를 띄운 뒤에야 돈다. 계약 시험은 tag 하나로 몇 초 안에 깨질 곳을 보여 준다.
  - Hermes 를 import 해서 시험한다. Hermes 의 의존성을 모두 설치해야 하고 import 만으로도 설정 파일을 읽는다. 구문만 읽어도 이름, 시그니처, 표의 칸, 경로를 확인할 수 있다.
  - plugin 이름을 역할에 맞게 바꾼다. 운영의 설치 경로와 배포 대상, 로그 이름과 접두어가 함께 바뀐다. 옛 디렉터리가 남으면 provider 가 두 번 등록된다. 기능 이득은 없다.
- **모듈 경계**:
  plugin 디렉터리 안에 평평한 모듈을 둔다. Hermes 의 plugin loader 는 디렉터리를 패키지로 불러오므로 상대 import 가 된다.
  의존은 `common` 에서 기능 모듈을 거쳐 `routes` 와 `__init__` 로 가는 한 방향이다.

  | 모듈 | 맡는 것 |
  | --- | --- |
  | `common` | 응답과 본문 읽기, profile 이름 검사, 원자적 쓰기, `.env` 줄, 공용 표식과 logger |
  | `profiles` | 만들기와 설정 틀, 지우기 판정, 스킬 목록과 토글 검사, `model-defaults` |
  | `session` | 자식 session 의 provider 읽기 |
  | `sandbox` | 실행 공간 정책과 `terminal:` 설정 |
  | `toolconfig` | `PUT /api/config` 의 도구 목록과 스킬 경로 검사 |
  | `env` | `PUT`, `DELETE /api/env` 의 검사 |
  | `connector_manifest` | 운영 목록, `connector.json` 검증, 카탈로그, 소유 기록 형식 |
  | `connector_vault` | 보관 파일 |
  | `connector_install` | 옛 설치와 바인딩 설치, 이름 대응, probe 검사 |
  | `connector_run` | `call` 과 `execute`, MCP SDK 판 확인 |
  | `routes` | provider, 바꿔 끼운 `token_auth_middleware`, 경로 표 |
  | `__init__` | `register` 와 비밀값 판정 |

  - 요청 검사 함수(`_check_*`)는 검사하는 기능의 모듈에 둔다. 검사가 그 기능의 내부 상태를 읽기 때문이다. `routes` 는 경로와 검사 함수를 잇기만 한다.
  - 하위 모듈 이름으로 `gateway`, `toolsets`, `plugins` 를 쓰지 않는다. Hermes 의 최상위 이름과 같아 읽는 사람이 헷갈린다.
  - 기능을 바꾸지 않고 위 경계의 모듈로 나눴다. 기존 `hermes/tests` 전체가 회귀 기준이고 함수 본문은 그대로 유지한다.
    `__init__.py` 는 `register` 와 기존 이름을 다시 내보낸다. 시험 적재 도우미는 패키지와 하위 모듈을 함께 등록하고 정리하며, mock 도 같은 이름을 쓰는 모듈에 함께 넣는다.
    설치 묶음은 모든 Python 모듈을 복사하고 파일별로 비교한다. `fos-ctx` 도 서명과 문맥, 커넥터 정책, 자식 등록, hook 진입점으로 나눴다.
- **결과**:
  - 얻는 것:
    - Hermes 를 올리기 전에 깨질 곳이 이름 단위로 보인다. 앞선 판 `v2026.8.31` 에 돌리면 `reload_gateway_plugins` 와 `_config_profile_scope` 가 없다고 실패한다.
    - plugin 이 새 Hermes 이름을 import 하면 선언에 더하지 않은 채로는 시험이 통과하지 않는다. 모르는 패키지를 import 해도 실패한다. 쓰지 않게 된 이름이 선언에 남아도 실패한다.
    - 모듈을 나누면 보안 판정을 기능 단위로 리뷰할 수 있다. 나란히 진행하는 PR 이 서로 다른 파일을 고친다.
  - 감당할 것:
    - 계약 시험은 이름, 시그니처, 일부 반환 모양, 표의 칸, 경로와 처리기가 받는 칸, 미들웨어 순서를 본다. 같은 이름의 동작이 바뀐 것은 잡지 못한다. 배포 뒤 실제 실행을 한 번 왕복시키는 확인은 그대로 필요하다.
    - plugin 이 직접 답하는 경로 목록은 plugin 코드와 대조하지 않는다. 그 경로를 더하면 선언도 함께 고친다.
    - CI 의 hermes job 이 상류 저장소에서 tag 하나를 받는다. 상류 저장소에 닿지 못하면 job 이 실패한다.
    - 상류 저장소의 tag 와 운영 이미지 안의 소스가 다를 수 있다. 이미지에서 꺼낸 소스 디렉터리를 스크립트에 줘서 확인할 수 있다.
    - `register_token_route` 와 `is_token_route` 의 시그니처가 바뀌면 시험이 실패한다. 메서드 단위 등록 같은 확장점이 생겼을 수 있으니 이 판정을 다시 본다.
    - 원래 `token_auth_middleware` 는 토큰을 받아들인 provider 를 구분하지 않는다. 다른 plugin 이 등록한 토큰 경로는 우리 토큰으로도 열린다. 바꿔 끼운 함수가 우리 경로가 아닌 요청을 원래 함수에 그대로 넘기기 때문이다.
    - 바꿔 끼운 함수가 직접 답하는 경로는 대시보드의 Host 검사보다 앞에서 끝난다. 이 경로들은 Bearer 토큰을 요구한다.
- **적용 범위**: `hermes/plugins/dashboard-profile-api/`, `hermes/tests/hermes_contract.py`, `hermes/tests/test_hermes_contract.py`, `scripts/check-hermes-contract.sh`.
  Hermes 를 올리는 순서는 [`hermes/docs/hermes-contract.md`](../hermes-contract.md) 의 「대시보드 plugin 이 기대는 내부 지점」 이 갖는다. 운영 절차는 `fos-home-infra` 가 갖는다.
