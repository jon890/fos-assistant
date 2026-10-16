# 원격 검증

| 선행 조건 | 시점 | 실행 위치 | 명령 | 기대값 |
| --- | --- | --- | --- | --- |
| push | PR 뒤 | GitHub Draft CI | `gh pr checks <PR> --watch` | 필수 검사 통과. 무거운 backend·MySQL 검사는 CI가 수행한다 |
| push | PR 뒤 | GitHub PR | `gh pr ready <PR>` | Draft CI 통과와 최신 main 반영 뒤 Ready가 된다 |
| push | PR 뒤 | GitHub Claude 리뷰 | `gh pr checks <PR> --watch` | claude-review 종료 뒤 모든 지적에 답하고 한 번 반영한다 |

반영 push의 CI 통과와 미해결 스레드 0을 확인한 뒤 코디네이터에게 PR 번호·head·검증 결과를 보고한다.
머지와 정리는 코디네이터가 맡는다. 홈서버와 실제 외부 서비스는 사용하지 않는다.
