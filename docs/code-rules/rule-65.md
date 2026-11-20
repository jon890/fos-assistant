# rule-65

plugin은 서비스 이름을 모르고 connector만 서비스별 동작을 갖는다.

검사: [서비스 중립](../../test/repo/connector-neutral.test.ts)

## 까닭

범용 plugin이 특정 서비스의 이름과 동작에 묶이지 않게 한다.

## 예외와 검토 범위

서비스별 이름은 connectors와 그 검사·문서에만 둔다.
웹 푸시의 허용 호스트 목록(`PushProperties`)은 예외다. 플러그인이 아니라 브라우저가 정한 푸시 서비스이고, 목록이 있어야 서버가 사용자가 준 주소로 내부망 요청을 보내지 않는다([ADR-20261120 / web-push](../adr/ADR-20261120-web-push.md)).
단추 연결의 OAuth 제공자 표(`connector_oauth_providers.py`)도 예외다. 플러그인 선언이 로그인과 토큰 주소를 정하면 client secret 을 아무 주소로나 보낼 수 있어, 아는 제공자(GitHub, Google)의 주소만 대시보드 plugin 이 갖는다([ADR-20261120 / connector-oauth](../adr/ADR-20261120-connector-oauth.md)).

## 이 저장소의 사례

[connector-neutral.test.ts](../../test/repo/connector-neutral.test.ts)에서 확인한다.
connector-neutral 테스트는 범용 plugin에 서비스 이름이 들어가지 않는지 확인한다.
