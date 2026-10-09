diff --git a/docs/README.md b/docs/README.md
index 7223b8b..55f0287 100644
--- a/docs/README.md
+++ b/docs/README.md
@@ -10,8 +10,8 @@ frontend 는 화면의 동작과 구조이고, hermes 는 외부 런타임인 He
 | 문서 | 소유하는 것 |
 | --- | --- |
 | [`prd.md`](prd.md) | 제품의 목적과 범위, 범위 밖, 아직 정하지 않은 것 |
-| [`code-architecture.md`](code-architecture.md) | 패키지 경계, 사용자와 profile, 에이전트, 대화의 층, Hermes 쪽 코드의 배치, 실행 공간 파일의 경로 규칙과 API, 비밀값을 두는 곳, 아직 만들지 않은 것 |
-| [`flow.md`](flow.md) | 화면 전환과 호출 순서, 두 방향의 토큰, 실행이 실패할 때의 흐름, 파일 공간을 열 때의 흐름 |
+| [`code-architecture.md`](code-architecture.md) | 패키지 경계, 사용자와 profile, 에이전트, 대화의 층, Hermes 쪽 코드의 배치, 실행 공간 파일의 경로 규칙과 API 와 지우기 도우미 계약, 비밀값을 두는 곳, 아직 만들지 않은 것 |
+| [`flow.md`](flow.md) | 화면 전환과 호출 순서, 두 방향의 토큰, 실행이 실패할 때의 흐름, 파일 공간을 열고 지울 때의 흐름 |
 | [`connectors.md`](connectors.md) | 커넥터의 정의, 언제 에이전트를 나누는가, 커넥터 선언 파일, 연결과 붙이기 API, 승인, 토큰 저장 |
 | [`connector-authoring.md`](connector-authoring.md) | 범용 커넥터를 만드는 방법, 갖출 것, PR 에 필요한 것, 공통 검사 |
 | [`connectors/gmail.md`](connectors/gmail.md) | Gmail 커넥터의 도구와 정책, 보안, 설정 안내, 실제 계정 확인 |
diff --git a/docs/backend/packages.md b/docs/backend/packages.md
index f0e179f..1cef514 100644
--- a/docs/backend/packages.md
+++ b/docs/backend/packages.md
@@ -38,7 +38,7 @@ Control Plane 의 패키지마다 맡는 책임과 패키지 사이의 방향 
 | `followup` | 할 일의 저장과 상태 전이, 사람이 쓰는 API, 에이전트의 제안 저장([`follow-up.md`](follow-up.md)) |
 | `proactive` | 먼저 살펴보기의 시작 전 점검, 점검 대화의 살펴보기 turn, 상한, 결과 계약의 검사와 그리기, 문제 후보의 검사와 저장, 살펴보기 트리 판정([`proactive-check.md`](proactive-check.md)), 판단 피드백의 replay 읽기 모델, 매일 루프의 이음매와 보일 판정, 판정 반응([`proactive-loop.md`](proactive-loop.md)) |
 | `attention` | 먼저 알리기의 판정과 지금 화면이 읽는 카드. 다른 패키지의 기록을 읽기만 한다([`attention.md`](attention.md)) |
-| `workspace` | 사용자 실행 공간의 파일 목록과 본문([`../code-architecture.md`](../code-architecture.md) 의 「실행 공간 파일」) |
+| `workspace` | 사용자 실행 공간의 파일 목록과 본문, 권한 도우미로 지우기([`../code-architecture.md`](../code-architecture.md) 의 「실행 공간 파일」) |
 
 검사: `ArchitectureRules.SHARED_DOES_NOT_DEPEND_ON_DOMAINS`
 
