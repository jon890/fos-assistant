# 플러그인 OAuth 연결 Hermes 쪽 인수 검증 계획

이 PR 은 Hermes 대시보드 plugin 쪽만 바꾼다. REQ4, REQ5, REQ7, REQ8, REQ9 는 Hermes 쪽 부분(파일, 교환, 오류 분류, 취소)만 확인한다. 화면 단추, Control Plane 의 상태와 알림, 실제 GitHub 왕복은 뒤 계획의 인수 검증이 맡는다.

## 인수 검증 계획

| 요구사항 | 준비 조건 | 실행 절차 | 기대 결과 | 실행 환경 | 실행 담당 | 선행 조건 | 시점 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| REQ1 | 맞는 GitHub 선언, 모르는 제공자 선언, `oauth` 없는 플러그인 | `hermes/tests/test_connector_oauth_manifest.py` 를 돌리고 카탈로그 응답을 본다 | 맞는 선언은 `oauth` 가 정규화돼 나오고, 틀린 선언은 플러그인이 남고 `oauth` 만 없으며 경고 로그가 있다 | 로컬 unittest(가짜 Hermes 대시보드) | worker | 없음 | PR 전 |
| REQ6 | key 를 준 보관 파일과 OAuth 파일, 가짜 GitHub 서버 | `hermes/tests/test_connector_oauth_routes.py` 의 갱신 사례를 돌린다 | 바뀐 refresh token 이 OAuth 파일에 있고 응답과 로그에 refresh token 이 없다. 쓰기 실패면 access token 없이 `unavailable` | 로컬 unittest | worker | 없음 | PR 전 |
| REQ6 | OAuth 선언 테스트 플러그인을 붙인 바인딩 | `hermes/tests/test_connector_oauth_launch.py` 를 돌린다 | 실행기 자식 env 와 직접 실행 env 에 통로 주소가 있고, 정의와 `config.yaml` 에 없으며, refresh token 을 바꾼 뒤 다시 설치해도 `restart_required` 가 아니다 | 로컬 unittest(실제 자식 프로세스) | worker | 없음 | PR 전 |
| REQ6 | 같은 보관 파일의 갱신과 다른 profile 의 보관 파일 쓰기 | `hermes/tests/test_connector_oauth_routes.py` 의 잠금 사례 | 가짜 서비스가 멈춘 동안 다른 profile 쓰기가 끝난다 | 로컬 unittest | worker | 없음 | PR 전 |
| REQ4, REQ6, REQ9 | 가짜 GitHub 서버, OAuth 선언 테스트 플러그인, key 를 준 보관 파일 | `hermes/tests/test_connector_oauth_flow.py`: 대시보드 경로를 HTTP 로 authorize-url → begin → exchange → call(pending) → commit → 바인딩 설치 → 실행기 자식 env → 붙여 넣기 `PUT` → 단추로 다시 연결 → `DELETE` 까지 돈다 | 확인 도구는 대기 주소, 실행기 자식은 본 주소를 받는다. 붙여 넣기 `PUT` 뒤 OAuth 파일이 없고, `DELETE` 뒤 보관 파일·OAuth 파일·대기 파일이 모두 없다 | 로컬 unittest(실제 자식 프로세스, 가짜 서비스) | worker | 없음 | PR 전 |
| REQ5, REQ7 | 가짜 GitHub·Google 의 오류 응답 | `hermes/tests/test_connector_oauth_routes.py` 의 exchange·refresh 오류 사례 | Google 부분 scope 는 `scope_denied`, refresh token 없는 교환은 `app_rejected`, `invalid_grant`·`bad_refresh_token` 은 `grant_revoked`, `invalid_client` 는 `app_rejected`, 서버 중단은 `unavailable` | 로컬 unittest | worker | 없음 | PR 전 |
| REQ8 | 가짜 GitHub·Google | `hermes/tests/test_connector_oauth_routes.py` 의 revoke·discard 사례 | GitHub 해제는 허락 전체를, discard 는 토큰 하나를 취소한다. Google discard 는 서비스를 부르지 않는다 | 로컬 unittest | worker | 없음 | PR 전 |
| REQ6 | 같은 보관 파일의 `PUT` 과 바인딩 설치 | `hermes/tests/test_connector_oauth_store.py` 의 동시 요청 사례 | 둘 다 끝난다(교착 없음) | 로컬 unittest | worker | 없음 | PR 전 |
| REQ1, REQ6 | 이 PR 의 변경 전체 | `python3 -m unittest discover -s hermes/tests` 와 `scripts/dev/check-local.sh --fast` | 모두 통과하고 기존 연결(붙여 넣기)의 실행기·직접 실행 테스트가 그대로 통과한다 | 로컬 | worker | 없음 | PR 전 |
