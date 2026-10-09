# 문서

문서는 무엇을 고치는 사람이 읽는지에 따라 층을 나눈다.
공통은 backend 와 frontend 두 층에 걸친 제품 범위와 흐름과 결정이고, backend 는 Control Plane 의 동작이다.
frontend 는 화면의 동작과 구조이고, hermes 는 외부 런타임인 Hermes 가 어떻게 동작하는지다.
고칠 주제의 파일 하나를 아래 표에서 찾는다.

## 공통

| 문서 | 소유하는 것 |
| --- | --- |
| [`prd.md`](prd.md) | 제품의 목적과 범위, 범위 밖, 아직 정하지 않은 것 |
| [`code-architecture.md`](code-architecture.md) | 패키지 경계, 사용자와 profile, 에이전트, 대화의 층, 비밀값을 두는 곳, 아직 만들지 않은 것 |
| [`flow.md`](flow.md) | 화면 전환과 호출 순서, 두 방향의 토큰, 실행이 실패할 때의 흐름 |

## web

| 문서 | 소유하는 것 |
| --- | --- |
| [`web/docs/prd.md`](../web/docs/prd.md) | 화면마다 보이는 것과 그리지 않는 것. 관리자 영역, 에이전트 화면, 사용량 화면, 지금 화면, 기억 화면, 대화 목록, 새 대화 화면, 동작 승인, 참고한 기억, 작업 과정 블록 |
| [`web/docs/flow.md`](../web/docs/flow.md) | 화면에서 하는 일마다 주고받는 순서. 대화 이력, 모델 고르기, 질문 카드, 다른 창에서 답하는 중일 때, 다시 생성, 결과 다시 전달, 점검 대화, 실행 다시 보기, 꺼진 사용자의 세션 |
| [`web/docs/code-architecture.md`](../web/docs/code-architecture.md) | 화면 목록, 화면 틀, 디렉터리, 테마 토큰, 화면의 정체성, 마크다운 읽기, 밝기 모드 |

화면에 관한 결정은 [`web/docs/adr/INDEX.md`](../web/docs/adr/INDEX.md) 에 있다.

## Hermes

| 문서 | 소유하는 것 |
| --- | --- |
| [`hermes/AGENTS.md`](../hermes/AGENTS.md) | hermes 모듈의 규칙과 문서 목록 |
| [`hermes/docs/code-architecture.md`](../hermes/docs/code-architecture.md) | hermes 모듈의 구조와 배포 순서 |
| [`hermes/docs/hermes-contract.md`](../hermes/docs/hermes-contract.md) | Hermes 의 동작 계약. profile, Runs API, 동시 실행, 위임, 도구 hook, 실행 공간, 스킬, 설정 API, 올릴 때 |
| [`hermes/README.md`](../hermes/README.md) | 설치 묶음과 운영 값, 검사 |
