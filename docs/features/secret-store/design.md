# 서버 비밀값 기술 설계

스펙: [`spec.md`](spec.md). 장기 결정은 [ADR-20261120 / server-secret](../../adr/ADR-20261120-server-secret.md) 이 갖는다.

## 요구사항 연결

| ID | 해결 방법 |
| --- | --- |
| REQ1 | `admin.secret.presentation` 의 관리자 API 와 `web/src/app/admin/secrets/` 화면. 권한은 기존 관리자 영역의 `requireAdmin` 을 쓴다(ADR-063) |
| REQ2 | `crypto.application.ServerSecretService` 가 이름별 상태(`EMPTY`, `SET`), 앞부분, 마지막으로 바꾼 사용자 번호, 시각, 공개 값(VAPID 공개 키와 연락처)만 담은 dto 를 낸다. 앞부분 기준(16자 이상일 때 앞 4자)은 `ConnectorValues` 의 상수를 `crypto.domain` 으로 옮겨 두 곳이 함께 쓴다 |
| REQ3 | 원문 읽기는 `crypto.domain.ServerSecrets` 인터페이스에만 있다. `admin` 은 규칙상 기능의 `application` 과 dto 만 부를 수 있어(`ADMIN_USES_ONLY_FEATURE_APPLICATION`) 원문에 닿지 못한다 |
| REQ4 | `crypto.infra.DatabaseServerSecrets` 가 값을 `KeyEncryptionKeys.wrap` 으로 감싸 `server_secret.sealed_value` 에 쓴다. AAD 는 `server_secret:<이름>` 이다. KEK 가 없으면 `SECRET_STORE_UNAVAILABLE`(409)로 거절하고 줄을 만들지 않는다 |
| REQ5 | 쓰기 트랜잭션이 커밋되면 새 값을 캐시에 바로 넣는다. 읽기가 캐시를 채울 때는 이름별 세대 번호가 읽기 전과 같을 때만 넣는다. 사용처는 매번 `ServerSecrets` 를 부른다(서버 한 대 전제) |
| REQ6 | `notification.application.VapidKeyService.regenerate(admin, subject)` 가 키 쌍을 만들고, 전역 등록 잠금(`PushRegistrationLock`) 안의 한 트랜잭션에서 VAPID 줄을 바꾸고 모든 `app_user.push_devices` 를 비운다. 커밋 뒤 기기가 있던 사용자마다 알림 한 건을 남긴다. 그 알림 종류는 푸시를 보내지 않는다 |
| REQ7 | #817 의 `VapidKeys` 를 기동 때 한 번 읽는 빈에서 `ServerSecrets` 를 읽어 키 쌍을 돌려주는 조회기로 바꾼다. `PushProperties` 의 세 칸과 환경 변수를 지운다. 등록 요청에 구독한 공개 키를 넣고, 서버는 지금 키와 다르면 409 `PUSH_KEY_CHANGED` 로 거절한다. 웹(#817 의 PR1b)은 기존 구독의 키가 지금 공개 키와 다르면 해지한 뒤 다시 구독한다 |
| REQ8 | `DatabaseServerSecrets` 의 쓰기와 지우기가 이름과 사용자 번호만 info 로그로 남긴다 |
| REQ9 | 값의 주인 기능이 가져온다. `browser.application.GatewaySecretImporter` 가 `BrowserProperties` 의 옛 값을 가져온다. 가져오기는 `SmartInitializingSingleton` 으로 웹 서버가 요청을 받기 전에 돈다. 이름의 줄이 있으면(값이 비어 있어도) 건너뛴다 |
| REQ10 | `HermesProfileKeyStore` 가 파일 대신 `ServerSecrets` 의 `profile_key:<profile>` 를 읽고 쓴다. 공개 메서드 모양은 그대로다. `write` 는 값이 NULL 인 줄을 비어 있음으로 보고 쓴다. 지운 profile 이름으로 다시 만들 수 있다 |
| REQ11 | `hermes.infra` 의 profile key 가져오기가 옛 key 디렉터리의 파일을 이름마다 한 번 가져온다. REQ9 와 같은 기동 단계다 |
| REQ12 | `agent.application.ProviderKeyService` 가 공급자마다 `provider_key:<공급자>:<번호>` 줄을 둔다. 별칭과 순서는 그 줄의 `meta` JSON 에 둔다 |
| REQ13 | `ProviderKeyService` 가 대시보드 포트(`HermesDashboardClient`)에 더한 `putCredentialPool` 로 그 공급자의 우리 줄 전체와 방식, 버전 번호를 보낸다. 공급자마다 JVM 잠금 하나 안에서 「최신 커밋 값 읽기와 보내기」 를 한다. 실패는 공급자 줄(`provider:<공급자>`)의 `meta.syncError` 에 남고 성공하면 지운다. 기동 뒤 모든 공급자를 한 번 보낸다 |
| REQ14 | plugin 이 Hermes 루트 목록에서 `source` 가 `manual:fos-assistant:<번호>` 인 줄만 고치고 그 밖의 줄(구독 로그인)보다 뒤 우선순위로 둔다. 쓰기는 Hermes 의 인증 저장 함수와 그 잠금을 거친다. 번호와 key 지문이 같은 줄은 Hermes 의 상태(막힘, `last_status_at`, `request_count`)를 그대로 둔다. 방식은 `credential_pool_strategies.<공급자>` 에 `fill_first` 나 `round_robin` 으로 쓴다. 어느 `config.yaml` 이 profile 실행에 읽히는지는 PR3 의 계약 테스트가 정한다 |
| REQ15 | plugin 의 새 `GET /api/credential-pool/<공급자>` 가 우리 줄마다 상태, 오류 코드, 풀리는 시각, 사용 횟수(`request_count`), 마지막 상태 시각(`last_status_at`)을 원문 없이 낸다. 화면을 열 때 부르고 실패하면 상태 칸만 비운다 |
| REQ16 | plugin 의 새 `POST /api/credential-pool/<공급자>/<번호>/reset` 이 Hermes 의 `reset_status` 를 부른다 |
| REQ18 | `browser.application.GatewaySecretService.regenerate` 가 값을 바꾼다. `admin.secret` 이 이어서 `connector.connection.application` 의 새 서비스 `ConnectorBrowserBindings.reinstallAll()` 를 부른다. 이 메서드는 브라우저를 쓰는 플러그인(`ownerBrowser`)의 바인딩을 상태와 상관없이 기존 `reinstall` 과 같은 두 단계(시작 트랜잭션, 실행)로 다시 설치하고 결과 수를 dto 로 돌려준다. 다시 설치한 바인딩은 Hermes 재시작이 필요해 재시작 대기가 된다(아래 「실패와 일관성」) |
| REQ19 | `agent` 의 기동 단계가 함께 쓰는(`SHARED_HOUSEHOLD`) profile 목록을 plugin 의 새 `DELETE /api/env/provider-keys` 에 보낸다. plugin 이 Hermes `PROVIDER_REGISTRY` 의 API key 이름만 지우고 공급자별 줄 수를 돌려준다. 모든 profile 이 성공했을 때만 표지 줄(`import:provider_env`)을 남긴다. 실패하면 다음 기동에서 다시 돈다 |