diff --git a/docs/code-architecture.md b/docs/code-architecture.md
index 31cdada..dff8f2a 100644
--- a/docs/code-architecture.md
+++ b/docs/code-architecture.md
@@ -99,7 +99,7 @@ Memory 의 기본 근거는 [`adr/ADR-003-memory-권한은-주입으로-강제
 | 키 | 환경 변수 | 비었을 때 |
 | --- | --- | --- |
 | `assistant.sandbox-workspace.root` | `ASSISTANT_SANDBOX_WORKSPACE_ROOT` | 기동한다. 상태 조회 밖의 모든 경로가 503 `WORKSPACE_UNAVAILABLE` 이고 상태 조회는 `available: false` 다 |
-| `assistant.sandbox-workspace.delete-socket` | `ASSISTANT_SANDBOX_WORKSPACE_DELETE_SOCKET` | 기동한다. 상태 조회의 `deletable` 이 거짓이고 화면은 지우기를 열지 않는다 |
+| `assistant.sandbox-workspace.delete-socket` | `ASSISTANT_SANDBOX_WORKSPACE_DELETE_SOCKET` | 기동한다. 지우기가 `WORKSPACE_DELETE_UNAVAILABLE` 이고 상태 조회는 `deletable: false` 다 |
 
 `root` 는 실행 공간 정책의 `workspace_root` 와 같은 디렉터리를 Control Plane 에서 본 경로다. 읽기 전용으로 붙인다.
 붙이는 일은 `fos-home-infra` 가 한다. 루트가 링크가 아닌 디렉터리가 아니어도 `WORKSPACE_UNAVAILABLE` 이다.
@@ -132,7 +132,7 @@ Memory 의 기본 근거는 [`adr/ADR-003-memory-권한은-주입으로-강제
 | `OTHER` | FIFO, 소켓, 장치 | 주지 않는다 |
 
 `readable` 이 거짓이면 화면은 「읽을 수 없음」 을 보인다. 권한이 없는 파일, 하드 링크가 둘 이상인 파일, `LINK`, `OTHER` 가 그렇다.
-`openable` 은 이름이 주소 조각으로 쓸 수 있는지다. `%`, `;`, `\` 가 든 이름은 Control Plane 의 요청 방화벽이 주소에서 거절하므로 미리보기와 내려받기를 열지 않는다. 목록은 `path` 인자로 열므로 그런 이름의 디렉터리도 연다.
+`openable` 은 이름이 주소 조각으로 쓸 수 있는지다. `%`, `;`, `\` 가 든 이름은 Control Plane 의 요청 방화벽이 주소에서 거절하므로 미리보기와 내려받기를 열지 않는다. 목록과 지우기는 `path` 인자로 하므로 그런 이름도 된다.
 
 ### API
 
@@ -144,6 +144,7 @@ Memory 의 기본 근거는 [`adr/ADR-003-memory-권한은-주입으로-강제
 | `GET /api/v1/workspace/entries?path=` | 디렉터리 하나의 목록 | `{path, entries: [{name, kind, size, modifiedAt, readable, openable}], truncated}`. 디렉터리를 읽는 순서로 1,001개까지 읽고, 그 가운데 1,000개를 디렉터리 먼저, 이름 순서로 준다. 1,001번째가 있으면 `truncated` 가 참이고, 그때는 순서상 앞선 항목도 빠질 수 있다. `size` 는 `FILE` 만 채운다. 사용자 디렉터리가 아직 없으면 빈 목록이다 |
 | `GET /api/v1/workspace/files/{경로}` | 미리보기 본문 | 아래 「본문 머리글」. 경로의 조각마다 URL 인코딩한다 |
 | `GET /api/v1/workspace/files/{경로}?download=1` | 내려받기 | 크기 상한 없이 스트림으로 준다 |
+| `DELETE /api/v1/workspace/entries?path=` | 지우기 | 아래 「지우기」 |
 
 본문 경로의 오류는 아래와 같다. 루트 확인(503), 경로 검사(400) 다음에, 미리보기는 확장자(415), 크기(413), 종류(404), 읽기 권한(403) 차례로 판정한다. 내려받기는 종류와 읽기 권한만 본다.
 
@@ -186,9 +187,48 @@ HTML 이 상대 경로로 부르는 CSS 와 사진은 같은 `files/` 아래 주
 
 ### 로그와 기록
 
-본문은 로그와 실행 기록에 남기지 않는다.
+본문은 로그와 실행 기록에 남기지 않는다. 지우기는 사용자 번호, 상대 경로, 종류, 지운 항목 수, 결과를 `INFO` 로그 한 줄로 남긴다.
 목록과 본문의 오류 로그는 사용자 번호와 오류 종류만 남기고 경로를 남기지 않는다.
 
+### 지우기
+
+**지우기는 경로 하나를 받는다.** 빈 경로(사용자 디렉터리 자체)는 400 `VALIDATION_FAILED` 다.
+Control Plane 은 경로 규칙을 먼저 검사하고 읽기 마운트에서 그 경로가 있는지 본 뒤 운영의 권한 도우미를 부른다.
+
+| 판정 | 응답 |
+| --- | --- |
+| 지운다 | 200 `{kind, entries, bytes}`. `entries` 는 지운 항목 수, `bytes` 는 지운 일반 파일의 크기 합이다 |
+| 도우미 socket 이 설정되지 않았다 | 503 `WORKSPACE_DELETE_UNAVAILABLE` |
+| 없는 경로, 링크를 지나는 경로 | 404 `WORKSPACE_ENTRY_NOT_FOUND` |
+| 디렉터리 안의 항목이 10,000 개를 넘는다 | 409 `WORKSPACE_DELETE_TOO_MANY`. 아무것도 지우지 않는다 |
+| 도우미가 실패했거나 30초 안에 답하지 않았다 | 502 `WORKSPACE_DELETE_FAILED` |
+
+**권한 도우미와의 계약.** 도우미는 `fos-home-infra` 가 만들고 운영한다. 이 저장소는 아래 계약만 갖는다.
+
+- socket 은 unix stream socket 이다. Control Plane 만 열 수 있게 둔다. 망에 열지 않는다
+- 요청 하나에 연결 하나다. Control Plane 이 UTF-8 JSON 한 줄을 `\n` 으로 끝내 보내고, 도우미가 JSON 한 줄로 답한 뒤 연결을 닫는다
+- 요청은 `{"version": 1, "owner": "u12", "path": "reports/a.csv", "max_entries": 10000}` 이다
+- 성공 답은 `{"ok": true, "kind": "FILE", "entries": 1, "bytes": 2048}` 이다. `kind` 는 목록의 `kind` 와 같은 네 값이다
+- 실패 답은 `{"ok": false, "code": "<코드>"}` 이다. 코드는 아래 표의 다섯이다
+
+| 코드 | 뜻 | Control Plane 응답 |
+| --- | --- | --- |
+| `INVALID_REQUEST` | 주인 키나 경로가 규칙에 맞지 않는다 | 502 `WORKSPACE_DELETE_FAILED` |
+| `NOT_FOUND` | 없는 경로 | 404 `WORKSPACE_ENTRY_NOT_FOUND` |
+| `LINK_IN_PATH` | 중간 조각이 링크이거나 디렉터리가 아니다 | 404 `WORKSPACE_ENTRY_NOT_FOUND` |
+| `TOO_MANY_ENTRIES` | 디렉터리 안의 항목이 `max_entries` 를 넘는다. 아무것도 지우지 않았다 | 409 `WORKSPACE_DELETE_TOO_MANY` |
+| `FAILED` | 지우다 실패했다. 일부가 지워졌을 수 있다 | 502 `WORKSPACE_DELETE_FAILED` |
+
+도우미가 지킬 것은 아래와 같다.
+
+- 주인 키는 `^[a-z][a-z0-9-]{0,63}$` 이고, 지우는 범위는 `<workspace_root>/<주인 키>` 아래뿐이다. 주인 디렉터리 자체는 지우지 않는다
+- 경로 규칙은 위 「경로 규칙」 과 같다
+- 주인 디렉터리부터 조각마다 링크를 따라가지 않고 디렉터리 핸들로 연다. 마지막 조각이 링크면 링크만 지운다
+- 디렉터리는 먼저 안의 항목을 링크를 따라가지 않고 센다. `max_entries` 를 넘으면 지우지 않고 답한다. 넘지 않으면 안쪽부터 지운다
+- 지우는 크기에는 상한을 두지 않는다. 지우는 비용은 항목 수를 따르고 파일 크기를 따르지 않는다
+- 요청마다 시각, 주인 키, 경로, 종류, 지운 항목 수, 결과를 감사 기록 한 줄로 남긴다. 파일 본문은 읽지도 남기지도 않는다
+- 실행 공간 루트만 쓰기로 붙이고, 지우는 데 필요한 권한만 갖는다
+
 ## 화면을 검증하는 방법
 
 테스트는 확인하는 대상을 나눠 둔다.
@@ -222,7 +262,6 @@ profile key 와 AI credential 은 계속 홈서버 파일에 둔다.
 
 ## 아직 만들지 않은 것
 
-- 실행 공간 파일의 지우기. 화면은 지우기 단추를 그리지 않는다
 - Hermes 안의 `delegate_task` 하위 에이전트가 자기 실행 줄을 남기는 경로.
   그 하위 에이전트는 Hermes 안에서만 돌고 사건으로만 보인다.
   우리 실행 줄이 생기는 자식은 `agent_delegate`, 흐름의 하위 실행, Memory 제안이다.
diff --git a/docs/flow.md b/docs/flow.md
index 47a4886..472f7fb 100644
--- a/docs/flow.md
+++ b/docs/flow.md
@@ -485,4 +485,28 @@ sequenceDiagram
     end
 ```
 
+```mermaid
+sequenceDiagram
+    participant B as 브라우저
+    participant C as Control Plane
+    participant H as 권한 도우미
+
+    B->>C: GET /api/v1/workspace 로 도는 실행 수를 다시 읽는다
+    B->>B: 확인 창. 도는 실행이 있으면 다시 생길 수 있다고 알린다
+    B->>C: DELETE /api/v1/workspace/entries?path=
+    C->>C: 경로 규칙, 읽기 마운트에서 있는지 확인
+    C->>H: {owner, path, max_entries} 한 줄
+    alt 지웠다
+        H-->>C: ok, kind, entries, bytes
+        C->>C: INFO 로그 한 줄
+        C-->>B: 200. 목록을 다시 읽는다
+    else 항목이 너무 많다
+        H-->>C: TOO_MANY_ENTRIES
+        C-->>B: 409. 아무것도 지우지 않았다고 알린다
+    else 도우미가 실패했거나 답하지 않았다
+        C-->>B: 502. 목록을 다시 읽어 남은 것을 보인다
+    end
+```
+
+두 탭에서 같은 것을 지우면 늦은 쪽은 404 를 받고 목록을 다시 읽는다.
 목록을 연 사이 에이전트가 파일을 바꾸면 다음 읽기에 보인다. 화면은 스스로 다시 읽지 않는다.
diff --git a/docs/frontend/structure.md b/docs/frontend/structure.md
index b666ab5..1c3687d 100644
--- a/docs/frontend/structure.md
+++ b/docs/frontend/structure.md
@@ -23,7 +23,7 @@
 | `/connections/{id}` | 커넥터 하나의 연결 화면. 머리에 아이콘, 이름, 설명, 링크를 보이고, 계정을 연결하고, 이 연결을 붙인 에이전트를 보인다 |
 | `/connections/accountbook` | 옛 주소. `/connections/{id}` 로 넘긴다 |
 | `/browser` | 내 브라우저. 만들기, 켜기, 끄기, 지우기와 자동 중지 안내, 켜져 있거나 꺼져 있을 때 로그인 화면(「화면 열기」). `?url=` 은 화면을 열 때 갈 시작 주소이고 `http`, `https` 가 아니면 무시한다. 사이드바 「고급」 의 「내 브라우저」 와 `/connections` 위쪽의 「내 브라우저」 링크로 간다([`../backend/user-browser.md`](../backend/user-browser.md)) |
-| `/files` | 파일 공간. 내 에이전트들이 함께 쓰는 실행 공간의 목록, 미리보기, 내려받기. `?path=` 는 연 디렉터리이고 `?file=` 은 미리 보는 파일이다. 사이드바 「고급」 의 「파일 공간」 으로 간다. 아래 「파일 공간」 |
+| `/files` | 파일 공간. 내 에이전트들이 함께 쓰는 실행 공간의 목록, 미리보기, 내려받기, 지우기. `?path=` 는 연 디렉터리이고 `?file=` 은 미리 보는 파일이다. 사이드바 「고급」 의 「파일 공간」 으로 간다. 아래 「파일 공간」 |
 | `/tasks` | 내 예약 작업 목록과 「새 작업」([`../backend/task.md`](../backend/task.md) 의 「화면」) |
 | `/tasks/new` | 예약 작업 만들기 |
 | `/tasks/{id}` | 예약 작업 하나. 고치기, 멈추기와 다시 켜기, 지우기, 최근 실행 |
@@ -106,7 +106,7 @@
 | 머리 | 「파일 공간」 제목, 「내 에이전트들이 함께 쓰는 공간이에요」 와 그 에이전트 이름들. 그룹에 공개한 에이전트에는 「그룹 공개」 표시를 붙이고 「다른 사람이 이 에이전트를 쓰면 그 파일도 여기 생겨요」 를 한 줄 더한다 |
 | 경로 줄 | 「파일 공간」 부터 연 디렉터리까지의 조각. 누르면 그 디렉터리로 간다 |
 | 목록 | 디렉터리를 먼저, 이름 순서. 줄마다 종류 아이콘, 이름, 크기, 바뀐 시각. 디렉터리를 누르면 연다. 파일을 누르면 미리보기를 연다. `readable` 이 거짓이면 「읽을 수 없음」, `LINK` 는 「링크」 표시를 붙이고 누르지 못한다. `openable` 이 거짓이면 「주소로 열 수 없는 이름」 을 붙이고 미리보기와 내려받기를 열지 않는다. `truncated` 면 목록 끝에 「1,000개까지만 보여요」 |
-| 줄의 동작 | 「내려받기」(`FILE` 이고 `readable` 이고 `openable` 일 때) |
+| 줄의 동작 | 「내려받기」(`FILE` 이고 `readable` 이고 `openable` 일 때), 「지우기」(상태의 `deletable` 일 때만 그린다) |
 | 미리보기 | 넓은 화면은 목록 옆 패널, 좁은 화면은 전체 폭 시트. 결과물 패널과 같은 자리 규칙이다. 머리에 이름, 「내려받기」, 「닫기」 |
 
 미리보기 종류는 화면이 확장자와 목록의 `size` 로 정하고, 상한과 형식은 Control Plane 의 「본문 머리글」 표와 같다.
@@ -127,6 +127,10 @@
 | 404 | 「찾을 수 없어요. 지워졌을 수 있어요.」 와 맨 위로 가기 |
 | 그 밖의 실패 | 「불러오지 못했어요」 와 다시 읽기 |
 
+**지우기는 확인 창을 거친다.** 창은 이름과 종류를 보이고, 디렉터리면 「안의 파일까지 모두 지워요」 를 더한다.
+창을 열 때 상태를 다시 읽어 `runningExecutions` 가 0 보다 크면 「에이전트가 지금 일하고 있어요. 쓰는 중인 파일이면 다시 생길 수 있어요.」 를 더한다.
+지운 뒤 목록을 다시 읽고, 미리 보던 파일이면 미리보기를 닫는다. 409 는 「항목이 너무 많아 지우지 않았어요. 안쪽 폴더부터 지워 주세요.」, 502 는 「지우지 못했어요. 일부만 지워졌을 수 있어요.」 다.
+
 ## 꺼진 사용자의 세션
 
 관리자가 끈 사용자가 세션을 가진 채 요청하면 Control Plane 이 401 과 `ACCESS_REVOKED` 로 답한다.
diff --git a/docs/self-hosting.md b/docs/self-hosting.md
index 23c15a5..d672e8a 100644
--- a/docs/self-hosting.md
+++ b/docs/self-hosting.md
@@ -55,6 +55,7 @@ Hermes 에 설치하는 묶음과 그때 받는 값은 [`hermes/README.md`](../h
 | `ASSISTANT_ARTIFACT_ROOT` | Backend | 에이전트가 만든 결과물 파일을 두는 디렉터리. Control Plane 이 읽고 오래된 것을 지운다. 비면 기동이 실패한다 |
 | `ASSISTANT_ARTIFACT_AGENT_ROOT` | Backend | 같은 디렉터리를 Hermes 컨테이너에서 보는 경로. 실행 입력에 적는다. 비면 기동이 실패한다 |
 | `ASSISTANT_SANDBOX_WORKSPACE_ROOT` | Backend | 셸 실행 공간 정책의 `workspace_root` 를 Control Plane 에서 본 경로. 읽기 전용으로 붙인다. 비면 「파일 공간」 을 쓸 수 없다고 보이고 기동은 한다([`code-architecture.md`](code-architecture.md) 의 「실행 공간 파일」) |
+| `ASSISTANT_SANDBOX_WORKSPACE_DELETE_SOCKET` | Backend | 실행 공간 파일을 지우는 권한 도우미의 unix socket 경로. 비면 지우기를 열지 않는다 |
 | `ASSISTANT_SKILL_ROOT` | Backend | 에이전트에 올린 스킬을 profile 별 버전 디렉터리로 두는 디렉터리. Control Plane 이 쓴다. 비면 기동이 실패한다 |
 | `ASSISTANT_SKILL_AGENT_ROOT` | Backend | 같은 디렉터리를 Hermes 컨테이너에서 읽기 전용으로 보는 경로. `skills.external_dirs` 에 적는다. 비면 기동이 실패한다 |
 | `ASSISTANT_SKILL_MAX_PER_AGENT` | Backend | 에이전트 하나에 올릴 수 있는 스킬 수. 기본 30. 새 스킬을 만들 때만 본다 |
