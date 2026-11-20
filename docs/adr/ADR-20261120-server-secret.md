## ADR-20261120 / server-secret: 서버 비밀값은 KEK 로 감싸 데이터베이스에 두고 관리자 화면에서 바꾼다

- **status**: `accepted`
- Date: 2026-11-20
- **decision-type**: `architecture`
- **areas**: `security, operations`
- **현재 규칙의 원본**: [`AGENTS.md`](../../AGENTS.md) 「지켜야 할 것」, [`docs/code-architecture.md`](../code-architecture.md) 「비밀값을 두는 곳」, 스펙 [`secret-store/spec.md`](../features/secret-store/spec.md), 설계 [`secret-store/design.md`](../features/secret-store/design.md)
- [ADR-20261008 / data-encryption](../../backend/docs/adr/ADR-20261008-data-encryption.md) 의 KEK 를 그대로 쓰고, KEK 교체 때 다시 감싸는 대상에 `server_secret` 줄을 더한다.

### 결정

**KEK 하나만 홈서버 파일에 두고, Control Plane 과 Hermes 가 쓰는 서버 비밀값은 KEK 로 감싸 `server_secret` 테이블에 둔다. 관리자가 `/admin` 화면에서 넣고 바꾸며 원문은 다시 보이지 않는다.**

| 무엇 | 값 |
| --- | --- |
| 옮기는 값 | 웹 푸시 VAPID 키와 연락처, 브라우저 중계 서명 비밀, profile API key, provider API key |
| 남기는 값 | KEK, 데이터베이스 비밀번호, 웹과 Control Plane 의 HMAC 과 웹 로그인 비밀값, Hermes 대시보드 토큰, 플러그인 보관 파일 key, 금융 해시 key, 구독 로그인 파일 |
| 감싸기 | 값 자체를 활성 KEK 로 AES-256-GCM 으로 감싼다. AAD 는 `server_secret:<이름>` 이다. 데이터 key 층을 두지 않는다 |
| KEK 가 없을 때 | 저장을 거절한다. 원문으로 두는 길은 없다. 값을 쓰는 기능은 꺼진 채 뜬다 |
| 반영 | Control Plane 이 쓰는 값은 저장 즉시. Hermes 가 쓰는 provider key 는 Control Plane 이 대시보드 plugin 으로 Hermes 루트 credential 목록에 맞춰 넣는다 |
| 옛 설정 | 이름마다 줄이 없을 때 한 번 가져온다. 옛 파일과 환경 변수는 운영이 지운다 |

### 맥락

값을 넣거나 바꿀 때마다 홈서버 파일을 만들고 컨테이너를 다시 띄워야 했다. 웹 푸시 VAPID 키는 머지 전에 홈서버 디렉터리부터 만들어야 했다.
사용자 본문은 이미 KEK 로 감싼 데이터 key 로 암호화하고, KEK 는 데이터베이스와 백업에 없다. 같은 KEK 로 서버 비밀값을 감싸면 데이터베이스 dump 만으로는 비밀값을 읽지 못하는 성질이 그대로 남는다.
「비밀값을 데이터베이스에 넣지 않는다」 규칙은 원문 유출을 막으려는 것이라 「원문으로 넣지 않는다」 로 바꿔도 막는 것이 같다.

### 대안 기각

- **파일과 환경 변수를 그대로 쓴다**: 값을 바꿀 때마다 홈서버 작업과 재기동이 든다. 사용자가 이 불편을 이유로 바꾸자고 했다.
- **모든 비밀값을 옮긴다(KEK 와 데이터베이스 비밀번호만 남김)**: 웹은 데이터베이스를 읽지 않아 HMAC 비밀값을 기동 때 받아 와야 하고, 대시보드 토큰은 동기화 통로 자체라 기동 순서가 꼬인다. 보관 파일 key 를 Control Plane 이 쥐면 [ADR-20261103 / connector-vault-key](../../hermes/docs/adr/ADR-20261103-connector-vault-key.md) 의 key 분리가 사라진다.
- **[ADR-20261008 / data-encryption](../../backend/docs/adr/ADR-20261008-data-encryption.md) 이 기각한 「KEK 하나로 바로 암호화」 와 다른 점**: 그 ADR 은 사용자 한 명의 본문만 지울 수 없고 KEK 를 바꾸면 모든 본문을 다시 써야 해서 기각했다. 서버 비밀값은 사용자에 속하지 않아 줄을 지우면 지워지고, 값이 짧고 수가 적어 KEK 교체 때 모두 다시 감싸도 비용이 작다.
- **서버용 데이터 key 층을 둔다**: 사용자 본문과 같은 세 층이 되지만 서버 비밀값은 줄을 지우면 지워져 데이터 key 를 따로 지울 일이 없다. 값이 짧고 수가 적어 KEK 교체 비용도 작다.
- **원문을 다시 보이는 단추를 둔다**: 옮겨 적기 쉽지만 화면과 브라우저 기록으로 원문이 나갈 길이 생긴다. 플러그인 연결 화면도 원문을 다시 보이지 않는다.
- **클라우드 KMS**: KEK 가 서버 밖에 있어 풀 때마다 기록이 남지만 홈서버 하나에 외부 의존과 비용이 는다. `KeyEncryptionKeys` 뒤에 두었으므로 나중에 바꿀 수 있다.

### 결과

- 얻는 것:
  - 관리자가 홈서버에 들어가지 않고 값을 넣고 바꾼다. VAPID 와 중계 서명 비밀은 서버가 만들어 사람이 값을 다루지 않는다.
  - 데이터베이스와 백업만으로는 서버 비밀값을 읽지 못한다.
- 감당할 것:
  - **KEK 를 잃으면 서버 비밀값을 모두 다시 넣어야 한다.** VAPID 는 새로 만들고 모든 기기가 다시 등록한다. profile key 는 다시 만들어 Hermes 에 써야 한다.
  - KEK 와 데이터베이스를 함께 가진 사람은 비밀값을 읽는다. 사용자 본문과 같은 위협 모델이다.
  - provider key 는 Hermes 가 자기 `auth.json` 에 원문으로 저장해 Hermes 쪽에 사본이 남는다. 그 까닭은 PR3 이 `hermes/docs/adr/` 에 따로 남긴다.
  - 이 결정 앞의 버전으로 내리면 옛 파일과 환경 변수가 남아 있어야 그 값을 쓰는 기능이 돈다. 운영은 각 단계가 안정된 뒤에 옛 설정을 지운다.
- **확인 방법**: 관리자 API 통합 테스트가 응답과 로그에 원문이 없고 줄이 암호문인지, KEK 가 없으면 거절하는지 본다.
