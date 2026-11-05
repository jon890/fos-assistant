## ADR-20261103 / connector-vault-key: 커넥터 보관 파일은 Control Plane KEK 와 다른 key 로 대시보드 plugin 이 암호화한다

- **status**: `accepted`
- Date: 2026-11-03
- 스펙과 설계는 [`docs/features/connector/spec.md`](../../../docs/features/connector/spec.md), [`design.md`](../../../docs/features/connector/design.md) 가 갖는다.
- [ADR-083](../../../docs/adr/ADR-083-커넥터는-사용자가-한-번-연결하고-자기-에이전트에-여럿-붙여-그-에이전트가-도구를-직접-부른다.md) 의 「값의 원본은 보관 파일이다」 는 그대로다. 그 원본을 평문으로 두던 것을 이 결정이 바꾼다.

### 결정

**연결마다 하나인 보관 파일을 대시보드 plugin 이 AES-256-GCM 으로 암호화한다. key 는 Control Plane 의 KEK 와 다른 key 이고, 대시보드 프로세스의 환경 변수가 가리키는 파일에서 읽는다.**

| 무엇 | 값 |
| --- | --- |
| key 를 읽는 자리 | 대시보드 plugin. 환경 변수 `FOS_ASSISTANT_CONNECTOR_VAULT_KEY_FILE`, `FOS_ASSISTANT_CONNECTOR_VAULT_ACTIVE_KEY_ID` |
| key 파일 모양 | Control Plane KEK 파일과 같다. 한 줄에 `<id>:<base64 32바이트>`. 값은 다르다 |
| 파일 모양 | `{"v": 2, "connector", "key_id", "sealed"}`. `sealed` 는 `v1.<IV>.<암호문과 태그>` 이고 풀면 칸 값의 JSON 이다 |
| AAD | `connector-vault:<vault>:<connector>`. 파일을 다른 연결 이름으로 옮기거나 `connector` 를 바꾸면 풀리지 않는다 |
| key 가 없을 때 | 「key 필수」(`FOS_ASSISTANT_CONNECTOR_VAULT_KEY_REQUIRED`)가 켜져 있으면 쓰기만 거절한다. 꺼져 있으면 평문으로 쓰고 경고한다. 운영은 켠다 |
| 이미 있는 파일 | 대시보드가 기동할 때마다 평문 파일과 옛 key 파일을 활성 key 로 다시 쓰고 남은 수를 로그에 남긴다 |
| key 를 잃거나 되돌릴 때 | 평문으로 되돌리는 경로를 두지 않는다. key 를 잃으면 연결을 다시 등록하고, 이전 이미지로 되돌리면 연결을 해제한 뒤 다시 등록한다 |

### 맥락

보관 파일은 연결의 비밀값 원본인데 평문 JSON 이었다. 서버 디스크나 백업에 보관 파일 디렉터리가 들어가면 모든 사용자의 토큰이 그대로 읽힌다.
Control Plane 은 이미 KEK 로 사용자 데이터 key 를 감싸 대화와 기억을 암호화한다([ADR-20261008 / data-encryption](../../../backend/docs/adr/ADR-20261008-data-encryption.md)). 그 ADR 이 보완으로 꼽은 것 가운데 하나가 보관 파일 암호화다.
보관 파일은 Hermes 컨테이너 안에서 대시보드 plugin 이 쓰고 읽는다. Control Plane 은 원문을 갖지 않는다.

### 대안 기각

- **Control Plane KEK 를 Hermes 에도 준다**: key 하나로 운영이 간단해진다. 그러나 Hermes 컨테이너에서 도는 플러그인과 셸이 대화와 기억까지 풀 key 를 쥐게 된다. Hermes 쪽이 뚫렸을 때 피해가 Control Plane 데이터로 번진다.
- **Control Plane 이 값을 암호화해 보내고 Hermes 는 암호문만 둔다**: 실행할 때 푸는 쪽은 결국 Hermes 이므로 Hermes 가 key 를 가져야 한다. Control Plane 이 원문을 다루는 경로만 늘어난다.
- **연결마다 데이터 key 를 두고 KEK 로 감싼다(Control Plane 과 같은 세 층)**: 연결 수가 적고 연결을 지우면 파일째 지운다. 데이터 key 를 따로 지워 얻는 것(crypto-shredding)이 파일 삭제와 같다.
- **AAD 를 연결 이름으로만 묶는다**: 같은 연결 이름 아래 `connector` 를 바꿔 다른 플러그인의 서버로 값을 넘길 수 있다. 둘 다 묶는다.
- **key 가 없으면 늘 쓰기를 거절한다**: 개발 설치와 테스트가 key 를 만들어야 돈다. 대화 본문의 KEK 와 같이 필수 여부를 환경 변수로 가른다.
- **평문으로 되돌리는 스크립트를 둔다**: 평문이 다시 생기는 길이 남는다. 되돌릴 일은 연결을 다시 등록하는 것으로 충분하다.

### 결과

- 얻는 것:
  - 보관 파일 디렉터리만 담긴 백업이나 디스크 사본으로는 토큰을 읽지 못한다.
  - 파일을 다른 연결로 옮겨 다른 사용자의 값으로 쓰게 할 수 없다.
  - 기동 로그의 남은 옛 key 파일 수로 옛 key 를 지워도 되는 때를 안다.
- 감당할 것:
  - **key 를 잃으면 모든 연결을 다시 등록해야 한다.** key 사본은 운영이 한 곳에 따로 보관한다. 자리는 `fos-home-infra` 가 갖는다.
  - key 는 Hermes 컨테이너 안에 있다. 같은 계정으로 도는 플러그인과 실행 공간을 적용하지 않은 profile 의 셸은 key 와 보관 파일을 함께 읽을 수 있다. 이 결정은 디스크와 백업을 막고, 실행 중인 프로세스의 격리 수준은 바꾸지 않는다.
  - 로컬 플러그인의 profile `.env` 평문 사본은 실행기가 없앴다(스펙 REQ6, REQ7). 원격 플러그인의 값은 예외로 `.env` 에 남는다(스펙 DECISION8).
  - 이 결정을 배포한 뒤 이전 이미지로 되돌리면 v2 파일을 읽지 못하고, 이전 이미지의 쓰기도 그 파일을 덮어쓰지 못한다. 그때는 연결을 해제한 뒤 다시 등록한다.
  - plugin 이 Hermes 이미지의 `cryptography` 에 기댄다. Hermes 를 올릴 때 core 의존에 남아 있는지 확인한다.
- **적용 범위**: `hermes/plugins/dashboard-profile-api` 의 `connector_vault_key.py`, `connector_vault.py`, `connector_vault_migration.py`.