## 책임과 경계

| 모듈 | 맡는 것 |
| --- | --- |
| `crypto` | `server_secret` 줄, KEK 로 감싸고 풀기, 캐시, 로그. 인터페이스 `ServerSecrets` 는 `crypto.domain`, 구현은 `crypto.infra`, 화면용 조회는 `crypto.application`. 하위 기능을 만들지 않는다(루트 역할 패키지가 이미 있다) |
| `browser` | 중계 서명 비밀 읽기, 만들기와 지우기, 옛 환경 변수 가져오기 |
| `notification` | VAPID 키 쌍 만들기와 검사, 기기 비우기, 해제 알림, 등록 때 공개 키 확인 |
| `hermes` | profile key 를 `ServerSecrets` 로 읽고 쓰기, 옛 key 파일 가져오기(PR2). 대시보드 포트에 credential 목록 호출 더하기(PR3) |
| `agent` | provider key 줄, Hermes 로 맞춰 넣기, 상태 읽기, 옛 `.env` 줄 지우기(PR3). `credential_scope` 를 가진 모듈이다 |
| `connector` | 브라우저 플러그인 바인딩 전체 다시 설치 |
| `admin.secret` | 화면 API 와 값마다 바꾸기 흐름 조율. 기능의 `application` 메서드만 부르고 dto 만 받는다. 기동 작업은 갖지 않는다 |
| `hermes/plugins/dashboard-profile-api` | Hermes 루트 credential 목록과 방식 설정, profile `.env` 의 provider key 지우기(PR3). Hermes core 는 고치지 않고 Hermes 함수만 부른다 |
| `web` | `/admin/secrets` 화면과 관리자 메뉴 한 줄 |

