## ADR-20261120 / connector-oauth: 플러그인 OAuth 는 대시보드 plugin 이 아는 제공자만 쓰고, 만료 토큰은 Control Plane 의 토큰 통로로 받는다

- **status**: `accepted`
- Date: 2026-11-20
- **decision-type**: `architecture`
- **areas**: `security`
- **현재 규칙의 원본**: [플러그인 계정 연결 단추 설계](../features/connector-oauth/design.md), [`hermes/connectors/README.md`](../../hermes/connectors/README.md) 의 「assistant.json」 과 「비밀값과 권한」
- [ADR-043](../../backend/docs/adr/ADR-043-커넥터는-plugin-의-connector-json-으로-선언하고-control-plane-은-범용-흐름만-갖는다.md) 의 「서비스에 관한 것은 플러그인 선언만 갖는다」 를 OAuth 제공자에 한해 바꾼다. Control Plane 이 서비스 주소를 모른다는 것은 그대로다.

### 결정

**플러그인은 `assistant.json` 에 OAuth 제공자의 이름만 적는다. 로그인·토큰·취소 주소와 응답 모양은 이 저장소의 대시보드 plugin 이 갖는 제공자 표(지금은 GitHub 과 Google)가 정한다.**
**교환, 갱신, 취소는 보관 파일 옆의 대시보드 plugin 이 하고 refresh token 은 보관 파일 밖으로 나가지 않는다. 플러그인 프로세스는 Control Plane 의 토큰 통로에서 지금 유효한 access token 을 받는다.**

| 항목 | 정한 것 |
| --- | --- |
| 제공자 | 대시보드 plugin 의 표에 있는 이름만 받는다. 모르는 이름은 그 플러그인의 단추만 뺀다 |
| client secret | Control Plane 의 서버 비밀값에 둔다. 교환·갱신·취소 요청마다 대시보드 plugin 에 보내고 대시보드는 저장하지 않는다. 플러그인 프로세스에는 가지 않는다 |
| refresh token | 보관 파일에만 있다. GitHub 처럼 갱신마다 바뀌면 대시보드가 답하기 전에 보관 파일에 쓴다 |
| access token | Control Plane 메모리와 토큰 통로 응답에만 있다. DB 와 보관 파일에 두지 않는다 |
| 토큰 통로 | Control Plane 경로. 바인딩마다 고정인 HMAC 표식으로 그 바인딩의 연결 토큰만 준다. 표식이 바뀌지 않아 갱신이 바인딩 재시작을 만들지 않는다 |

### 맥락

연결의 비밀 칸을 사람이 붙여 넣는 대신 「GitHub 으로 연결」 단추로 받기로 했다(사용자 2026-11-20). GitHub App 토큰은 8시간, Google 토큰은 1시간이면 만료다.
칸 값은 플러그인 프로세스가 뜰 때 env 로 한 번 들어가고, 값을 바꾸면 바인딩이 재시작 대기가 된다([connector.md](../features/connector.md) 「보관 파일」). 만료 토큰을 이 길로 주면 몇 시간마다 끊기거나 재시작해야 한다.
갱신에는 OAuth 앱의 client secret 이 필요하다. 운영 비밀을 플러그인 프로세스에 넣는 길은 [ADR-046](../../backend/docs/adr/ADR-046-운영-비밀은-operator-env-와-다른-칸으로-선언하고-자식-mcp-프로세스에만-넣는다.md) 이 막아 두었다.
토큰 주소로 client secret 이 간다. 그 주소를 플러그인 선언이 정하면 선언을 쓴 쪽이 비밀이 갈 곳을 정한다. Google 앱은 여러 플러그인이 나눠 쓰기로 했다(사용자 2026-11-20).
플러그인 프로세스에서 Control Plane 에 닿는 길은 브라우저 중계가 이미 쓴다([ADR-20261008 / browser-gateway-token](../../backend/docs/adr/ADR-20261008-browser-gateway-token.md)). 대시보드 plugin 에는 플러그인 프로세스가 부르는 길이 없다.

### 대안 기각

- **플러그인이 직접 갱신한다(지금 Gmail 방식)**: client secret 이 모든 플러그인 프로세스에 들어간다. GitHub 은 바뀐 refresh token 을 저장할 길이 없어 재시작하면 끊긴다.
- **플러그인 선언이 제공자 주소를 적는다**: ADR-043 을 그대로 지키지만, 나눠 쓰는 Google 앱의 secret 을 한 플러그인이 다른 곳으로 보낼 수 있다.
- **관리자가 주소를 서버 비밀값 화면에 넣는다**: GitHub 취소처럼 표준과 다른 방식은 여전히 서비스별 코드가 필요하고, 관리자가 주소를 알아야 한다.
- **Control Plane 이 교환과 갱신을 한다**: 제공자 표가 Java 와 Python 두 곳에 생기거나, 갱신 때마다 refresh token 을 보관 파일에서 Control Plane 으로 읽어 와야 한다.
- **대시보드 plugin 이 토큰 통로를 연다**: 플러그인 프로세스에서 대시보드로 가는 인증된 길을 새로 만들어야 하고, 운영 네트워크에서 닿는지 확인되지 않았다.
- **access token 을 보관 파일에 두고 실행기가 env 로 넣는다**: 토큰이 바뀔 때마다 서버 정의가 바뀌어 재시작 대기가 된다.

### 결과

- 얻는 것: 단추로 연결한 플러그인이 재시작 없이 만료 토큰을 쓴다. client secret 과 refresh token 이 플러그인 프로세스와 Control Plane DB 에 없다. 새 제공자는 이 저장소 PR 로 표에 더한다.
- 감당할 것: 저장소 밖 플러그인은 토큰 통로를 부르는 코드를 더해야 단추를 쓴다. 이 저장소가 모르는 서비스는 단추를 쓸 수 없고 붙여 넣기만 된다. access token 이 Control Plane 메모리를 지나므로 Control Plane 로그와 오류에 토큰이 남지 않는지 검사해야 한다. 토큰 통로는 커넥터 코드 자체를 막는 경계가 아니다. 그 경계는 이 저장소가 검토한 커넥터 코드다(browser-gateway-token 과 같음).
- **확인 방법**: 대시보드 plugin 의 manifest 검사 테스트, Control Plane 토큰 통로 테스트(갱신이 서버 정의를 바꾸지 않음), 로그와 DB 에 토큰 원문이 없는지 보는 검사.
