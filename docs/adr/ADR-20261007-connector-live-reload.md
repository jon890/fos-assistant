## ADR-20261007: 커넥터를 붙이면 공유 gateway 의 MCP 설정 맞추기로 반영하고 Control Plane 이 두 주기 뒤 스스로 확인한다

- **status**: `accepted`
- Date: 2026-10-07
- **결정**: 커넥터를 에이전트에 처음 붙이거나 스킬만 바뀐 설치는 공유 gateway 를 재시작하지 않는다.
  공유 gateway 가 `config.yaml` 의 `mcp_servers` 를 주기적으로 맞추는 동작에 반영을 맡긴다.
  Control Plane 은 설치한 시각에서 두 주기 뒤 그 바인딩의 반영 맞추기를 스스로 돌려 `READY` 로 둔다. 관리자 반영 완료를 기다리지 않는다.

  | 설치가 바꾼 것 | 반영 방법 | 대시보드 답 |
  | --- | --- | --- |
  | 새 MCP 서버 이름 | gateway 의 다음 맞추기 주기가 연결한다 | `reload_pending: true`, `restart_required: false` |
  | 스킬 파일 | 같은 쓰기에서 `skills.disabled` 의 색인 표식을 새 값으로 바꾼다 | `reload_pending: true` |
  | 이름 대응 파일만 | `fos-ctx` 가 호출마다 읽으므로 따로 할 것이 없다 | `reload_pending: true` |
  | 이미 있던 서버의 정의나 그 서버의 `.env` 값 | 이름이 같아 맞추기가 다시 연결하지 않는다. 지금처럼 재시작을 기다린다 | `restart_required: true` |
  | `fos-ctx` plugin 파일 | 지금처럼 재시작을 기다린다 | `plugin_updated: true` |
  | 떼기 | gateway 의 다음 주기가 그 서버만 끊는다. 스킬은 색인 표식을 바꿔 다음 실행에서 뺀다 | `restart_required: false` |

- **맥락**: [ADR-083](ADR-083-커넥터는-사용자가-한-번-연결하고-자기-에이전트에-여럿-붙여-그-에이전트가-도구를-직접-부른다.md) 의 바인딩은 붙이면 재시작 대기가 되고, 관리자가 공유 gateway 를 재시작한 뒤 반영 완료를 눌러야 `READY` 가 됐다. 재시작은 모든 사용자의 대화를 잠깐 끊는다.
  운영과 같은 Hermes v0.21.5 이미지의 일회성 컨테이너(`--network none`)에서 아래를 확인했다(2026-10-07).
  - 공유 gateway 의 housekeeping 이 60초마다 profile 마다 `mcp_servers` 의 이름과 살아 있는 연결을 맞춘다(`gateway/run_profile_reconcile.py` 의 `_mcp_config_reconciler`). 새 이름은 연결하고 빠졌거나 `enabled: false` 인 이름은 그 profile 의 그 서버만 끊는다. 서버를 더하고 40초에서 60초 안에 그 profile 의 다음 실행이 새 도구를 불렀다. 같은 profile 의 다른 서버와 다른 profile 의 서버는 PID 가 그대로였다
  - API server 는 요청마다 agent 를 새로 만들고 설정을 다시 읽는다. 새로 연결된 MCP 도구는 다음 실행부터 보인다
  - 맞추기는 이름만 비교한다. `.env` 의 토큰만 바꾸면 옛 프로세스가 옛 값으로 남았다
  - gateway 의 스킬 색인 캐시 키에는 디렉터리 내용이 없다. profile 스킬 디렉터리에 스킬을 더해도, 서버를 함께 더해도 다음 실행의 색인에 나타나지 않았다. `skills.disabled` 에 없는 이름을 하나 넣자 그 profile 의 다음 실행 색인에 모두 나타났다
  - 대시보드와 공유 gateway 는 같은 컨테이너의 다른 프로세스다. 둘 사이의 통로는 파일과 제어 소켓뿐이고 제어 소켓에는 MCP 를 다시 읽는 동사가 없다. gateway 의 MCP 연결 상태를 밖에서 읽을 공개 경로도 없다

- **대안 기각**:
  - 대화의 `/reload-mcp`: profile 범위로 다시 연결하지만 API server 경로에서는 명령으로 처리되지 않는다
  - 제어 소켓의 `reload-plugins`: `fos-ctx` 갱신을 그 profile 에만 반영하지만, 다시 읽는 동안 hook 목록이 비어 그 profile 의 커넥터 호출이 판정 없이 나갈 수 있다. `invoke_hook` 이 발견 잠금을 잡지 않고 목록을 읽는다
  - `unserve-profile` 뒤 `serve-profile`: adapter 만 다시 만들고 MCP 연결은 그대로 쓴다. 값 교체에 효과가 없다
  - `fos-ctx` 가 gateway 프로세스 안에서 내부 MCP 함수를 부르는 것: 즉시 다시 연결하지만 Hermes 내부 함수에 기대고 plugin 적재에 부작용을 둔다
  - 값 교체도 `enabled: false` 로 한 주기 끄고 되돌리는 것: 실측으로 된다. 그 커넥터가 2-3분 멈추고 Control Plane 에 상태가 하나 더 는다. 토큰 교체는 드물어 이번에는 재시작 대기로 둔다

- **결과**:
  - 얻는 것: 커넥터를 붙여도 다른 사용자의 대화가 끊기지 않는다. 관리자 반영 완료는 값 교체와 `fos-ctx` 갱신에만 남는다. 떼었을 때 스킬 색인에 이름이 남던 결함도 같이 없어진다
  - 감당할 것:
    - 반영 판정은 gateway 를 직접 보지 못한다. 두 주기를 기다린 뒤 설치 상태와 probe 로 판정한다. probe 가 성공해도 gateway 쪽 연결만 실패한 경우는 첫 실제 호출의 오류로 드러난다
    - 이 동작은 Hermes 의 공식 계약이 아니라 소스와 실측으로 확인한 것이다. Hermes 를 올릴 때 계약 시험이 맞추기 동작과 색인 캐시 키를 확인한다
    - 떼고 한 주기 안에 다시 붙이면 gateway 가 옛 연결을 끊지 않은 채 그대로 쓴다. 그 사이 값이 바뀌었으면 옛 값이 남는다
    - 이어지는 대화는 저장한 시스템 프롬프트를 다시 쓸 수 있어 새 스킬 색인이 새 대화부터 보일 수 있다

- **적용 범위**: 바인딩 설치(`mode: bind`)만 해당한다. 옛 커넥터 에이전트의 설치는 지금 판정 그대로다.
