# 원격 검증

| 선행 조건 | 실행 위치 | 명령 | 기대값 |
|---|---|---|---|
| PR 브랜치를 push 한 뒤 | GitHub Actions | `gh pr checks <PR 번호> --watch` | 필수 검사(backend, web, e2e, unit, hermes, quality, public-safe) 통과 |
| 머지와 배포, 운영 목록에 `naver-blog` 를 올린 뒤 | 웹 「연결」 화면 | 네이버 블로그 연결 확인 | `session_status` 가 성공해 연결이 `READY` |
| 위 연결을 블로그 주인의 기본 에이전트에 붙이고 반영 완료한 뒤 | 그 에이전트의 대화 | `docs/connectors/naver-blog.md` 의 「실제 계정으로 확인하기」 1~5 | 미리보기에 첨부 사진 두 장이 보이고, 작업이 `succeeded`, 임시저장 수가 하나 늘고, 다시 연 글에 사진 두 장의 `문서 너비`·스티커·지도·카테고리·태그가 남는다 |
| 같은 확인 | 그 에이전트의 대화 | 승인 결과의 자동 turn 에서 `draft_job` 이 작업 파일을 찾는지 | `NAVER_BLOG_JOB_NOT_FOUND` 가 아니다. 대시보드 실행 경로와 Hermes 의 MCP 서버가 같은 임시 디렉터리, 같은 uid, 같은 PID 네임스페이스에 있다는 전제의 확인이다 |
