## ADR-043: 커넥터는 plugin 의 connector.json 으로 선언하고 Control Plane 은 범용 흐름만 갖는다

- **status**: `accepted`
- **결정**: 외부 서비스 커넥터는 그 plugin 디렉터리의 `connector.json` 이 선언한다.
  manifest 는 제목, 입력 칸(env 이름, 비밀 여부, 형식, 선택지를 채울 도구), 확인 도구, 운영자가 주는 env 이름, 서비스 오류 코드의 공통 어휘 대응을 담는다.
  대시보드 plugin 이 manifest 를 읽어 카탈로그로 내고, 확인과 선택지 조회는 plugin 의 읽기 전용 MCP 도구를 공식 `mcp` Python SDK 로 한 번 불러 얻는다.
  Control Plane 은 커넥터 id 를 받는 범용 흐름(카탈로그, 선택지, 확인, 등록, 확인, 해제, 관리자 반영)만 갖고, 칸 값은 `connector_connection.fields` JSON 한 열에 둔다.
  [ADR-039](ADR-039-외부-서비스-연결은-사용자별-전용-에이전트로-실행한다.md) 의 「사람마다 전용 profile 과 비공개 에이전트」 는 그대로다.
- **맥락**: 첫 커넥터인 가계부를 붙이며 env 이름, 토큰 형식, 가계부 API 호출, 서버 이름, 전용 표와 화면이 Control Plane 에 들어왔다.
  이 저장소는 누구나 자기 Hermes 에 붙여 쓰는 오픈소스라([ADR-041](ADR-041-hermes-에-설치하는-plugin-과-profile-틀은-이-저장소가-소유한다.md)), 서비스를 하나 붙일 때마다 Control Plane 을 고치면 그 서비스의 코드가 공개 저장소에 쌓인다.
  Hermes v0.21.5 의 `plugin.yaml` `config_schema` 는 입력 칸을 선언하지만 선택지를 도구로 채우는 칸과 확인 도구가 없고, 대시보드와 API server 에 MCP 도구를 대신 부르는 공식 경로도 없다.
- **대안 기각**:
  - 커넥터마다 Control Plane 코드를 더한다: 지금 방식이다. 서비스 코드가 공개 저장소에 쌓이고 커넥터를 붙이는 사람이 이 저장소를 고쳐야 한다.
  - Hermes `plugin.yaml` 의 `config_schema` 만 쓴다: 선택지 도구와 확인 도구를 담지 못한다. 칸 이름은 그것과 맞춰 나중에 옮길 수 있게 한다.
  - 확인을 모델 실행(`/v1/runs`)으로 한다: 비용이 들고 도구를 부를지와 인자가 결정적이지 않다.
  - Hermes 내부 연결 함수를 빌려 도구를 부른다: 내부 이름이라 업그레이드에 깨진다.
  - JSON-RPC 를 직접 구현한다: 의존은 없지만 버전 협상과 종료 처리를 직접 맡는다.
  - 칸 값을 자식 표에 둔다: DB 가 칸 단위 제약을 걸 수 있지만, 커넥터마다 칸이 다르고 칸 단위로 찾을 일이 없어 JSON 한 열을 골랐다(사용자 결정).
- **결과**:
  - 얻는 것: 커넥터를 붙이는 일이 plugin 저장소에 `connector.json` 을 두고 운영 설정에 한 줄을 더하는 것으로 끝난다. Control Plane 은 서비스 이름과 주소를 모른다.
  - 감당할 것: 대시보드 plugin 이 자식 프로세스를 띄워 도구를 부른다. 시간 제한과 동시 실행 수 제한을 두고, manifest 가 허용한 읽기 전용 도구만 부른다.
    `fields` JSON 에 비밀 원문이 들어가는 것을 DB 가 막지 못하므로, 코드가 비밀 칸은 앞부분만 남기고 검사가 원문 부재를 확인한다.
    오류 원문은 서비스마다 다르므로 manifest 의 대응 표가 공통 어휘 넷(`credential_rejected`, `forbidden`, `unavailable`, `invalid_input`)으로 바꾸고, 표에 없는 코드는 `unavailable` 로 본다.