층 순서는 `crypto < hermes < browser < notification < agent < connector < admin` 이라 새 의존은 모두 아래를 향한다(`TopLevelPackageOrder`).
`infra` 인 `VapidKeys` 와 `HermesProfileKeyStore` 는 `crypto.domain` 의 인터페이스를 부르므로 층 방향(`LAYER_DIRECTION`)을 지킨다.

## 인터페이스와 데이터

**테이블 `server_secret`**(새 마이그레이션, 번호는 plan 이 예약한다)

| 칸 | 뜻 |
| --- | --- |
| `name` | 기본 키. `vapid`, `browser_gateway`, `profile_key:<profile>`, `provider:<공급자>`, `provider_key:<공급자>:<번호>`, `import:<단계>` |
| `kek_id`, `sealed_value` | 감싼 값. 값이 비었으면 둘 다 NULL 이다. 줄이 있고 값이 NULL 이면 「비어 있음, 가져오기 끝남」 이다 |
| `prefix` | 화면에 보일 앞부분. 짧은 값은 NULL |
| `meta` | 비밀이 아닌 JSON. VAPID 공개 키와 연락처, 별칭과 순서, 공급자 방식과 버전 번호, 반영 실패 |
| `updated_by`, `updated_at` | 마지막으로 바꾼 사용자 번호(기동 가져오기는 NULL)와 시각 |

`backend/docs/data-schema.md` 에 표를 더하고 「테이블을 늘리지 않는다」 예외 줄에 스펙 DECISION5 를 적는다.
`DataKeyRewrapper` 가 같은 기동에서 `server_secret` 의 옛 KEK 줄도 다시 감싼다.

**Control Plane API**(`/api/v1/admin/secrets`, `ADMIN` 만)

| 요청 | 하는 일 |
| --- | --- |
| `GET` | 값 목록(REQ2), 확인 창용 기기 수와 브라우저 바인딩 수, profile key 수, 공급자별 key 줄과 반영 실패 |
| `POST /vapid/regenerate` 본문 `subject`, `DELETE /vapid` | REQ6 |
| `POST /browser-gateway/regenerate`, `DELETE /browser-gateway` | REQ18 |
| `POST /providers/{공급자}/keys`, `PATCH .../keys/{번호}`, `DELETE .../keys/{번호}`, `PUT .../order`, `PUT .../strategy`, `POST .../sync`, `GET .../status`, `POST .../keys/{번호}/reset` | REQ12-REQ16 |

