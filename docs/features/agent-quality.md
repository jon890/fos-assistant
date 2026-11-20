# 에이전트 품질

비서가 운영에서 실제로 동작하는지와 답 내용이 좋은지를 사람 손 없이 확인하는 기능이다. 배포 뒤 기능별 자동 확인, 기준 답과 비교하는 정기 평가, 답에 대한 좋다·나쁘다 반응과 관리자 품질 화면을 다룬다.

covers: `backend/src/main/java/com/bifos/assistant/quality/`, `backend/src/main/java/com/bifos/assistant/admin/quality/`

요구와 결정은 스펙 [`agent-quality/spec.md`](agent-quality/spec.md), 구조는 설계 [`agent-quality/design.md`](agent-quality/design.md)가 갖는다.
지금은 배포 뒤 확인의 기반(평가 전용 사용자, 확인 기록 표 `quality_run`, 기능별 규칙 판정)까지 들어갔다. 확인을 돌리는 실행기와 품질 화면은 뒤 PR 이 한다. 그 뒤로 정기 평가, 피드백 순서로 들어온다.

## 평가 전용 사용자

배포 뒤 확인은 실제 사용자의 데이터를 건드리지 않도록 평가 전용 사용자로 돈다. 관리자가 `POST /api/v1/admin/quality/evaluation-user`(본문 `hermesProfile`)로 한 번 준비하고 `GET` 으로 상태를 읽는다. 다시 불러도 빠진 것만 채운다.

- 준비하면 허용 목록 행과 Hermes profile, 사용자(`MEMBER`, `app_user.evaluation` 참), 비공개 기본 비서와 위임 대상 에이전트, 늘 넣는 기억 한 줄이 생긴다
- 이 사용자는 로그인하지 않는다. 로그인 판정과 매 요청 인증이 막는다. 관리자 사용자 목록에는 「평가용」 으로 보이고, 에이전트가 비공개라 다른 사용자의 고르기와 멘션에 나오지 않는다
- 기본 비서의 도구(첫 로그인 기본 도구와 그림·사진 도구)는 실행 공간 안에서만 켠다. 운영이 그 profile 을 실행 공간과 첨부 정책에 먼저 등록해야 한다. 등록 전이면 상태의 빠진 것에 `POLICY_REGISTRATION_REQUIRED` 가 남는다(설계 「운영 저장소에 넘길 요청」). 처음 준비한 직후에는 도구 켜기가 요청 밖에서 돌아 잠시 남을 수 있으니 `GET` 으로 다시 읽는다. Hermes 가 도구 목록을 돌려주지 않으면 `TOOLS_UNREADABLE` 이다
- 빠진 것의 `PRIMARY_AGENT` 는 다시 준비해도 채우지 않는다. 기본 비서는 지울 수 없어 첫 로그인이 허용 목록 행을 찾지 못했을 때만 생기므로, 사람이 허용 목록 행과 평가 사용자를 확인해 조치한다

숨기는 곳 전체와 만드는 순서는 설계의 「평가 전용 사용자」 가 갖는다.
지금 답변 품질 평가는 [`answer-quality.md`](answer-quality.md) 의 「답변 품질 평가」 가 갖고, 정기 평가로 옮기면 이 파일로 넘어온다.
