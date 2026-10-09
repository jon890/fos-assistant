## ADR-045: 커넥터 에이전트는 자기 MCP 서버만 받고 Memory 와 Control Plane 도구를 받지 않는다

- **status**: `accepted`
- **결정**: 커넥터 연결의 전용 에이전트([ADR-039](ADR-039-외부-서비스-연결은-사용자별-전용-에이전트로-실행한다.md))는 외부 서비스의 데이터를 읽는 worker 로 본다.
  이 에이전트의 실행에는 세 가지를 주지 않는다.
  - Control Plane MCP(`fos-assistant`) 도구. profile 의 API 도구 목록은 그 profile 에 설치한 커넥터의 MCP 서버 이름과 그 커넥터의 manifest 가 선언한 읽기 전용 이미지 도구만 갖고, `mcp_servers` 의 Control Plane MCP 등록도 지운다. manifest 가 선언할 수 있는 내장 도구는 [ADR-044](ADR-044-커넥터-manifest-는-읽기-전용-이미지-도구만-열-수-있다.md) 가 허용한 것뿐이다. 그 profile 의 MCP 토큰과 `fos-ctx` plugin 은 그대로 둔다. 목록은 대시보드 plugin 의 설치가 쓰고, 설치한 커넥터가 없으면 `no_mcp` 하나를 둔다.
  - 호출자의 Memory 문맥. 사용자가 직접 연 대화와 위임받은 실행 모두에서 Control Plane 이 Memory 를 조립하지 않는다.
  - Control Plane MCP 호출의 수락. origin 실행의 에이전트가 커넥터 에이전트이면 Control Plane 이 도구 호출을 거절한다. 거절은 도구 호출의 요청자 판정에 두고 토큰 인증은 바꾸지 않는다. profile 설정이 옛 모양으로 남아 있어도 이 판정은 바로 걸린다.

  필요한 맥락은 부르는 쪽(Chief)이 위임 요청의 `task` 에 담는다.
  위임 결과는 지금처럼 Control Plane 이 실행 줄의 답을 부모 대화의 다음 turn 으로 전한다([ADR-040](ADR-040-위임-결과는-control-plane-이-부모-대화의-다음-turn-을-열어-전한다.md)). 이 경로는 worker 의 MCP 호출을 쓰지 않는다.
- **대체된 부분**: worker 경계 전체는 새 연결에 대해 [ADR-083](../../../docs/adr/ADR-083-커넥터는-사용자가-한-번-연결하고-자기-에이전트에-여럿-붙여-그-에이전트가-도구를-직접-부른다.md) 이 대체한다. 연결을 붙인 일반 에이전트는 이 경계를 받지 않는다. 남은 옛 커넥터 에이전트에는 아직 걸리므로 전체 대체가 아니다. 옛 에이전트가 모두 지워지고 격리 코드를 지울 때 이 결정을 `superseded` 로 바꾼다.
- **맥락**: 커넥터 에이전트는 외부 서비스가 돌려준 글을 모델 입력으로 읽는다. 그 글은 우리가 쓰지 않았고 지시문을 담을 수 있다.
  2026-10-01 감사에서 이 에이전트가 `memory_read`, `artifact_write`, `agent_delegate` 를 포함한 Control Plane 도구 여섯 개와 호출자의 Memory 문맥을 받는 것을 확인했다.
  외부 글이 모델을 속이면 Memory 본문을 읽어 외부 서비스의 쓰기 도구로 내보내거나, 다른 에이전트에게 일을 맡기거나, 대화 폴더에 결과물을 쓸 수 있다.
  Hermes 가 무엇을 할지 정하고 Control Plane 은 누가 무엇을 할 수 있는지 강제한다([ADR-017](../../../docs/adr/ADR-017-무엇을-할지는-hermes-가-정하고-control-plane-은-경계만-갖는다.md)). 이 결정은 그 경계를 worker 에 적용한 것이다.
- **대안 기각**:
  - 도구 목록만 고친다: 이미 설치한 profile 은 공유 gateway 를 재시작할 때까지 옛 도구 목록으로 돈다. Control Plane 이 호출을 거절해야 배포한 순간부터 막힌다.
  - Memory 를 제목만 준다: 제목도 개인 정보이고 본문을 읽는 도구가 없으면 쓸모가 없다.
  - worker 에게 `agent_status` 같은 읽기 도구만 남긴다: 도구마다 판정을 나누면 목록이 바뀔 때 빠지는 것이 생긴다. worker 는 자기 MCP 서버만 쓴다는 한 줄로 둔다.
  - Control Plane 이 `PUT /api/config` 로 목록을 쓴다: 그 경로는 Control Plane MCP 를 목록에 요구한다. 예외를 두면 일반 에이전트의 검사가 약해진다. 설치가 목록을 함께 쓰면 서버 등록과 허용 목록이 한 번에 맞는다.
- **결과**:
  - 얻는 것: 외부 글이 모델을 속여도 닿는 범위가 그 커넥터의 MCP 도구와 그 대화의 답으로 한정된다. Memory, 결과물 폴더, 다른 에이전트로 이어지는 길이 없다.
  - 감당할 것: worker 는 사용자의 Memory 를 모른다. Chief 가 맥락을 `task` 에 담지 않으면 worker 의 답이 얕아진다.
    worker 는 결과물을 쓰지 못하고 하위 위임을 하지 못한다. 그 일은 결과를 받은 Chief 가 한다.
    이미 설치한 profile 은 연결 확인이나 관리자의 반영 완료가 설치를 다시 보내 새 목록으로 바꾼다. 도구 목록은 다음 실행부터 적용되고, `mcp_servers` 의 Control Plane MCP 등록 제거는 이미 떠 있는 gateway 에 재시작 전까지 남을 수 있다. 그동안에도 목록이 그 서버를 막고 Control Plane 이 호출을 거절한다([커넥터 연결](../../../docs/backend/connector-install.md)).
    커넥터의 MCP 서버가 가진 쓰기 도구는 그대로 열려 있다. 그 도구의 승인은 이 결정의 범위가 아니다.
- **다음**: 도구 호출마다 승인을 받는 action policy 와 OAuth 연결은 따로 설계한다. 그때 에이전트 종류를 `connectorManaged` 한 칸이 아니라 종류 값으로 나눌지 함께 정한다.
  커넥터 에이전트 대화의 답이 Memory 제안으로 가는 경로는 이 결정이 다루지 않는다. 제안은 사람이 승인해야 남고([ADR-012](ADR-012-memory-는-사람이-승인한-것만-남는다.md)) 기본 설정에서 꺼져 있다. 켤 때 커넥터 에이전트의 대화를 제안 대상에서 뺄지 정한다.
  동작 정책을 넣을 때 `fos-ctx` 의 hook 이 그 profile 의 MCP 토큰으로 Control Plane 에 정책을 묻는 경로를 더할 수 있다. 그래서 토큰을 회수하지 않는다.
