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
| [`connectors.md`](connectors.md) | 커넥터의 정의, 언제 에이전트를 나누는가, 커넥터 선언 파일, 연결과 붙이기 API, 승인, 토큰 저장 |
| [`hermes/connectors/README.md`](../hermes/connectors/README.md) | 범용 커넥터를 만드는 방법, 갖출 것, PR 에 필요한 것, 공통 검사 |
| [`hermes/connectors/gmail/README.md`](../hermes/connectors/gmail/README.md) | Gmail 커넥터의 도구와 정책, 보안, 설정 안내, 실제 계정 확인 |
| [`hermes/connectors/naver-blog/README.md`](../hermes/connectors/naver-blog/README.md) | 네이버 블로그 커넥터의 도구와 정책, 초안의 모양, 임시저장 작업, 보안, 설정 안내, 실제 계정 확인 |
| [`hermes/connectors/tossinvest/README.md`](../hermes/connectors/tossinvest/README.md) | 토스증권 커넥터의 도구, 토큰과 허용 IP, 보안, 설정 안내, 실제 계정 확인 |
| [`self-hosting.md`](self-hosting.md) | 기술 스택, 개별 실행, 주요 환경 변수 |
| [`privacy.md`](privacy.md) | 개인정보 처리 안내. 커넥터 데이터가 어디로 가고 어디에 남는가 |
| [`read-data-flow.md`](read-data-flow.md) | 커넥터 READ 결과가 모델, 셸, 웹, 다른 커넥터, Memory, 결과물, 기록으로 가는 길의 신뢰 경계, 보호 수단이 보장하는 범위, 흐름 판정 표, 실행 공간이 해결한 것과 남은 것 |
| [`backend/docs/flow.md`](../backend/docs/flow.md) | 모델 단계의 선택 규칙, 에이전트 기본 모델, 모델 숨김, 비동기 자식 사용량 |
| [`adr/INDEX.md`](adr/INDEX.md) | 되돌리기 어려운 결정의 목록과 ADR 작성·참조·정렬 규칙 |

## backend

| 문서 | 소유하는 것 |
| --- | --- |
| [`backend/docs/flow.md`](../backend/docs/flow.md) | 기능마다 받는 것과 부르는 순서, 갈리는 지점. 대화와 실행, 에이전트와 커넥터, Memory 와 알림, 먼저 살펴보기 루프 |
| [`backend/docs/data-schema.md`](../backend/docs/data-schema.md) | 표와 칸의 뜻, 지울 때 함께 지워지는 것, 마이그레이션 작성 규칙 |
| [`backend/docs/code-architecture.md`](../backend/docs/code-architecture.md) | 패키지의 책임과 층, 한 번의 대화가 지나는 길, 통합 검사, 코드 품질 검사 |

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
