# 문서

문서는 무엇을 고치는 사람이 읽는지에 따라 층을 나눈다.
공통은 backend 와 frontend 두 층에 걸친 제품 범위와 흐름과 결정이고, backend 는 Control Plane 의 동작이다.
frontend 는 화면의 동작과 구조이고, hermes 는 외부 런타임인 Hermes 가 어떻게 동작하는지다.
고칠 주제의 파일 하나를 아래 표에서 찾는다.

## 공통

| 문서 | 소유하는 것 |
| --- | --- |
| [`prd.md`](prd.md) | 제품의 목적과 범위, 범위 밖, 아직 정하지 않은 것 |
| [`code-architecture.md`](code-architecture.md) | 패키지 경계, Hermes 쪽 코드의 배치, 비밀값을 두는 곳, 아직 만들지 않은 것 |
| [`flow.md`](flow.md) | 화면 전환과 호출 순서, 두 방향의 토큰, 실행이 실패할 때의 흐름 |
| [`connectors.md`](connectors.md) | 커넥터 선언 파일, 연결 API, 승인, 토큰 저장 |
| [`connector-authoring.md`](connector-authoring.md) | 범용 커넥터를 만드는 방법, 갖출 것, PR 에 필요한 것, 공통 검사 |
| [`connectors/gmail.md`](connectors/gmail.md) | Gmail 커넥터의 도구와 정책, 보안, 설정 안내, 실제 계정 확인 |
| [`self-hosting.md`](self-hosting.md) | 기술 스택, 개별 실행, 주요 환경 변수 |
| [`model-tiers.md`](model-tiers.md) | 모델 단계의 선택 규칙, 에이전트 기본 모델, 모델 숨김, 비동기 자식 사용량 |
| [`adr/INDEX.md`](adr/INDEX.md) | 되돌리기 어려운 결정의 목록 |

## backend

| 문서 | 소유하는 것 |
| --- | --- |
| [`backend/packages.md`](backend/packages.md) | backend 패키지의 책임, 한 번의 대화가 지나는 길, 가격표 |
| [`backend/quality.md`](backend/quality.md) | 구조 규칙과 코드 규칙의 기준 파일을 갱신하는 방법, 뺀 규칙의 까닭 |
| [`backend/schema/README.md`](backend/schema/README.md) | 표와 칸의 뜻. 표를 주제별 파일로 나눈 색인이 있다 |
| [`backend/agent.md`](backend/agent.md) | 페르소나, 에이전트 도구, 에이전트를 만들고 지우는 규칙 |
| [`backend/agent-delegation.md`](backend/agent-delegation.md) | `agent_*` 도구로 다른 에이전트에게 맡기는 경로와 결과 도착 |
| [`backend/artifact.md`](backend/artifact.md) | 에이전트가 만든 결과물 파일의 저장과 조회 |
| [`backend/attachment.md`](backend/attachment.md) | 대화에 올린 사진의 저장과 전달 |
| [`backend/connector-install.md`](backend/connector-install.md) | 대시보드 plugin 의 커넥터 경로를 쓰는 방법, 커넥터 설치와 실패 처리, 커넥터 에이전트의 경계 |
| [`backend/connector-tool-policy.md`](backend/connector-tool-policy.md) | 커넥터 도구의 위험도와 승인 방식, 도구 호출 판정, 승인이 필요한 호출의 흐름, 사용자별 호출 제한 |
| [`backend/conversation.md`](backend/conversation.md) | 대화와 실행 사건, 모델 단계와 자식 기록, 도구 내용 가리기 |
| [`backend/mcp-caller.md`](backend/mcp-caller.md) | Control Plane MCP 호출의 요청자를 정하는 방법, MCP 서버와 결과물 쓰기 도구의 계약, 도구 호출의 입력 비용 |
| [`backend/memory.md`](backend/memory.md) | Memory 의 범위와 제안과 수락, 본문을 읽는 길 |
| [`backend/people.md`](backend/people.md) | 관리자가 사용자를 더하는 절차 |
| [`backend/skill.md`](backend/skill.md) | 스킬의 저장과 스킬 커맨드 전달 |
| [`backend/turn-control.md`](backend/turn-control.md) | 응답 중 대기열, 중지, 기동할 때 남은 실행 정리 |
| [`backend/notification.md`](backend/notification.md) | 대화 밖에서 사용자에게 알리는 것. 알림 종류, 만드는 때, 사용자 단위 SSE, 알림 화면, 보관 |
| [`backend/execution-limit.md`](backend/execution-limit.md) | 사용자 한 명이 Hermes 에 동시에 맡기는 실행의 한도, 세는 실행과 세지 못하는 실행, 한도끼리의 관계, 한도에 닿을 때 |

## frontend

| 문서 | 소유하는 것 |
| --- | --- |
| [`frontend/structure.md`](frontend/structure.md) | 화면 목록, 에이전트 화면과 사용량 화면의 구성, 화면의 정체성 |
| [`frontend/shell.md`](frontend/shell.md) | 대화 이력과 목록, 화면 틀, 로딩 표시, 밝기 모드 |
| [`frontend/chat.md`](frontend/chat.md) | 대화 화면의 모델 선택, 에이전트 질문, 다시 생성, 메시지 동작 |
| [`frontend/activity.md`](frontend/activity.md) | 실행 하나를 다시 보는 화면과 작업 과정 표시 |

화면에 관한 결정은 `adr/INDEX.md` 에서 층 칸이 frontend 인 것을 본다.

## Hermes

| 문서 | 소유하는 것 |
| --- | --- |
| [`hermes/README.md`](hermes/README.md) | Hermes 의 확장 지점과 `docs/hermes/` 개별 문서의 색인 |
| [`../hermes/README.md`](../hermes/README.md) | Hermes 에 설치하는 plugin 과 profile 틀, 설치 묶음과 운영 값, 대시보드 plugin 이 여는 경로 목록 |
