## ADR-041: Hermes 에 설치하는 plugin 과 profile 틀은 이 저장소가 소유한다

- **status**: `accepted`
- **결정**: Control Plane 이 기대는 Hermes 쪽 코드를 이 저장소의 `hermes/` 에 둔다.
  대상은 대시보드 plugin `dashboard-profile-api`, MCP 호출 서명 plugin `fos-ctx`, 새 profile 설정 틀이다.
  운영 값(Control Plane MCP 주소, 커넥터 plugin 경로, 커넥터 실행 파일, 토큰)은 코드에 두지 않고 설치할 때 받는다.
  설치 묶음은 `hermes/bundle.sh` 가 만든다. 운영은 Control Plane 을 배포할 SHA 의 묶음을 설치하고, plugin 을 먼저 올린 뒤 Control Plane 을 올린다.
- **맥락**: 이 저장소는 사용자가 여러 에이전트를 정의하고 연결해 거느리는 Hermes Control Plane 으로 공개한다.
  그런데 에이전트 만들기, 스킬 게시, env 쓰기, MCP 호출 서명이 모두 비공개 저장소의 plugin 에 있었다.
  이 저장소만 받은 사람은 Hermes 와 연동할 수 없었고, Control Plane 과 plugin 의 계약을 바꿀 때마다 두 저장소의 PR 을 맞춰야 했다.
  코드에 박힌 운영 값은 커넥터 경로, 커넥터 실행 파일의 경로, 틀의 MCP 주소 셋뿐이었다.
- **대안 기각**:
  - 비공개 저장소에 그대로 둔다: 공개한 Control Plane 이 혼자 동작하지 못한다.
  - 별도 공개 저장소를 둔다: Hermes 를 다른 용도로 쓰는 사람도 가져갈 수 있지만, Control Plane 과 plugin 의 계약을 두 저장소에서 따로 맞춰야 한다. 지금 이 plugin 을 쓰는 곳은 이 Control Plane 하나다.
  - 설치 스크립트 전체를 옮긴다: 컨테이너 이름, 홈서버 경로, 재시작 방법은 운영마다 다르다. 공개 쪽은 설치할 파일 묶음을 만드는 데까지만 맡는다.
  - 배포마다 plugin 을 설치한다: 설치는 대시보드 재시작을 부른다. `hermes/` 가 바뀐 배포에서만 설치한다.
- **결과**:
  - 얻는 것: 이 저장소 하나로 Hermes 연동이 끝난다. Control Plane 과 plugin 의 계약을 한 PR 에서 바꾸고 같은 CI 가 검사한다. 운영의 plugin 은 늘 배포한 Control Plane 과 같은 판이다.
  - 감당할 것: 공개 CI 에 Python 검사가 더해진다. `hermes/` 에도 운영 값을 적지 않는 규칙이 걸린다.
    plugin 은 한 배포 동안 옛 Control Plane 의 호출도 받아야 하므로, 경로를 바꿀 때는 새 경로를 더하고 옛 경로를 다음 배포에서 뺀다.
    실제 Hermes 와 맞는지는 이 저장소의 CI 가 아니라 운영 저장소의 live 검사가 본다.
  - 앞선 결정과의 관계: ADR-019, ADR-031, ADR-037 은 plugin 의 소유와 검사를 `fos-home-infra` 가 갖는다고 적었다. 이 결정 뒤로 plugin 원본과 단위 검사는 이 저장소가 갖고, 설치와 live 검사만 운영 저장소가 갖는다.
