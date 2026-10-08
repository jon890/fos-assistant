# 원격 검증

운영 명령과 profile 별 값은 `fos-home-infra` 가 갖는다. 여기에는 확인할 것만 적는다.

| 선행 조건 | 실행 위치 | 명령 | 기대값 |
|---|---|---|---|
| PR 을 push 한 뒤 | GitHub | `gh pr checks <PR 번호> --watch` | 필수 검사 모두 통과. hermes job 의 계약 확인에 `test_unattended_approval_points` 가 돈다 |
| 머지하고 대시보드 plugin 을 배포한 뒤 | 운영 | 실행 공간 정책에 등록된 docker profile 마다 셸 계열 도구를 다시 저장한다(에이전트 도구 화면 저장이나 운영의 일괄 반영) | 각 profile 설정에 `approvals.unattended_mode: approve` 가 있고 `approvals` 의 다른 키는 그대로다. gateway 를 다시 띄우지 않는다 |
| 위 반영 뒤 | 웹 | 목록 파일을 내는 커넥터가 붙은 docker profile 에이전트에게 이번 달 카테고리별 합계를 묻는다 | 실행이 `completed` 이고 `execute_code` 가 거절되지 않으며 합계가 답에 실린다 |
| 위 반영 뒤 | 웹 | 같은 에이전트에게 `/workspace` 아래 임시 디렉터리를 `rm -rf` 로 지우라고 한다 | 승인 카드가 뜬다. 거절하면 명령이 돌지 않는다 |
| 위 반영 뒤 | 운영 | local profile 의 설정을 읽는다 | `approvals.unattended_mode` 가 없다 |
