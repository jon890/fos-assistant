## ADR-20261120 / connector-auto-apply: 재시작 대기는 그 서버만 껐다 켜거나 gateway 재기동을 알아채 스스로 반영하고, 재시작은 운영 정기 작업이 한다

- **status**: `accepted`
- Date: 2026-11-20
- **decision-type**: `architecture`
- **areas**: `operations`
- **현재 규칙의 원본**: [연결 반영 자동화 설계](../features/connector/design.md), [플러그인 연결](../features/connector.md) 의 「바인딩의 반영 맞추기」, [`dashboard-profile-api/README.md`](../../hermes/plugins/dashboard-profile-api/README.md) 의 플러그인 절
- **결정**:
  - 이미 있던 서버의 정의나 값이 바뀐 설치와 뗀 이름을 다시 붙인 설치는 공유 gateway 를 재시작하지 않는다. Control Plane 이 그 profile 의 실행이 비기를 최대 10분 기다린 뒤 대시보드 plugin 에 설정의 그 서버 항목만 `enabled: false` 로 쓰게 하고(소유 기록은 그대로 둔다), 두 맞추기 주기 뒤 다시 켜게 한다. 다시 켠 설치는 [ADR-20261007 / connector-live-reload](ADR-20261007-connector-live-reload.md) 의 반영 예정 확인이 맡는다.
  - `fos-ctx` plugin 파일이 바뀐 설치만 재시작을 기다린다. 대시보드 plugin 이 Hermes 의 `gateway.status` 로 gateway 프로세스의 시작 시각을 답하고, Control Plane 이 그 시각보다 앞서 대기가 된 바인딩을 스스로 반영 맞추기 한다.
  - 공유 gateway 재시작은 운영 정기 작업이 한다. Control Plane 은 재시작 대기 수와 진행 중 실행 수를 서비스 토큰 조회로 답할 뿐 재시작하지 않는다.
  - 관리자 반영 완료는 자동 반영이 실패했을 때 쓰는 비상 수단으로 남는다.
- **맥락**: 재시작 대기 바인딩은 관리자가 gateway 를 재시작하고 반영 완료를 눌러야 `READY` 가 됐다. 운영은 배포 때 이미 gateway 를 재시작하지만 Control Plane 은 그것을 알 길이 없었고, 배포 뒤 정의 어긋남 점검이 다시 설치하면 재시작 뒤에 새 대기가 생겼다. 2026-11-20 에 기본 비서의 네이버 블로그 바인딩이 그렇게 남아 사용자가 직접 확인을 눌렀다. 사용자는 사람이 확인하지 않아도 끝나기를 원했다(스펙 [`spec.md`](../features/connector/spec.md), 확인 2026-11-20).
  [ADR-20261007 / connector-live-reload](ADR-20261007-connector-live-reload.md) 의 실측에서 `enabled: false` 로 한 주기 끄고 되돌리면 값 교체가 재시작 없이 반영됐고 그 커넥터만 2분에서 3분 멈췄다. 그때는 토큰 교체가 드물어 채택하지 않았지만, 실행기 정의의 보관 파일 리비전 때문에 로컬 플러그인의 값 교체와 manifest 변경 뒤 재설치가 모두 정의 변경이 되어 흔해졌다.
- **대안 기각**:
  - 재시작 감지만 쓰고 껐다 켜기를 쓰지 않는다: 배포 재시작 뒤의 재설치가 다시 대기를 만들어 재시작이 한 번 더 필요하다
  - plugin 이 끄고 켜기를 타이머로 혼자 한다: 대시보드가 그 사이 다시 뜨면 서버가 꺼진 채 남고, 실행이 비기를 기다리는 판단에 필요한 실행 기록이 Control Plane 에 있다
  - `fos-ctx` 가 적재될 때 표식을 남겨 재시작을 알린다: plugin 을 다시 읽을 때(`reload_gateway_plugins`)도 표식이 바뀌어 재시작으로 잘못 본다
  - Control Plane 이 마지막으로 본 gateway 식별자를 메모리에 두고 바뀌면 재시작으로 본다: 배포는 Control Plane 도 함께 다시 띄우므로 배포 때의 재시작을 놓친다
  - Control Plane 이나 plugin 이 gateway 를 재시작한다: 사용자 요청에서 재시작하지 않는다는 원칙과 운영이 재시작 수단을 소유한다는 경계를 어긴다
- **결과**:
  - 얻는 것: 값 교체와 배포 뒤 재설치가 몇 분 안에 사람 없이 끝난다. 남은 재시작은 대화가 없는 틈에만 일어난다
  - 감당할 것:
    - 껐다 켜는 동안 그 플러그인 도구는 2분에서 3분 멈춘다. 실행이 10분 넘게 이어지면 진행 중인 그 서버의 호출이 끊길 수 있다
    - gateway 의 `enabled: false` 처리와 `gateway.status` 는 Hermes 의 공식 계약이 아니다. `hermes/tests/hermes_contract.py` 와 Hermes 계약 테스트가 올릴 때 확인한다
    - 꺼진 서버가 남은 채 plugin 을 되돌리면 그 바인딩 하나가 「운영자가 바꾼 서버」 로 거절된다. 운영은 되돌리기 전에 끄기 기록이 빈지 확인한다
    - 자동 경로가 한 번 실패하면 다시 시도하지 않고 관리자에게 넘긴다. 일시 장애도 관리자의 다시 확인이 필요하다
- **확인 방법**: 설계의 「검증 전략」 과 배포 뒤 실제 값 교체 왕복(`fos-home-infra` 절차)