웹 푸시 등록 요청(#817 의 `/api/v1/push`)에 구독한 공개 키 칸을 더한다. #817 의 웹 화면(PR1b)이 아직 없어 이 칸을 처음부터 넣는다.

**대시보드 plugin**(PR3, 토큰은 기존 대시보드 토큰): `GET`·`PUT /api/credential-pool/<공급자>`, `POST /api/credential-pool/<공급자>/<번호>/reset`, `DELETE /api/env/provider-keys`.
`PUT` 은 버전 번호가 지금 것보다 작으면 거절한다. 응답과 로그에 key 원문을 넣지 않는다. 계약은 PR3 이 [`hermes-contract.md`](../../../hermes/docs/hermes-contract.md) 에 적는다.

## 실패와 일관성

- **쓰기와 캐시**: 커밋 뒤 새 값을 캐시에 넣고 세대 번호를 올린다. 커밋 전에 옛 줄을 읽은 스레드는 세대가 바뀌어 캐시에 넣지 못한다. 롤백되면 캐시와 세대가 그대로다.
- **VAPID 바꾸기**: 줄과 기기 비우기가 전역 등록 잠금 안의 한 트랜잭션이다. 잠금을 놓은 뒤의 등록은 공개 키 확인으로 옛 키 구독을 거절한다. 해제 알림은 커밋 뒤 따로 만들고, 실패해도 키는 바뀐 채 경고만 남긴다.
- **중계 비밀 바꾸기**: 값이 커밋되면 옛 표식은 바로 무효다. 그 뒤 브라우저 플러그인 바인딩을 하나씩 다시 설치한다. 서버 정의의 `.env` 가 바뀌어 plugin 이 재시작 필요를 답하므로 바인딩은 재시작 대기가 되고, 관리자가 기존 「반영 완료」(Hermes 재시작)를 해야 새 표식으로 돈다. 그 사이 그 플러그인 호출은 실패한다(스펙 DECISION20). 연결 반영 자동화(connector-auto-apply)가 들어오면 그 기능이 재시작 대기를 푼다. 이 설계는 기존 재시작 대기 상태만 쓰므로 두 기능 사이에 따로 맞출 계약이 없다. 하나가 실패해도 다음으로 가고, 실패한 바인딩은 기존 바인딩 화면에 실패로 보인다.
- **provider key 동기화**: Control Plane 이 원본이다. 공급자마다 JVM 잠금 안에서 최신 값을 읽어 보내므로 늦게 도착한 옛 목록이 새 목록을 덮지 않는다. plugin 도 버전 번호로 한 번 더 막는다. 같은 줄은 Hermes 상태를 지키므로 기동 때 다시 보내도 막힘 기억이 지워지지 않는다.
- **Hermes 인증 파일**: 구독 로그인 갱신도 같은 파일에 쓰므로 plugin 은 파일을 직접 고쳐 쓰지 않고 Hermes 의 인증 저장 함수와 잠금을 거친다.
- **가져오기**: 웹 서버가 요청을 받기 전에 돈다. 이름마다 줄이 있으면 건너뛰므로 여러 번 기동해도 한 번이다. 한 값이 실패하면 경고만 남기고 기동을 멈추지 않는다. 그 값을 쓰는 기능은 꺼진 채로 뜬다.
- **KEK 없음**: 저장은 거절하고, 읽기는 「비어 있음」 으로 보아 각 기능이 꺼진 채 뜬다. 옛 KEK 를 잃은 줄은 기동 때 수만 경고한다.
- **배포 순서**: #817 머지 → PR1. PR1 이 `PushProperties` 의 세 칸을 지우므로 운영의 VAPID 환경 변수는 남아도 읽히지 않는다. PR2 는 가져오기가 끝난 로그를 본 뒤 운영이 key 디렉터리를 지운다. PR3 은 배포 직후 화면에서 key 를 넣는다(스펙 DECISION24).
- **되돌리기**:
  - PR1 을 되돌리면 VAPID 가 다시 환경 변수를 찾으므로 푸시가 꺼진 채 뜬다.
  - PR1 뒤 중계 비밀을 다시 만들었으면, 되돌린 버전은 옛 환경 변수로 검사해 브라우저 플러그인이 모두 실패한다. 되돌린 뒤 그 바인딩들을 다시 맞추고 Hermes 를 재시작한다.
  - PR2 를 되돌리면 옛 key 파일이 남아 있어야 대화가 돈다. PR2 뒤에 만든 profile 은 파일이 없어 그 사용자의 대화가 멈추므로, 되돌린 뒤 그 profile 을 다시 만든다. 그래서 운영은 PR2 가 안정된 뒤에 key 디렉터리를 지운다.

## 검증 전략

| 요구사항 | 경계와 환경 |
| --- | --- |
| REQ1-REQ4, REQ8 | 관리자 API 통합 테스트(H2): 권한, 응답에 원문 없음, 줄이 암호문, KEK 없는 컨텍스트에서 409, 로그 캡처. 구조 규칙(`archTest`)이 admin 의 원문 접근을 막는다 |
| REQ5 | 단위 테스트: 쓰기와 읽기가 겹칠 때 옛 값이 캐시에 남지 않음 |
| REQ6, REQ7 | 통합 테스트: 기기가 있는 사용자 둘, 다시 만들기 뒤 기기 0 과 알림 한 건씩, 그 알림이 `PushDispatcher` 를 타지 않음, 옛 공개 키 등록은 409 |
| REQ9, REQ11, REQ19 | 가져오기 단위 테스트: 줄 없음, 줄 있음, 지운 뒤 다시 기동, 일부 profile 실패 때 표지 없음 |
| REQ18 | 통합 테스트(가짜 대시보드): `READY` 와 재시작 대기 바인딩 모두 다시 설치가 불리고 재시작 대기로 남음 |
| REQ12-REQ16 | Control Plane 은 가짜 대시보드로 요청 본문, 버전, 실패 표시를 본다. plugin 은 `hermes/tests` 에서 Hermes 기준 버전의 credential 목록 함수로 줄 순서, `source`, 같은 줄의 상태 유지, 방식, reset 을 본다 |
| REQ13, REQ14 의 실제 동작 | PR3 계약 테스트: 공유 listener(multiplex) 아래에서 profile 이 루트 목록과 어느 `config.yaml` 의 방식을 읽는지. 배포 뒤 확인(운영): 루트 목록에 넣은 key 로 함께 쓰는 profile 의 실행을 한 번 왕복한다 |
| 화면 | 브라우저 테스트: 목록, 확인 창 문구, 입력 칸 비우기, 상태 못 읽음 |

## 결정

| ID | 결정 | 답 | 누가 | 근거 |
| --- | --- | --- | --- | --- |
| DECISION1 | 값을 감싸는 층 | 값 자체를 KEK 로 바로 감싼다. 서버용 데이터 key 층을 두지 않는다 | 작성자 | 값이 짧고 수가 적다. [ADR-20261008 / data-encryption](../../../backend/docs/adr/ADR-20261008-data-encryption.md) 이 이 방식을 기각한 까닭(사용자 한 명만 지울 수 없음, KEK 교체 때 모든 본문을 다시 씀)은 서버 비밀값에 해당하지 않는다 |
| DECISION2 | 저장 자리 | `crypto.domain` 인터페이스와 `crypto.infra` 구현. 하위 기능을 만들지 않는다 | 작성자 | 하위 기능은 정해진 큰 기능만 둔다(`backend/docs/code-architecture.md` 「패키지 배치 규칙」). infra 사용처가 부를 수 있는 층이 domain 이다 |
| DECISION3 | 한 테이블에 모든 종류 | 이름 접두로 종류를 나누고 비밀 아닌 값은 `meta` JSON 에 둔다 | 작성자 | 테이블을 하나만 더한다(스펙 DECISION5). 종류마다 칸을 두면 대부분 비어 있다 |
| DECISION4 | 지운 값과 가져오기 표지 | 줄을 지우지 않고 값만 NULL 로 둔다. 쓰기는 NULL 줄을 비어 있음으로 본다 | 작성자 | 스펙 DECISION16 의 「다시 가져오지 않음」 을 칸 하나 없이 지킨다 |
| DECISION5 | 캐시 | 커밋 뒤 새 값을 넣고, 읽기는 세대 번호가 같을 때만 넣는다. 만료 시간은 두지 않는다 | 작성자 | 서버 한 대 전제. 커밋 앞 읽기가 옛 값을 남기는 경쟁을 막는다 |
| DECISION6 | provider key 를 Hermes 에 넘기는 모양 | 공급자 단위 `PUT` 에 버전 번호. 우리 줄은 `source` 로 구분하고 같은 줄은 Hermes 상태를 지킨다 | 작성자 | 구독 로그인 줄을 건드리지 않고, 다시 보내기와 기동 때 맞추기가 같은 호출이다 |
| DECISION7 | 가져오기 자리와 때 | 값의 주인 기능이 웹 서버보다 먼저 가져온다 | 작성자 | admin 은 기능의 설정과 포트를 부를 수 없다. 늦게 가져오면 첫 요청이 key 없음으로 실패한다 |
| DECISION8 | 옛 키 구독 막기 | 등록 요청에 공개 키를 넣고 다르면 409 | 작성자 | 키를 바꾼 뒤 옛 키 구독이 등록되면 403 만 받고 자동 정리도 안 돼 스펙 DECISION7 이 막으려던 상태가 생긴다. 웹 화면(PR1b)이 아직 없어 호환 부담이 없다 |

## 결정 기록 연결

| 결정 | 종류 | ADR 또는 필요 없음의 이유 | 현재 규칙의 원본 |
| --- | --- | --- | --- |
| 스펙 DECISION1, 이 설계 DECISION1 | architecture | [ADR-20261120 / server-secret](../../adr/ADR-20261120-server-secret.md) | `AGENTS.md` 「지켜야 할 것」, `docs/code-architecture.md` 「비밀값을 두는 곳」 |
| 이 설계 DECISION6 | architecture | PR3 이 `hermes/docs/adr/` 에 쓴다. Hermes 목록의 원문 사본을 받아들인 까닭을 남긴다 | `hermes/docs/hermes-contract.md` |
| 이 설계 DECISION2-DECISION5, DECISION7, DECISION8 | 필요 없음 | 기존 모듈 경계와 관례를 따르는 내부 선택이다 | 이 설계 |
