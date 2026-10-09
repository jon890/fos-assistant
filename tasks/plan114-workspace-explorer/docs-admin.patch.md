diff --git a/docs/backend/packages.md b/docs/backend/packages.md
index 1cef514..ca4347f 100644
--- a/docs/backend/packages.md
+++ b/docs/backend/packages.md
@@ -38,7 +38,7 @@ Control Plane 의 패키지마다 맡는 책임과 패키지 사이의 방향 
 | `followup` | 할 일의 저장과 상태 전이, 사람이 쓰는 API, 에이전트의 제안 저장([`follow-up.md`](follow-up.md)) |
 | `proactive` | 먼저 살펴보기의 시작 전 점검, 점검 대화의 살펴보기 turn, 상한, 결과 계약의 검사와 그리기, 문제 후보의 검사와 저장, 살펴보기 트리 판정([`proactive-check.md`](proactive-check.md)), 판단 피드백의 replay 읽기 모델, 매일 루프의 이음매와 보일 판정, 판정 반응([`proactive-loop.md`](proactive-loop.md)) |
 | `attention` | 먼저 알리기의 판정과 지금 화면이 읽는 카드. 다른 패키지의 기록을 읽기만 한다([`attention.md`](attention.md)) |
-| `workspace` | 사용자 실행 공간의 파일 목록과 본문, 권한 도우미로 지우기([`../code-architecture.md`](../code-architecture.md) 의 「실행 공간 파일」) |
+| `workspace` | 사용자 실행 공간의 파일 목록과 본문, 권한 도우미로 지우기, 관리자의 공간별 용량([`../code-architecture.md`](../code-architecture.md) 의 「실행 공간 파일」) |
 
 검사: `ArchitectureRules.SHARED_DOES_NOT_DEPEND_ON_DOMAINS`
 
@@ -92,7 +92,7 @@ Control Plane 의 패키지마다 맡는 책임과 패키지 사이의 방향 
 `browser` 는 `user` 바로 위다. 관리자 목록의 사용자 이름을 읽으려고 `user` 를 쓰고, 사용자를 끈 사건(`shared.auth.UserAccessRevoked`)을 받는다. 커넥터 바인딩이 브라우저 중계를 쓰게 되므로 `connector` 보다 아래에 둔다. 중계가 바인딩 표식의 주인을 찾으려고 `browser` 에 port(`BrowserGrantOwners`)를 두고 `connector` 가 구현한다.
 `notification` 은 `user` 바로 위다. 알림을 만드는 쪽(`connector`, 그 위의 패키지)이 모두 이 패키지를 부르고, 이 패키지는 알림을 받는 사용자 말고 다른 도메인을 모른다.
 `attention` 은 `workspace` 바로 아래다. 먼저 알리기의 후보를 읽으려고 `usage`, `chat`, `agent`, `memory`, `connector`, `followup` 의 `application` 을 부르고, 어느 패키지도 `attention` 을 import 하지 않는다.
-`workspace` 는 맨 위다. 함께 쓰는 에이전트를 읽으려고 `agent` 를, 도는 실행 수를 읽으려고 `usage` 를 부른다. 어느 패키지도 `workspace` 를 import 하지 않는다.
+`workspace` 는 맨 위다. 함께 쓰는 에이전트를 읽으려고 `agent` 를, 도는 실행 수를 읽으려고 `usage` 를, 관리자 용량의 이름을 읽으려고 `user` 를 부른다. 어느 패키지도 `workspace` 를 import 하지 않는다.
 검사: `ArchitectureRules.TOP_LEVEL_PACKAGES_FOLLOW_LAYER_ORDER`, 근거: ADR-068
 
 ### proactive
