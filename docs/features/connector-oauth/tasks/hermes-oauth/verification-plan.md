# 플러그인 OAuth 연결 Hermes 쪽 인수 검증 계획

이 PR 은 Hermes 대시보드 plugin 쪽만 바꾼다. 화면 단추와 실제 GitHub 왕복은 뒤 계획(Control Plane, 웹)의 인수 검증이 맡는다.

## 인수 검증 계획

| 요구사항 | 준비 조건 | 실행 절차 | 기대 결과 | 실행 환경 | 실행 담당 | 선행 조건 | 시점 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| REQ1 | 맞는 GitHub 선언, 모르는 제공자 선언, `oauth` 없는 플러그인 | `hermes/tests/test_connector_oauth_manifest.py` 를 돌리고 카탈로그 응답을 본다 | 맞는 선언은 `oauth` 가 정규화돼 나오고, 틀린 선언은 플러그인이 남고 `oauth` 만 없으며 경고 로그가 있다 | 로컬 unittest(가짜 Hermes 대시보드) | worker | 없음 | PR 전 |
| REQ6 | key 를 준 보관 파일과 OAuth 파일, 가짜 GitHub 서버 | `hermes/tests/test_connector_oauth_routes.py` 의 갱신 사례를 돌린다 | 바뀐 refresh token 이 OAuth 파일에 있고 응답과 로그에 refresh token 이 없다. 쓰기 실패면 access token 없이 `unavailable` | 로컬 unittest | worker | 없음 | PR 전 |
| REQ6 | OAuth 선언 테스트 플러그인을 붙인 바인딩 | `hermes/tests/test_connector_oauth_launch.py` 를 돌린다 | 실행기 자식 env 와 직접 실행 env 에 통로 주소가 있고, 정의와 `config.yaml` 에 없으며, refresh token 을 바꾼 뒤 다시 설치해도 `restart_required` 가 아니다 | 로컬 unittest(실제 자식 프로세스) | worker | 없음 | PR 전 |
| REQ6 | 같은 보관 파일의 갱신과 다른 profile 의 보관 파일 쓰기 | `hermes/tests/test_connector_oauth_routes.py` 의 잠금 사례 | 가짜 서비스가 멈춘 동안 다른 profile 쓰기가 끝난다 | 로컬 unittest | worker | 없음 | PR 전 |
| REQ1, REQ6 | 이 PR 의 변경 전체 | `python3 -m unittest discover -s hermes/tests` 와 `scripts/dev/check-local.sh --fast` | 모두 통과하고 기존 연결(붙여 넣기)의 실행기·직접 실행 테스트가 그대로 통과한다 | 로컬 | worker | 없음 | PR 전 |
