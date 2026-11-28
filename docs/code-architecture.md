# 구조

## 경계

```
브라우저
  └ Next.js (web/)            세션은 여기까지만 산다
      └ 서버 라우트에서 짧은 수명의 토큰을 발급해 호출
          └ Spring Boot (backend/)   Control Plane
              └ Hermes API server    /p/<profile>/v1/runs
```

브라우저는 Control Plane 토큰을 갖지 않는다.
Next.js 서버 라우트가 세션에서 메일 주소를 꺼내 매 요청마다 토큰을 새로 만든다.
그래서 사용자가 요청 본문을 고쳐 남의 자료를 달라고 할 수 없다.

**위 그림은 요청이 나가는 쪽만 그린 것이다.**
Hermes 가 Control Plane 을 부르는 반대 방향도 있고 토큰이 서로 다르다.
그 두 방향은 [`docs/features/mcp.md`](features/mcp.md) 의 「두 방향의 인증」 절이 그림으로 갖는다.

## 사용자와 profile, 에이전트, 대화

층이 넷이고 서로 다르다.

```
사용자 (app_user)
 └ 그 사용자의 profile 들
     └ 각 profile 을 가리키는 에이전트 (agent 표)
         └ 그 에이전트로 시작한 대화 (conversation)
```

**한 사용자가 profile 을 여럿 가질 수 있다.**
기본 profile 하나에 역할 profile 을 더한다.
둘은 만드는 방법이 달라 설정도 다르다.
`fos-home-infra` 가 그 차이를 소유한다.

## 사용자별 실행 경계

사용자 요청이 Hermes 를 거쳐 실행 공간, 플러그인, 「내 브라우저」 에 닿을 때 구간마다 누가 인증하고 무엇이 막는지를 적는다.
**profile 을 나눈 것은 Hermes 안의 논리 경계다. OS 권한 경계가 아니다.** 공유 gateway 는 모든 profile 을 한 프로세스, 한 OS 계정으로 돌린다([`hermes-contract.md`](../hermes/docs/hermes-contract.md) 의 「공유 listener 가 바꾸는 경계」).
그래서 아래 표는 OS 권한과 서버의 권한 판정을 따로 적는다.

확인 수준은 셋이다.

- **소스·구성 확인**: 소스나 측정 기록으로 판정했다. 근거 절을 함께 적는다.
- **합성 검사**: 합성 사용자 둘 이상으로 자기 자원은 쓰고 남의 자원은 거절되는 것을 테스트가 매번 확인한다.
- **운영 확인 필요**: 운영 저장소의 설정(Docker proxy 정책, 망, 방화벽)에 기댄다. 별도 승인을 받아 운영에서 확인한다.