diff --git a/docs/code-architecture.md b/docs/code-architecture.md
index dff8f2a..c365555 100644
--- a/docs/code-architecture.md
+++ b/docs/code-architecture.md
@@ -145,6 +145,7 @@ Memory 의 기본 근거는 [`adr/ADR-003-memory-권한은-주입으로-강제
 | `GET /api/v1/workspace/files/{경로}` | 미리보기 본문 | 아래 「본문 머리글」. 경로의 조각마다 URL 인코딩한다 |
 | `GET /api/v1/workspace/files/{경로}?download=1` | 내려받기 | 크기 상한 없이 스트림으로 준다 |
 | `DELETE /api/v1/workspace/entries?path=` | 지우기 | 아래 「지우기」 |
+| `GET /api/v1/admin/workspaces` | 사용자별 용량. `ADMIN` 만 | 아래 「관리자 용량」 |
 
 본문 경로의 오류는 아래와 같다. 루트 확인(503), 경로 검사(400) 다음에, 미리보기는 확장자(415), 크기(413), 종류(404), 읽기 권한(403) 차례로 판정한다. 내려받기는 종류와 읽기 권한만 본다.
 
@@ -229,6 +230,16 @@ Control Plane 은 경로 규칙을 먼저 검사하고 읽기 마운트에서 
 - 요청마다 시각, 주인 키, 경로, 종류, 지운 항목 수, 결과를 감사 기록 한 줄로 남긴다. 파일 본문은 읽지도 남기지도 않는다
 - 실행 공간 루트만 쓰기로 붙이고, 지우는 데 필요한 권한만 갖는다
 
+### 관리자 용량
+
+`GET /api/v1/admin/workspaces` 는 `{available, spaces: [{kind, id, name, bytes, entries, partial}]}` 를 준다.
+`kind` 는 `USER`(디렉터리 `u<번호>`) 나 `AGENT`(디렉터리 `a<번호>`) 이고 `name` 은 사용자 이름이나 에이전트 이름이다. 이름을 찾지 못하면 빈 값이다.
+그 밖의 이름을 가진 디렉터리는 세지 않는다. 파일 이름과 경로는 응답에 없다.
+
+요청할 때 링크를 따라가지 않고 센다. `bytes` 는 일반 파일 크기의 합이다.
+공간 하나에 항목 200,000 개, 요청 전체에 30초를 넘기면 거기서 멈추고 `partial` 을 참으로 둔다. 읽지 못한 디렉터리가 있어도 `partial` 이 참이다.
+줄은 `bytes` 가 큰 순서다.
+
 ## 화면을 검증하는 방법
 
 테스트는 확인하는 대상을 나눠 둔다.
diff --git a/docs/frontend/shell.md b/docs/frontend/shell.md
index 4c87166..ace0f28 100644
--- a/docs/frontend/shell.md
+++ b/docs/frontend/shell.md
@@ -238,7 +238,7 @@ ChatGPT 의 배치를 따른다. 위쪽 가로 메뉴를 두지 않고 왼쪽 
 ```
 
 머리의 「관리자」 는 배지다. 색과 부품은 일반 화면과 같은 토큰을 쓴다.
-관리자 메뉴도 항목마다 아이콘을 둔다. 사용자 `Users`, 에이전트 `Bot`, 모델 `Cpu`, 사용량과 비용 `Receipt`, 커넥터 `Plug`, 브라우저 `Globe` 다.
+관리자 메뉴도 항목마다 아이콘을 둔다. 사용자 `Users`, 에이전트 `Bot`, 모델 `Cpu`, 사용량과 비용 `Receipt`, 커넥터 `Plug`, 브라우저 `Globe`, 파일 공간 `HardDrive` 다.
 머리에 특정 그룹을 뜻하는 이름을 적지 않는다.
 
 **「사용 화면으로 돌아가기」 는 마지막으로 보던 대화로 간다.**
diff --git a/docs/frontend/structure.md b/docs/frontend/structure.md
index 1c3687d..35cd361 100644
--- a/docs/frontend/structure.md
+++ b/docs/frontend/structure.md
@@ -37,6 +37,7 @@
 | `/admin/executions/{id}` | 실제 모델과 토큰, 금액, 시각 구간, 오류 코드, 도구 원본, 실은 문맥 항목의 참조가 보이는 실행 하나. 참조의 뜻은 [`backend/context-bundle.md`](../backend/context-bundle.md) 의 「로그와 저장」 이 갖는다 |
 | `/admin/connections` | 반영을 기다리는 연결의 반영 완료 |
 | `/admin/browsers` | 모든 사용자 브라우저의 사용자 이름, 상태, 마지막 사용 시각, 오류 코드와 끄기, 지우기 |
+| `/admin/workspaces` | 실행 공간마다 주인 이름, 용량, 항목 수와 일부만 셌는지. 파일 이름은 보이지 않는다 |
 
 `/admin` 아래는 `ADMIN` 만 연다.
 
@@ -55,6 +56,7 @@
 | 사용량과 비용 | `/admin/usage`, `/admin/executions/{id}` | 금액과 모델과 토큰, 「사용량 내역」, 「설정별 사용량」 탭, 실행 상세의 내부 값 | `/usage` 와 `/executions/{id}` 가 `ADMIN` 에게 더 그리던 것 |
 | 커넥터 | `/admin/connections` | 반영을 기다리는 바인딩의 「반영 완료」. 재시작 대기 바인딩은 공유 gateway 를 재시작한 뒤 누른다 | `/connections` 아래의 관리자 패널 |
 | 브라우저 | `/admin/browsers` | 모든 사용자 브라우저의 상태와 오류 코드, 끄기와 지우기 | 없음. `/browser` 는 내 브라우저만 보이고 오류 코드를 그리지 않는다 |
+| 파일 공간 | `/admin/workspaces` | 실행 공간마다 용량과 항목 수 | 없음. `/files` 는 내 공간만 보이고 관리자도 남의 공간을 열지 못한다 |
 
 대화 화면도 같다. 작업 과정의 「원본 보기」, 모델이 바뀌었다는 표시, 오류 코드는 일반 화면에 그리지 않는다.
 실패한 실행의 원인은 `/admin/usage` 의 실행 기록에서 그 실행을 열어 본다.
diff --git a/docs/prd.md b/docs/prd.md
index dd8c0b4..8998775 100644
--- a/docs/prd.md
+++ b/docs/prd.md
@@ -67,6 +67,7 @@
 | 대화를 찾고 이름을 바꾸고 지운다 | 지운 대화가 목록에서 사라져도 그 대화의 비용은 사용량에 남는다 |
 | 대화에 사진을 올려 에이전트에게 보인다 | 입력창에서 올린 사진을 에이전트가 파일로 읽고 답한다. 근거는 [ADR-020](adr/ADR-020-사진은-공유-디렉터리에-두고-에이전트가-파일로-읽는다.md) 에 있다 |
 | 에이전트가 만든 HTML 결과물을 대화에서 바로 본다 | 답 아래의 파일을 누르면 옆 패널에 그 페이지가 사진과 함께 보인다. 그 페이지의 스크립트는 돌지 않는다 |
+| 에이전트가 실행 공간에 만든 파일을 사용자가 보고 내려받고 지운다([ADR-20261009 / workspace-explorer](adr/ADR-20261009-workspace-explorer.md)) | 사이드바 「고급」 의 「파일 공간」 에서 자기 에이전트들이 함께 쓰는 공간의 목록이 보이고, 글과 사진과 CSV 표와 HTML 을 미리 본다. HTML 의 스크립트는 돌지 않는다. 다른 사용자의 공간은 어떤 경로로도 열리지 않고, 관리자는 사용자별 용량만 본다 |
 | 사용자마다 그 에이전트로 자주 하는 일을 추천한다 | 새 대화 화면에서 에이전트를 고르면 자기 대화 이력에서 나온 추천이 보이고, 이력이 없으면 그 에이전트가 할 수 있는 일이 보인다. 누르면 바로 보낸다 |
 | 외부 서비스에 쓰는 일은 사용자가 승인한 것만 실행한다 | 승인이 필요한 도구를 에이전트가 불러도 그 서비스에 요청이 가지 않고, 승인하면 승인한 인자 그대로 한 번만 실행된다 |
 | 정한 시각에 내 권한으로 에이전트를 돌린다 | 화면에서 「매달 1일 9시」 같은 예약 작업을 만들면 그 시각에 새 대화가 열려 답이 남는다. 서버를 다시 띄워도 같은 회차는 한 번만 돈다. 그 실행이 쓰기 도구를 부르면 사람이 있을 때와 같이 승인을 기다리고 알림이 간다 |
@@ -130,6 +131,8 @@
   실행 기록이 그 대화를 가리키고 비용이 거기서 나온다.
 - **결과물의 스크립트를 돌리지 않는다.** 에이전트가 만든 HTML 은 읽기만 한다.
   근거는 [ADR-027](adr/ADR-027-에이전트가-만든-html-은-대화별-폴더에-두고-스크립트-없이-보인다.md) 에 있다.
+- **실행 공간에 파일을 올리거나 고치지 않는다.** 「파일 공간」 은 보기, 내려받기, 지우기만 한다.
+  근거는 [ADR-20261009 / workspace-explorer](adr/ADR-20261009-workspace-explorer.md) 에 있다.
 - **먼저 알리기를 화면 밖으로 보내지 않는다.** 외부 채널은 credential 을 둘 곳, 실을 내용의 민감도, 외부로 나가는 글의 승인을 함께 정한 뒤에 연다.
   근거는 [ADR-072](adr/ADR-072-먼저-알리기의-기본값은-알리지-않음이고-control-plane-기록에서-정한-신호만-화면-안에-올린다.md) 에 있다.
 - **먼저 알리기와 할 일이 실행을 시작하지 않는다.** 보이기만 하고, 동작은 사람이 단추를 눌러 기존 경로로 한다.
