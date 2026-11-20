# 플러그인 에이전트용 스킬 설치 인수 검증 계획

## 인수 검증 계획

| 요구사항 | 준비 조건 | 실행 절차 | 기대 결과 | 실행 환경 | 실행 담당 | 선행 조건 | 시점 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| REQ1 | `agent_skills` 를 둔 임시 플러그인과 칸 없는 플러그인 | `python3 -m unittest discover -s hermes/tests -p 'test_connector_manifest.py'` 의 에이전트용 스킬 사례(정상, 칸 없음, 거절)를 돌린다 | 칸 있는 플러그인은 `skills` 에 이름이 합쳐지고 `agent_skills` 가 있다. 칸 없는 플러그인은 지금과 같다. 잘못된 선언은 카탈로그에서 빠진다 | 로컬 unittest | worker | 없음 | PR 전 |
| REQ2 | 터미널 꺼진 임시 profile | `python3 -m unittest discover -s hermes/tests -p 'test_dashboard_profile_api_connector_binding_install.py'` 의 터미널 꺼진 붙이기와 꺼 둔 스킬 다시 설치 사례 | 요구 env 없는 에이전트용 스킬이 `skills/` 에 있다. `skills.disabled` 에 넣은 이름은 다시 설치 뒤에도 남는다 | 로컬 unittest | worker | 없음 | PR 전 |
| REQ3 | 터미널 꺼진 profile, 셸 조건을 모두 갖춘 profile | `python3 -m unittest discover -s hermes/tests -p 'test_dashboard_profile_api_connector_binding_install.py'` 의 두 profile 붙이기와 같은 요청 다시 보내기 사례, `python3 -m unittest discover -s hermes/tests -p 'test_connector_agent_skills.py'` 의 까닭 순서 사례 | 앞은 200 이고 요구 env 스킬만 빠지며 `skipped_skills` 에 까닭이 있다. 뒤는 모두 설치되고 목록이 비었다. 「바뀐 것 없음」 응답에도 같은 목록이 있다 | 로컬 unittest | worker | 없음 | PR 전 |
| REQ4 | 붙인 profile | `python3 -m unittest discover -s hermes/tests -p 'test_dashboard_profile_api_connector_binding_install.py'` 의 조건 변화 사례 | 조건이 그대로면 붙인 직후 `configured` 가 참이다. 조건을 갖추면 거짓, 다시 설치 뒤 스킬이 깔리고 참. 조건을 잃으면 거짓, 다시 설치 뒤 스킬이 지워지고 참 | 로컬 unittest | worker | 없음 | PR 전 |
| REQ5 | 에이전트용 스킬이 설치된 profile | `python3 -m unittest discover -s hermes/tests -p 'test_dashboard_profile_api_connector_binding_install.py'` 의 떼기 사례 | 그 스킬 디렉터리가 없다 | 로컬 unittest | worker | 없음 | PR 전 |
| REQ6 | 같은 이름의 profile 스킬 디렉터리, 붙인 뒤 생긴 같은 이름의 올린 스킬 | `python3 -m unittest discover -s hermes/tests -p 'test_dashboard_profile_api_connector_binding_install.py'` 의 이름 겹침 사례 | 처음 붙이기는 409 이고 파일이 그대로다. 설치된 스킬과 같은 이름이 올린 스킬 자리에 생기면 다시 설치는 200 이고 그 스킬만 `name_taken` 으로 지워지며 올린 스킬이 그대로고, 직후 `configured` 가 참이다 | 로컬 unittest | worker | 없음 | PR 전 |
| REQ7 | 붙이기 전 profile 의 `.env` 와 `terminal` 설정 | `python3 -m unittest discover -s hermes/tests -p 'test_dashboard_profile_api_connector_binding_install.py'` 의 전후 비교 사례와 `python3 -m unittest discover -s hermes/tests -p 'test_dashboard_profile_api_sandbox_policy.py'` | 둘이 같다. `test_dashboard_profile_api_sandbox_policy.py` 를 고치지 않고 통과한다 | 로컬 unittest | worker | 없음 | PR 전 |
| REQ2, REQ3 | fos-agents 가 `agent-skills/` 사본과 manifest 칸을 낸 플러그인 버전 | 커리어 에이전트에서 다시 설치를 기다린 뒤 `/position-recommender` 를 직접 입력해 실행기를 한 번 부른다. 절차는 `fos-home-infra` 가 갖는다 | 스킬이 깔려 있고 실행기가 커리어 Backend 에 닿아 결과를 낸다 | 운영 | 코디네이터 | 배포(이 PR 과 fos-agents 변경) | PR 뒤 |
