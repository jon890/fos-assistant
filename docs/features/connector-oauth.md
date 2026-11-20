# 플러그인 계정 연결 단추

사용자가 「외부 서비스 연결」 화면에서 「GitHub 으로 연결」 같은 단추를 눌러 그 서비스에 로그인하면, 토큰을 붙여 넣지 않아도 연결이 만들어지는 기능이다.

covers: `hermes/plugins/dashboard-profile-api/connector_oauth*.py`

요구와 결정은 스펙 [`connector-oauth/spec.md`](connector-oauth/spec.md) 가 갖는다. 지금은 Hermes 대시보드 plugin 쪽(선언 검사, OAuth 파일, 교환·갱신·취소 경로, 실행기의 토큰 통로 env)만 있고, Control Plane 과 화면은 아직 없다.
지금의 연결 흐름은 [플러그인 연결](connector.md), 칸 값을 두는 곳은 [`code-architecture.md`](../code-architecture.md) 의 「플러그인 비밀값을 두는 곳」 이 갖는다.
