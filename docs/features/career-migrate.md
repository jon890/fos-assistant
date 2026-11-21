# 커리어 Backend 이전

covers: `docs/features/career-migrate/spec.md`, `docs/features/career-migrate/design.md`, `hermes/plugins/dashboard-profile-api/sandbox.py`

커리어 일(공고 추천, 공부 추천, 면접 연습, 지원 준비, 후보자 맥락 문서와 프로필 원고)을 커리어 Backend 없이 커리어 에이전트의 작업 폴더와 기억, 예약 작업으로 옮기는 기능이다.

요구와 결정은 스펙 [`career-migrate/spec.md`](career-migrate/spec.md) 가 갖는다. 코드 대부분은 fos-agents 저장소의 커리어 플러그인과 운영 저장소에서 바뀐다.
이 저장소에서 바뀌는 곳은 실행 공간이 받는 env 목록이고, 그 계약은 [에이전트 도구](agent-tools.md) 와 [`hermes/README.md`](../../hermes/README.md) 가 갖는다.
플러그인의 비서용 스킬이 설치되는 조건은 [에이전트와 스킬](agent-skill.md) 이 갖는다.
