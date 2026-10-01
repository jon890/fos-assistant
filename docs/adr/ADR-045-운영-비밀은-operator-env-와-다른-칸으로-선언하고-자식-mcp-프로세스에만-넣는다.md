## ADR-045: 운영 비밀은 operator_env 와 다른 칸으로 선언하고 자식 MCP 프로세스에만 넣는다

- **status**: `accepted`
- **결정**: `connector.json` 의 `operator_env` 는 비밀이 아닌 운영 설정만 담는다. 서비스 주소가 그 예다.
  운영자가 주는 비밀(서비스 계정 key 같은 것)은 `operator_secrets` 칸으로 따로 선언한다.
  운영 비밀의 원문은 사용자 profile 의 `.env`, `.fos-connectors.json`, `config.yaml`, 설정 백업, Control Plane DB, 어느 응답에도 들어가지 않는다. 필요한 순간 자식 MCP 프로세스의 env 에만 들어간다.
  **지금은 `operator_secrets` 를 지원하지 않는다.** 이 칸이 비어 있지 않은 manifest 는 대시보드 plugin 의 검증이 거절하고 그 커넥터를 카탈로그에 내지 않는다.
- **맥락**: `operator_env` 의 값은 설치할 때 profile `config.yaml` 의 `mcp_servers.<name>.env` 와 소유 기록 `.fos-connectors.json` 에 그대로 복제된다([ADR-041](ADR-041-hermes-에-설치하는-plugin-과-profile-틀은-이-저장소가-소유한다.md)).
  지금 운영 값은 공개 주소라 문제가 없지만, 비밀을 같은 칸에 넣으면 사용자 profile 마다 원문이 디스크에 남는다.
  원문을 복제하지 않으려면 서버 정의에 `${이름}` 참조만 두고 실행하는 쪽이 자기 프로세스 env 에서 값을 찾아야 한다.
  Hermes v0.21.5(`v2026.9.24`) 는 그렇게 하지 않는다.
  `tools/mcp_tool_config.py` 의 `_interpolate_env_vars` 는 `${이름}` 을 `agent/secret_scope.py` 의 `get_secret` 으로 푼다.
  `get_secret` 은 profile scope 가 걸린 공유 gateway(multiplex)에서 그 profile 의 `.env` 에 없는 이름을 프로세스 env 에서 찾지 않고 기본값을 돌려준다. 다른 profile 의 값이 새지 않게 하려는 설계다.
  프로세스 env 를 읽는 예외는 `PATH`, `HOME` 같은 정해진 전역 이름뿐이고 plugin 이 그 목록에 이름을 더할 길이 없다.
  그래서 gateway 프로세스 env 의 값을 참조로 자식에게 넘길 수 없다.
- **대안 기각**:
  - 운영 비밀을 `operator_env` 에 넣는다: 원문이 사용자 profile 의 설정과 소유 기록에 복제된다.
  - 운영 비밀을 사용자 profile `.env` 에 쓴다: 참조는 풀리지만 원문이 profile 마다 남고, 해제와 교체 때 모든 profile 을 고쳐야 한다.
  - 확인 호출(`call`)에서만 지원한다: 대시보드가 띄우는 자식에는 넣을 수 있지만 gateway 가 띄우는 실행 경로의 자식은 받지 못한다. 확인은 통과하고 실행은 실패하는 커넥터가 된다.
  - Hermes 의 외부 secret source 를 쓴다: profile 마다 설정하는 Hermes 기능이고 계약을 따로 조사해야 한다. 운영 비밀이 필요한 커넥터가 생길 때 다시 본다.
  - Hermes core 의 전역 이름 목록을 고친다: core 를 고치지 않는다([ADR-001](ADR-001-hermes를-런타임으로-두고-core를-고치지-않는다.md)).
- **결과**:
  - 얻는 것: manifest 를 쓰는 사람이 운영 설정과 운영 비밀을 다른 칸에 적는다. 비밀이 필요한 커넥터는 조용히 원문을 복제하는 대신 카탈로그에서 빠지고 로그에 까닭이 남는다.
  - 감당할 것: 운영 비밀이 필요한 커넥터는 지금 붙일 수 없다. `operator_env` 의 값이 비밀이 아닌지는 코드가 판정하지 못하고 운영자가 지킨다.
- **다음**: 운영 비밀이 필요한 커넥터가 생기면 Hermes 의 외부 secret source 와 그때의 `get_secret` 계약을 조사해 주입 방법을 정한다.
