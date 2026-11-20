# 연결 반영 자동화 PR1 인수 검증 계획

## 인수 검증 계획

| 요구사항 | 준비 조건 | 실행 절차 | 기대 결과 | 실행 환경 | 실행 담당 | 선행 조건 | 시점 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| REQ1 | 붙은 바인딩 하나와, 정의가 바뀌어 다시 설치하면 `restart_required` 와 `cycle_available` 을 답하는 가짜 대시보드 | `ConnectorBindingCycleTest` 를 돌린다(`cd backend && ./gradlew test --tests '*ConnectorBindingCycleTest'`). 등록이나 정의 어긋남 점검이 다시 설치한 뒤 `ConnectorBindingApplier.runScheduled` 를 고정 시계로 되풀이한다 | 재시작 대기가 아니라 `IDLE_WAIT` → 끄기 설치(`cycle: "off"`) → `OFF` → `apply-delay` 뒤 켜기 설치 → 반영 예정 → `READY` 를 사람의 조작 없이 지난다. 관리자 반영 완료를 부르지 않는다 | H2 와 가짜 대시보드의 backend 테스트 | worker | 없음 | PR 전 |
| REQ1 | 바인딩이 `OFF` 인 동안 사용자가 값을 다시 등록한다 | 같은 테스트의 등록 끼어들기 사례 | 등록의 설치가 `cycle: "off"` 로 가고 단계가 `OFF` 로 남으며, 다음 켜기가 새 값으로 `READY` 가 된다 | H2 와 가짜 대시보드의 backend 테스트 | worker | 없음 | PR 전 |
| REQ1 | 자동 경로의 끄기 설치가 외부 호출 실패로 끝난다 | 같은 테스트의 실패 사례 | `apply_failed_at` 이 적히고 다음 주기가 설치를 다시 보내지 않는다. 관리자 반영 완료가 실패 표시를 비우고 그 단계의 설치를 바로 보낸다 | H2 와 가짜 대시보드의 backend 테스트 | worker | 없음 | PR 전 |
| REQ1 | 정의가 바뀌는 다시 설치와 끄기, 켜기 요청 | `python3 -m unittest hermes/tests/test_dashboard_profile_api_connector_binding_cycle.py` | 보통 설치가 `cycle_available` 을 답하고, 끄기가 설정에만 `enabled: false` 를 쓰고 끄기 기록을 남기며, 120초가 지난 보통 설치가 켜고 `reload_pending` 을 답한다. 120초 전 보통 설치는 꺼진 채 새 값만 쓴다 | 임시 profile 의 plugin 단위 테스트 | worker | 없음 | PR 전 |
| REQ1 | 운영에 배포된 PR1 과 값 교체 한 번 | 운영에서 실제 연결의 값을 다시 등록하고 관리자 조작 없이 기다린다(`fos-home-infra` 의 배포 뒤 확인 절차) | 몇 분 안에 그 바인딩이 `READY` 가 되고 새 값으로 도구 호출이 성공한다 | 운영 | 코디네이터 | 배포(PR1 머지 뒤) | PR 뒤 |
| REQ1 | 정의 어긋남 점검이 manifest 가 바뀐 바인딩을 다시 설치한다 | `ConnectorBindingCycleTest` 의 정의 어긋남 사례 | 바인딩이 `IDLE_WAIT` 가 되고 `CONNECTOR_REINSTALLED` 알림이 생기지 않는다 | H2 와 가짜 대시보드의 backend 테스트 | worker | 없음 | PR 전 |
| REQ1 | 옛 plugin 처럼 `cycle_available` 이 없는 답 | 같은 테스트의 호환 사례 | 지금처럼 재시작 대기가 되고 껐다 켜기 단계가 생기지 않는다 | H2 와 가짜 대시보드의 backend 테스트 | worker | 없음 | PR 전 |
| REQ1 | 서버가 꺼진 동안 떼기 | plugin 테스트의 떼기 사례 | 200 이고 설정의 서버 항목과 끄기 기록에서 그 플러그인이 지워진다 | 임시 profile 의 plugin 단위 테스트 | worker | 없음 | PR 전 |
| REQ2 | 같은 profile 에 다른 바인딩과 다른 profile 이 있다 | 같은 plugin 테스트의 보존 사례 | 끄기 설치 뒤 소유 기록, 같은 profile 의 다른 서버 항목, 다른 profile 파일이 바이트 그대로이고, 다른 바인딩의 다시 설치와 상태 조회가 그대로 성공한다 | 임시 profile 의 plugin 단위 테스트 | worker | 없음 | PR 전 |
| REQ10 | `IDLE_WAIT` 바인딩과, 그 주인의 진행 중 실행(`UserExecutionLimiter.used` 가 1 이상) | `ConnectorBindingCycleTest` 의 기다림 사례 | 바쁜 동안은 끄기 설치를 보내지 않고, 실행이 끝나거나 단계 시작에서 10분이 지나면 보낸다 | H2 와 가짜 대시보드의 backend 테스트 | worker | 없음 | PR 전 |