| 구간 | 인증 주체 | 프로세스와 OS 권한 | 닿는 파일 범위 | 쓰는 토큰의 주인 | 강제 경계 | 확인 수준과 검사 |
| --- | --- | --- | --- | --- | --- | --- |
| 웹 → Control Plane | 사용자. 웹 서버 라우트가 세션에서 요청마다 짧은 수명 JWT 를 만든다 | 공용 서비스 | 데이터베이스와 사용자별 저장소. 사용자 본문은 사용자 key 로 암호화한다 | 사용자 | Control Plane 이 JWT 의 사용자로 대화, 첨부, 작업 공간, 브라우저를 고른다. 남의 것은 없는 것과 같은 404 다 | 합성 검사: `ConversationPublicIdTest`, `AttachmentServiceTest`, `WorkspaceControllerTest`, `WorkspaceServiceTest`, `UserBrowserScreenControllerTest`, `ChatAttachmentTurnTest` |
| Control Plane → Hermes 실행 | profile. 그 profile 의 `API_SERVER_KEY` | Hermes gateway 한 프로세스. 모든 profile 이 같은 OS 계정이다 | Hermes 홈 전체. profile 경계는 Hermes 의 secret scope 와 설정 scope 가 정한다 | profile key | 요청자가 쓸 수 있는 에이전트에서만 profile 을 꺼낸다. Hermes 는 남의 접두, 남의 key, 남의 실행 번호를 404 나 401 로 거절한다 | 합성 검사: `AgentToolServiceAccessTest`, `AgentConnectionControllerTest`. Hermes 쪽은 소스·구성 확인([「profile 접두」](../hermes/docs/hermes-contract.md#profile-접두)) |
| Hermes → AI 모델 | **의도적으로 공유한다.** `SHARED_HOUSEHOLD` 에이전트는 그룹이 함께 쓰는 AI 계정이다. `DEDICATED` 만 그 profile 의 계정이다 | Hermes gateway | 해당 없음 | 그룹의 AI 계정이나 그 profile 의 계정 | 경계를 두지 않기로 했다([ADR-002](../backend/docs/adr/ADR-002-profile은-나누고-ai-계정은-가족이-함께-쓴다.md)). 사용량은 Control Plane 실행 기록이 사용자별로 센다. 개인 연동 권한과 다른 줄이다 | 소스·구성 확인([「OAuth credential 은 여기서 빠진다」](../hermes/docs/hermes-contract.md#oauth-credential-은-여기서-빠진다)) |
| Hermes → Control Plane MCP | profile 의 `agent_token` 과 루트 session 서명. 사용자는 실행 기록에서 정한다 | Hermes gateway | 해당 없음 | profile 토큰 | `McpCallerResolver` 가 origin 실행의 사용자로만 기억, 결과물, 파일 공간, 위임을 연다([MCP 요청자](features/mcp.md#mcp-요청자)) | 합성 검사: `McpPrincipalTest`, `McpAgentToolsTest`, `McpArtifactWriteToolTest`, `McpFileSpacePutToolTest`, `RunTraceEndpointTest` |
| 셸과 파일 도구(실행 공간) | 실행 공간 정책에 등록한 profile 과 그 주인 | 사용자나 profile 마다 docker 컨테이너. 컨테이너 안은 root 이고 capability 를 줄였다 | 그 주인의 `/workspace`, 그 주인의 첨부(읽기 전용), 그 profile 의 스킬과 플러그인 출력(읽기 전용) | 없음. 컨테이너에 비밀 env 를 넣지 않는다 | 대시보드 plugin 의 정책이 마운트를 정하고, 컨테이너 생성 본문을 검사하는 Docker proxy 가 bind 와 권한을 막는다. 등록하지 않은 profile 은 셸을 켜지 못한다 | 합성 검사: `test_dashboard_profile_api_sandbox_attachments.py`, `test_dashboard_profile_api_sandbox_terminal.py`, `test_dashboard_profile_api_sandbox_policy.py`. 측정: [「측정 결과」](../hermes/docs/hermes-contract.md#측정-결과). 운영 확인 필요: Docker proxy 정책, 전용 망, 같은 LAN 으로 나가는 길의 방화벽 |
| 로컬 플러그인 MCP 프로세스 | 연결의 주인. 실행기가 그 연결의 보관 파일을 풀어 그 프로세스 env 에만 넣는다 | **Hermes 의 자식 프로세스. Hermes 와 모든 사용자의 플러그인이 같은 OS 계정이다.** 실행 공간 밖이다 | **Hermes 컨테이너의 파일 전체** | 연결 주인의 자격증명 | 호출마다 fos-ctx 가 Control Plane 에 묻고, 연결 주인과 실행의 사용자가 다르면 막는다. **OS 권한 경계는 없다**(아래 「경계 없음」 1) | 합성 검사: `ConnectorPolicyEndpointTest`, `ConnectorGuardSupportTest`, `ConnectorActionExecutionTest`, `test_connector_launcher.py`, `test_fos_ctx.py` |
| 원격 플러그인 | 연결의 주인. 붙인 에이전트 profile 의 `.env` 에 값을 복사한다 | Hermes gateway 가 직접 연결한다 | 해당 없음 | 연결 주인의 자격증명 | 위와 같은 호출 판정. **사본이 profile `.env` 에 있어 같은 OS 계정의 프로세스가 읽는다**(아래 「경계 없음」 1) | 소스·구성 확인([「플러그인 비밀값을 두는 곳」](#플러그인-비밀값을-두는-곳)) |
| 「내 브라우저」 중계 | 접근 표식. 바인딩 표식은 그 연결의 주인, 호출 표식은 그 사용자다. HMAC 서명이고 호출 표식은 5분 뒤 만료된다 | 사용자마다 브라우저 컨테이너 하나. 프로필 디렉터리도 사용자마다 따로다 | 그 사용자의 브라우저 프로필 | 표식의 주인 | Control Plane 중계가 서명, 바인딩, 만료, 허용 목록, 사람 잠금을 본 뒤 그 주인의 브라우저에만 넘긴다. 브라우저 망에서 브라우저에 닿는 것은 Control Plane 하나다 | 합성 검사: `BrowserGatewayTest`(합성 사용자 둘), `BrowserGatewayTokensTest`, `UserBrowserScreenControllerTest`. 운영 확인 필요: 브라우저 망에 Hermes 와 실행 공간이 닿지 않는 것 |
| Hermes 내장 브라우저(`browser_*`) | 해당 없음 | Hermes 컨테이너 | Hermes 컨테이너 | 해당 없음 | **기능을 보류했다.** profile 틀이 toolset 을 끈다 | 소스·구성 확인([「같은 프로세스에 남는 구멍」](../hermes/docs/hermes-contract.md#같은-프로세스에-남는-구멍)) |

### 경계 없음

강제 경계가 없는 실행 경로다. 고칠지, 그 기능을 보류할지는 따로 정한다.

1. **로컬 플러그인 MCP 프로세스는 다른 사용자의 비밀과 같은 OS 계정에서 돈다.**
   위치는 Hermes 가 띄우는 stdio MCP 서버와 대시보드 plugin 의 보관 파일이다.
   같은 계정이라 파일 권한이 막지 않는 것은 보관 key, 다른 사용자의 보관 파일과 OAuth 파일, 다른 profile 의 `.env`(원격 플러그인 사본 포함), 다른 바인딩의 서버 정의다. 서버 정의에는 만료 없는 바인딩 표식이 든 중계 주소가 있다.
   영향은 플러그인 하나의 코드나 의존이 뚫리면 모든 사용자의 플러그인 자격증명과 「내 브라우저」 로그인에 닿는다는 것이다.
   지금 막는 것은 운영 목록에 든 검토한 플러그인만 설치한다는 것뿐이다([ADR-20261103 / connector-vault-key](../hermes/docs/adr/ADR-20261103-connector-vault-key.md) 의 「결과」).
   호출 판정(연결 주인과 실행 사용자 비교)은 모델이 부르는 도구만 막고 프로세스 자체를 막지 않는다.
2. **Hermes gateway 의 profile 경계는 한 프로세스 안의 scope 다.**
   모든 profile 의 설정과 `.env` 를 한 OS 계정이 읽는다. Hermes core 의 scope 처리에 결함이 있으면 다른 profile 의 값이 섞일 수 있고 OS 가 막지 않는다.
   공용 Control Plane 과 이미지 재사용은 이 문제와 별개다. 이 줄은 사용자별 실행 권한만 말한다.

## 모델 판단을 구조로 정하는 범위

판단의 수준, 축, 종류 같은 분류를 테이블 컬럼이나 enum으로 고정하지 않는다.
모델이 좋아지면 판단이 더 세분화될 수 있어, 분류를 고정하면 모델을 바꿀 때마다 스키마와 코드를 함께 고쳐야 한다.
Control Plane은 안전 판정에 쓰는 값만 구조로 정하고, 나머지는 모델 출력의 자유 칸이나 JSON으로 받는다.
예를 들어 자율성 정책의 네 수준을 enum으로 굳혀 모델의 판단 전체를 그중 하나에 맞추는 설계는 이 규칙의 반례다.
판단 개선은 판단 단계를 늘리는 대신 실행 성공률, 사용자 수정률 같은 결과 지표로 평가한다.

## 화면을 검증하는 방법

테스트는 확인하는 대상을 나눠 둔다.

| 위치 | 확인하는 것 | 띄우는 것 |
| --- | --- | --- |
| `web/test/unit/` | 화면이 쓰는 순수 함수와 소스 파일의 구조 | 없음 |
| `web/test/node/` | 웹 API 라우트와 화면 함수. 웹의 `node_modules` 를 쓴다 | 없음 |
| `web/test/browser/` | 화면의 배치와 동작 | 웹, Control Plane, 가짜 Hermes, Chromium |
| `web/test/screenshots/` | 화면 둘러보기 스크린샷 | 브라우저 테스트와 같다 |
| `test/e2e/` | Control Plane 의 응답과 권한과 기록 | 가짜 Hermes, 백엔드, 데이터베이스. 웹은 띄우지 않는다 |
| `test/repo/` | 문서 링크와 파일 배치, 마이그레이션 불변과 버전, ADR 과 코드 규칙 목록처럼 여러 모듈에 걸친 저장소 규칙 | 없음 |
| `test/model-eval/` | 실제 모델이 운영 흐름에서 지시와 도구를 따르는가. CI 에서 돌지 않는다 | 운영 Control Plane, Hermes, 모델. 판정 규칙은 그 폴더의 `*.test.ts` 가 따로 검사한다 |
| `scripts/**/*.test.ts` | 그 스크립트의 동작. 스크립트 옆에 둔다 | 없음 |

`test/support/`, `test/fixtures/`, `test/resources/` 는 여러 모듈이 함께 쓰는 부품과 자료다.
부품의 단위 테스트는 부품 옆(`test/support/*.test.ts`, `test/e2e/unit/`)에 둔다.
어디에 둘지는 [ADR-20261110 / test-layout](adr/ADR-20261110-test-layout.md) 이 정한다.

브라우저 테스트는 `mobile` 과 `desktop` 두 폭에서 돈다. 폭의 값은 `web/test/browser/playwright.config.ts` 가 갖는다.

**운영 코드에 시험용 문을 만들지 않는다.**
로그인은 테스트가 NextAuth 세션 쿠키를 직접 만들어 넣는다.
테스트일 때만 켜지는 우회를 두면 그 문이 운영에도 남는다.

## 비밀값을 두는 곳

| 값 | 두는 곳 |
| --- | --- |
| 사용자의 AI credential | Hermes 쪽. provider API key 는 profile `.env` 에 둔다. OAuth 로그인은 profile 에 자기 `auth.json` 이 없으면 여러 profile 이 Hermes 루트의 것을 함께 쓴다([`hermes/README.md`](../hermes/docs/hermes-contract.md)) |
| profile 의 API server key | 홈서버의 mode 600 파일. 파일 이름이 profile 이름이다 |
| Hermes 대시보드를 부를 토큰 | Control Plane 의 환경 변수와 그 plugin 의 환경 변수 |
| 운영 정기 작업이 재시작 조회를 부를 토큰 | Control Plane 의 환경 변수(`CONNECTOR_OPS_TOKEN`)와 운영 정기 작업 쪽([ADR-20261120 / connector-auto-apply](adr/ADR-20261120-connector-auto-apply.md)) |
| 웹과 Control Plane 이 나눠 가지는 HMAC 비밀값 | 두 서비스의 환경 변수 |
| 사용자 본문을 감싸는 KEK | 홈서버 파일. 데이터베이스 백업과 다른 자리에 둔다([ADR-20261008 / data-encryption](../backend/docs/adr/ADR-20261008-data-encryption.md)) |
| 사용자별·그룹별 데이터 key | `user_data_key` 에 KEK 로 감싼 채로 둔다. 그룹 key 는 그룹 기억을 암호화한다([ADR-20261103 / memory-encryption](../backend/docs/adr/ADR-20261103-memory-encryption.md)). 푼 key 는 Control Plane 메모리에만 있다 |
| 웹 푸시 VAPID 키와 연락처 | 서버 비밀값 `server_secret` 의 `vapid`. 비밀 키를 KEK 로 감싼 채로 두고 관리자 화면 「서버 비밀값」 에서 서버가 새로 만든다. 바꾸면 등록 기기를 모두 비운다 |
| 브라우저 중계 서명 비밀 | 서버 비밀값 `server_secret` 의 `browser_gateway`. KEK 로 감싼 채로 두고 관리자 화면 「서버 비밀값」 에서 서버가 새로 만든다 |
| 플러그인 OAuth 앱 client secret | 서버 비밀값 `server_secret` 의 `oauth_app:` 줄. 관리자가 넣는다. 줄 이름은 [플러그인 OAuth 연결 설계](features/connector-oauth/design.md) 의 「서버 비밀값 줄」 이 갖는다 |
| 단추 연결 서명 비밀 | 서버 비밀값 `server_secret` 의 `connector_oauth_signing`. 처음 쓸 때 Control Plane 이 만든다(같은 「서버 비밀값 줄」) |

데이터베이스에는 어떤 비밀값도 원문으로 넣지 않는다.
서버 비밀값은 `server_secret` 에 KEK 로 감싸 두고 관리자 화면에서 넣고 바꾼다. 옮길 값과 남길 값, 옮기는 순서는 [ADR-20261120 / server-secret](adr/ADR-20261120-server-secret.md) 과 [서버 비밀값 스펙](features/secret-store/spec.md) 이 갖는다.
`agent` 는 profile 이름과 주소만 적는다.
profile key 와 AI credential 은 아직 홈서버 파일과 Hermes 쪽에 있다. 옮기는 일은 서버 비밀값 스펙의 PR2, PR3 이 한다.

## 플러그인 비밀값을 두는 곳

`connector_connection` 은 사용자, 플러그인, 상태, 칸 값, 마지막 확인 시각, 값을 보관 파일에 두었는지, 선언하지 않은 도구 수를 저장한다.
에이전트에 붙인 것은 `agent_connector_binding` 이 바인딩마다 상태와 재시작 대기를 저장한다([`backend/docs/data-schema.md`](../backend/docs/data-schema.md)).
비밀 칸은 원문과 해시를 저장하지 않고, 값이 충분히 길 때만 앞부분을 남긴다. 길이 기준과 저장 칸은 [`backend/docs/data-schema.md`](../backend/docs/data-schema.md) 의 「connector_connection」 이 갖는다.
화면은 연결된 상태에서 앞부분이 없는 필수 비밀 칸을 「입력됨」 으로만 보인다. 앞부분이 없는 선택 비밀 칸은 입력 여부를 응답으로 알 수 없어 보이지 않는다.
브라우저는 등록을 제출한 직후 비밀 칸 입력을 비우고 다시 표시하지 않는다. 선택지를 고르는 동안은 작성 중인 입력을 쓰고, 조회가 실패해도 입력을 비운다.
요청 record 의 문자열 표현, 외부 오류, 로그와 응답에 비밀 원문을 남기지 않는다.
`connector_action` 은 도구 호출마다의 판정과 승인 줄을 저장한다. 인자 원문은 승인 줄에만 두고 주인에게만 보인다. 관리자 목록과 로그에는 싣지 않는다.

**칸 값의 원문은 Hermes 쪽 보관 파일에만 있다.** 예외는 원격 플러그인과, 옮기지 못한 옛 바인딩이다.

| 어디 | 무엇 | 언제 지워지는가 |
| --- | --- | --- |
| 보관 파일 | 연결마다 하나인 원본. 대시보드 plugin 이 Control Plane KEK 와 다른 key 로 암호화해 둔다([ADR-20261103 / connector-vault-key](../hermes/docs/adr/ADR-20261103-connector-vault-key.md)) | 연결을 해제할 때 |
| 로컬 플러그인 MCP 프로세스의 env | 실행기가 띄울 때 보관 파일을 풀어 그 프로세스 env 에만 넣은 값. 서버 정의와 `.env` 에는 없다([보관 파일 암호화 설계](../hermes/plugins/dashboard-profile-api/README.md) 의 「PR2: 실행기와 사본 제거(REQ6, REQ7, REQ8)」) | 프로세스가 끝날 때 |
| 원격 플러그인을 붙인 에이전트 profile 의 `.env` | 붙일 때 보관 파일에서 복사한 값. 서버 정의의 헤더는 `${이름}` 참조만 갖고 그 profile 의 secret scope 에서 풀린다. Hermes 가 프로세스 없이 직접 붙어 실행기를 끼울 자리가 없어 예외로 둔다 | 그 에이전트에서 뗄 때. 연결 해제는 붙은 바인딩을 모두 떼므로 함께 지워진다 |
| 옮기지 못한 옛 로컬 바인딩의 `.env` | 배포 때 보관 파일을 풀지 못해 옛 방식으로 남긴 사본. 기동 로그의 「남은 사본」 수로 드러난다 | 다시 연결하거나 뗄 때 |
| OAuth 파일 | 단추로 연결한 연결의 refresh token 과 토큰 통로 주소. 보관 파일 옆에 같은 key 로 암호화해 둔다. 실행기가 통로 주소만 그 프로세스 env 에 넣는다([ADR-20261120 / connector-oauth](adr/ADR-20261120-connector-oauth.md), [플러그인 OAuth 연결 설계](features/connector-oauth/design.md)) | 연결을 해제하거나 붙여 넣기로 바꿀 때 |
| access token | 플러그인이 도구를 부를 때 토큰 통로에서 받는 값. Control Plane 메모리와 통로 응답에만 있다 | 만료되거나 연결을 해제할 때 |

값을 바꾸면 보관 파일을 다시 쓰고 붙은 바인딩을 다시 설치한다. 로컬 바인딩은 정의의 보관 파일 리비전이, 원격 바인딩은 `.env` 사본이 바뀐다. 떠 있는 MCP 프로세스는 옛 값을 쥐고 있어 그 서버만 껐다 켜서 반영된다([ADR-20261120 / connector-auto-apply](adr/ADR-20261120-connector-auto-apply.md)).
설정 백업은 `.env` 와 보관 파일을 담지 않는다. 그래서 떼거나 해제한 뒤에는 그 profile 디렉터리의 어느 파일에도 칸 값의 원문이 남지 않는다.
