# ADR 목록

ADR 은 그 결정을 지키는 코드가 있는 모듈의 `docs/adr/` 에 둔다.
이 파일은 여러 모듈에 걸친 결정의 목록과, 모든 ADR 의 둘 곳과 작성 규칙을 갖는다.

| 모듈 | 목록 |
| --- | --- |
| backend | [`backend/docs/adr/INDEX.md`](../../backend/docs/adr/INDEX.md) |
| web | [`web/docs/adr/INDEX.md`](../../web/docs/adr/INDEX.md) |
| hermes | [`hermes/docs/adr/INDEX.md`](../../hermes/docs/adr/INDEX.md) |

## 둘 곳

층은 그 결정을 지키는 코드가 있는 쪽이다. `backend`, `frontend`, `hermes` 가운데 하나 이상을 적거나 `공통` 이라 적는다.

| 층 | 둘 곳 |
| --- | --- |
| `backend` 하나 | `backend/docs/adr/` |
| `frontend` 하나 | `web/docs/adr/` |
| `hermes` 하나 | `hermes/docs/adr/` |
| 둘 이상이거나 `공통` | 이 디렉터리. 아래 목록의 층 칸에 적는다 |

모듈 목록에는 층 칸을 두지 않는다. 디렉터리가 층이다.

결정 전체가 대체되거나 퇴역하면 status 를 `superseded` 나 `retired` 로 바꾸고, 같은 디렉터리의 `archive/` 로 옮겨 목록의 「보관」 표에 둔다.
일부만 대체된 ADR 은 옮기지 않는다.
코드 주석이 옮길 ADR 을 지금의 근거로 가리키면, 그 주석을 대체한 ADR 로 먼저 바꾼다.

파일을 옮겨도 파일 이름과 식별자는 바꾸지 않는다. 코드와 마이그레이션은 ADR 을 식별자로만 가리키므로 고칠 것이 없다.
ADR 끼리의 링크와 문서의 링크는 옮기는 커밋에서 함께 고친다.

## 작성과 정렬 규칙

새 ADR 은 `ADR-<YYYYMMDD>-<슬러그>.md` 로 만든다. 날짜는 결정한 날이며 `Date` 와 같다.
제목 머리는 `## ADR-YYYYMMDD / <슬러그>: <제목>` 이다. 슬러그는 내용을 나타내는 짧은 소문자 영문과 숫자, 하이픈으로 쓴다.
같은 날의 ADR 은 서로 다른 슬러그로 구분한다. 기존 `ADR-001` 부터의 파일명과 식별자는 유지하고, 제목 머리는 `## ADR-NNN: <제목>` 이다.

목록은 기존 숫자 ADR 을 번호 오름차순으로 먼저 두고, 새 ADR 은 날짜 오름차순으로 그 뒤에 둔다.
같은 날짜는 슬러그의 사전 순서로 정렬한다. 「보관」 표도 같은 순서다.
새 ADR 의 식별자 칸과 문서 안의 참조는 `[ADR-YYYYMMDD / 슬러그](ADR-YYYYMMDD-슬러그.md)` 로 쓴다.
날짜만으로 가리키지 않고 파일로 링크하여 같은 날의 결정을 구분한다. 기존 ADR 의 참조 표기는 유지한다.

`test/unit/adr-index.test.ts` 가 파일과 목록, status 와 상태 칸, 둘 곳, 정렬, 제목 머리가 맞는지 확인한다.

## 결정 목록

| 식별자 | 제목 | 층 | 상태 |
| --- | --- | --- | --- |
| [ADR-001](ADR-001-hermes를-런타임으로-두고-core를-고치지-않는다.md) | Hermes 를 런타임으로 두고 core 를 고치지 않는다 | 공통 | Accepted |
| [ADR-005](ADR-005-기존-개인-저장소의-스택을-그대로-쓴다.md) | 기존 개인 저장소의 스택을 그대로 쓴다 | 공통 | Accepted. Checkstyle 을 뺀 부분은 ADR-042 가 대체한다 |
| [ADR-017](ADR-017-무엇을-할지는-hermes-가-정하고-control-plane-은-경계만-갖는다.md) | 무엇을 할지는 Hermes 가 정하고 Control Plane 은 경계만 갖는다 | 공통 | Accepted. 결과를 모델이 다시 묻는다는 부분은 ADR-040 이, 요청자를 MCP 토큰이 정한다는 부분은 ADR-032 가 대체한다 |
| [ADR-022](ADR-022-다시-생성과-수정은-같은-session-에-판으로-쌓는다.md) | 다시 생성과 수정은 같은 session 에 판으로 쌓는다 | backend, frontend | Accepted. 수정은 ADR-024 가 대체 |
| [ADR-024](ADR-024-메시지-수정을-없애고-중지한-뒤-다시-보낸다.md) | 메시지 수정을 없애고 중지한 뒤 다시 보낸다 | backend, frontend | Accepted. ADR-022 의 수정을 대체 |
| [ADR-025](ADR-025-대화는-주소에-공개-식별자를-쓰고-번호는-안에만-둔다.md) | 대화는 주소에 공개 식별자를 쓰고 번호는 안에만 둔다 | backend, frontend | Accepted |
| [ADR-026](ADR-026-에이전트가-물을-것은-답-끝의-태그로-두고-화면이-카드로-그린다.md) | 에이전트가 물을 것은 답 끝의 태그로 두고 화면이 카드로 그린다 | backend, frontend | Accepted |
| [ADR-027](ADR-027-에이전트가-만든-html-은-대화별-폴더에-두고-스크립트-없이-보인다.md) | 에이전트가 만든 HTML 은 대화별 폴더에 두고 스크립트 없이 보인다 | backend, frontend | Accepted |
| [ADR-038](ADR-038-도구의-명령-원문은-관리자에게만-보내고-사용자에게는-사람-말로-보인다.md) | 도구의 명령 원문은 관리자에게만 보내고 사용자에게는 사람 말로 보인다 | backend, frontend | Accepted. 원문 저장과 관리자 원문 보기는 ADR-047 이 대체한다 |
| [ADR-041](ADR-041-hermes-에-설치하는-plugin-과-profile-틀은-이-저장소가-소유한다.md) | Hermes 에 설치하는 plugin 과 profile 틀은 이 저장소가 소유한다 | 공통 | Accepted |
| [ADR-042](ADR-042-코드-품질-규칙은-도구-설정이-갖고-기존-위반은-기준-파일에-둔다.md) | 코드 품질 규칙은 도구 설정이 갖고 기존 위반은 기준 파일에 둔다 | 공통 | Accepted. ADR-005 가 미룬 Checkstyle 을 넣는다 |
| [ADR-048](ADR-048-응답-중에-보낸-메시지는-control-plane-이-쌓아-두고-다음-turn-으로-합쳐-보낸다.md) | 응답 중에 보낸 메시지는 Control Plane 이 쌓아 두고 다음 turn 으로 합쳐 보낸다 | backend, frontend | Accepted. ADR-040 의 다음 turn 을 여는 자리를 함께 쓴다 |
| [ADR-057](ADR-057-문서는-사람이-화면에서-직접-쓰고-고친다.md) | 문서는 사람이 화면에서 직접 쓰고 고친다 | backend, frontend | Accepted. ADR-012 의 승인 원칙을 문서에 적용한다 |
| [ADR-059](ADR-059-꺼진-사용자는-control-plane-이-요청마다-막고-웹이-세션을-끊는다.md) | 꺼진 사용자는 Control Plane 이 요청마다 막고 웹이 세션을 끊는다 | backend, frontend | Accepted |
| [ADR-060](ADR-060-reasoning-effort-의-지원은-확인한-것만-보이고-모르면-미확인으로-둔다.md) | reasoning effort 의 지원은 Hermes 가 알린 것만 확인으로 보이고 모르면 미확인으로 둔다 | backend, frontend | Accepted. ADR-030 의 「받지 않는 effort 는 Hermes 가 맞춘다」 는 그대로 두고 지원 표시와 `none` 선택을 더한다 |
| [ADR-063](ADR-063-관리자-전용-표시와-동작은-관리자-영역에만-두고-일반-경로의-응답은-서버가-역할에-따라-줄인다.md) | 관리자 전용 표시와 동작은 관리자 영역에만 두고 일반 경로의 응답은 서버가 역할에 따라 줄인다 | backend, frontend | Accepted. ADR-038 의 「서버가 응답에서 뺀다」 를 금액과 모델과 토큰으로 넓힌다 |
| [ADR-065](ADR-065-외부로-나가는-도구는-상시-허락을-닫는-선언을-둔다.md) | 외부로 나가는 도구는 상시 허락을 닫는 선언을 둔다 | backend, hermes | Accepted. ADR-050 의 상시 허락에 도구별 선언을 더한다 |
| [ADR-069](ADR-069-사용자-전체-실행-한도는-turn-자리와-실행-줄을-사용자-잠금-하나에서-센다.md) | 사용자 전체 실행 한도는 turn 자리와 실행 줄을 사용자 잠금 하나에서 센다 | backend, frontend | Accepted |
| [ADR-070](ADR-070-알림은-control-plane-의-notification-표가-원장이고-웹은-사용자-단위-SSE-로-받는다.md) | 알림은 Control Plane 의 `notification` 표가 원장이고 웹은 사용자 단위 SSE 로 받는다 | backend, frontend | Accepted |
| [ADR-072](ADR-072-먼저-알리기의-기본값은-알리지-않음이고-control-plane-기록에서-정한-신호만-화면-안에-올린다.md) | 먼저 알리기의 기본값은 알리지 않음이고 Control Plane 기록에서 정한 신호만 화면 안에 올린다 | backend, frontend | Accepted. 매일 루프 판정을 후보 기록에 더하는 부분은 [ADR-20261008 / daily-loop](ADR-20261008-daily-loop.md) 이 정한다 |
| [ADR-073](ADR-073-할-일은-에이전트가-제안하고-사람이-받아들인-것만-챙긴다.md) | 할 일은 에이전트가 제안하고 사람이 받아들인 것만 챙긴다 | backend, frontend, hermes | Accepted |
| [ADR-075](ADR-075-결과-전달은-묶음과-시도로-남기고-사용자가-저장된-결과만-다시-전달한다.md) | 결과 전달은 묶음과 시도로 남기고 사용자가 저장된 결과만 다시 전달한다 | backend, frontend | Accepted. ADR-040 의 「turn 이 실패해도 같은 결과로 다시 깨우지 않는다」 는 그대로 두고 사용자가 요청하는 복구를 더한다 |
| [ADR-078](ADR-078-예약-작업의-결과는-실행마다-새-대화가-기본이고-목록은-작업으로-묶는다.md) | 예약 작업의 결과는 실행마다 새 대화가 기본이고 목록은 작업으로 묶는다 | backend, frontend | Accepted. 매일 깨우기에 맞춘 개정은 ADR-085 가 한다 |
| [ADR-080](ADR-080-먼저-살펴보기는-점검-대화의-turn-하나로-돌고-읽기-경계를-control-plane-이-강제한다.md) | 먼저 살펴보기는 점검 대화의 turn 하나로 돌고 읽기 경계를 Control Plane 이 강제한다 | backend, frontend | Accepted. ADR-040 의 「부모는 맡긴 뒤 기다리지 않는다」 에 살펴보기 트리의 예외를 둔다. 맡길 곳을 커넥터 에이전트로 한정한 부분은 ADR-083 이 대체한다. 매일 깨우기에 맞춘 개정은 ADR-085 가 한다 |
| [ADR-082](ADR-082-먼저-살펴보기의-쓰기-도구는-관리자가-에이전트마다-켜고-커넥터-쓰기는-승인-카드로-보낸다.md) | 먼저 살펴보기의 쓰기 도구는 관리자가 에이전트마다 켜고, 커넥터 쓰기는 승인 카드로 보낸다 | backend, frontend | Accepted. 켠 에이전트에 한해 ADR-080 의 읽기 경계를 넓힌다. 맡길 곳을 커넥터 에이전트로 한정한 부분은 ADR-083 이 대체한다. 매일 깨우기에 맞춘 개정은 ADR-085 가 한다 |
| [ADR-083](ADR-083-커넥터는-사용자가-한-번-연결하고-자기-에이전트에-여럿-붙여-그-에이전트가-도구를-직접-부른다.md) | 커넥터는 사용자가 한 번 연결하고 자기 에이전트에 여럿 붙여 그 에이전트가 도구를 직접 부른다 | 공통 | Accepted. 새 연결에 대해 ADR-039, ADR-044, ADR-045 를, 커넥터 에이전트 부분에 대해 ADR-029, ADR-049, ADR-080, ADR-082 를 대체한다. 처음 붙이기와 스킬 변경의 재시작 대기는 [ADR-20261007 / connector-live-reload](ADR-20261007-connector-live-reload.md) 가 대체한다. `single_binding` 을 선언한 커넥터의 연결이 에이전트 하나에만 붙는다는 예외는 [ADR-20261008 / connector-binding-guards](ADR-20261008-connector-binding-guards.md) 가 둔다 |
| [ADR-085](ADR-085-매일-깨우기는-예약-작업을-다시-쓰고-다섯-칸-보고를-지금-화면에-올린다.md) | 매일 깨우기는 예약 작업을 다시 쓰고 다섯 칸 보고를 지금 화면에 올린다 | backend, frontend | Accepted. ADR-074, 076, 078, 079, 080, 081, 082 의 매일 깨우기와 보고 계약을 개정한다 |
| [ADR-086](ADR-086-셸과-파일-도구는-사용자별-docker-실행-공간에서만-돈다.md) | 셸과 파일 도구는 사용자별 docker 실행 공간에서만 돈다 | backend, frontend, hermes | Accepted. 운영 정책에 등록한 profile 에만 적용한다. ADR-082 의 남는 위험을 사용자 실행 공간 안으로 줄인다. ADR-029 의 「파일 접근을 격리하지 않는다」 전제는 실행 공간이 적용된 profile 에서 바뀐다. 사진 첨부를 실행 공간에 붙이는 방식은 ADR-091 이 정한다 |
| [ADR-089](ADR-089-커넥터는-식별자-인자를-선언하고-승인-카드는-그-값을-길이로-가리지-않는다.md) | 커넥터는 식별자 인자를 선언하고 승인 카드는 그 값을 길이로 가리지 않는다 | backend, frontend, hermes | Accepted. ADR-065 의 「가려진 인자」 에 커넥터 선언으로만 받는 예외를 더한다 |
| [ADR-091](ADR-091-사진-첨부는-사용자별로-저장하고-실행-공간에는-그-사용자만-붙인다.md) | 사진 첨부는 사용자별로 저장하고 실행 공간에는 그 사용자만 붙인다 | backend, hermes | Accepted. ADR-020 의 저장 경로와 ADR-086 의 전체 첨부 mount 를 바꾼다 |
| [ADR-092](ADR-092-승인한-실행의-실패는-커넥터가-선언한-오류-코드와-복구-어휘와-정수-세부만-에이전트까지-전한다.md) | 승인한 실행의 실패는 커넥터가 선언한 오류 코드와 복구 어휘와 정수 세부만 에이전트까지 전한다 | backend, hermes | Accepted. ADR-043 의 공통 오류 어휘 옆에 커넥터가 선언한 코드와 복구 계약을 더하고 ADR-050 의 실패 결과 저장을 바꾼다 |
| [ADR-20261007 / connector-live-reload](ADR-20261007-connector-live-reload.md) | 커넥터를 붙이면 공유 gateway 의 MCP 설정 맞추기로 반영하고 Control Plane 이 두 주기 뒤 스스로 확인한다 | backend, hermes | Accepted. ADR-083 의 「붙이면 재시작 뒤 관리자 반영 완료로 READY」 를 첫 붙이기와 스킬 변경에서 대체한다 |
| [ADR-20261007 / connector-owner-attachments](ADR-20261007-connector-owner-attachments.md) | 사용자 첨부를 읽는 커넥터는 바인딩 주인의 첨부 디렉터리를 설치가 정한 env 로만 받는다 | backend, hermes | Accepted. ADR-091 의 사용자별 첨부 경계를 커넥터 MCP 서버까지 넓힌다. 확인하지 못했을 때의 붙이기 오류 코드는 [ADR-20261008 / connector-binding-guards](ADR-20261008-connector-binding-guards.md) 가 바꾼다 |
| [ADR-20261007 / memory-remember](ADR-20261007-memory-remember.md) | 사용자가 대화에서 직접 말한 사실은 에이전트가 바로 기억하고 그 밖은 제안으로 남긴다 | backend, frontend, hermes | Accepted. ADR-012 의 「사람이 받아들여야 저장된다」 와 「에이전트가 스스로 쓰는 경로를 두지 않는다」 를 개정한다. 바로 저장 조건의 근거 대조와 민감도, 오래된 대화 판정은 [ADR-20261008 / memory-remember-guard](ADR-20261008-memory-remember-guard.md) 이 개정한다. 색인에 제목만 실린다는 부분은 [ADR-20261008 / memory-facts](ADR-20261008-memory-facts.md) 이 개정한다 |
| [ADR-20261007 / naver-blog-connector](ADR-20261007-naver-blog-connector.md) | 네이버 블로그 커넥터는 사용자의 Chrome 에 CDP 로 붙고, 임시저장은 승인한 뒤 백그라운드 작업으로 돈다 | backend, hermes | Accepted. ADR-083 의 바인딩으로 블로그 전용 profile 을 대신한다. 연결 칸의 CDP 주소와 CDP 주소마다의 잠금은 [ADR-20261007 / user-browser](ADR-20261007-user-browser.md) 가 바꾼다 |
| [ADR-20261007 / numbering-scheme](ADR-20261007-numbering-scheme.md) | Flyway 는 UTC 시각 버전을 쓰고 ADR 은 결정 날짜와 슬러그로 구분한다 | 공통 | Accepted. ADR 제목 머리와 파일을 옮기지 않는다는 부분은 [ADR-20261009 / adr-per-module](ADR-20261009-adr-per-module.md) 이 개정한다 |
| [ADR-20261007 / user-browser](ADR-20261007-user-browser.md) | 사용자마다 브라우저 하나를 Control Plane 이 관리하고, 커넥터는 바인딩이 준 중계 주소로만 닿는다 | backend, frontend, hermes | Accepted. ADR-20261007 / naver-blog-connector 의 CDP 주소 연결 칸을 바꾼다. 접근 표식을 만드는 방법은 [ADR-20261008 / browser-gateway-token](../../backend/docs/adr/ADR-20261008-browser-gateway-token.md) 이 바꾼다 |
| [ADR-20261007 / value-evaluation](ADR-20261007-value-evaluation.md) | 가치 판단은 축별 근거와 재평가 입력을 남기고 행동 정책과 분리한다 | backend, hermes | Accepted. 살펴보기에서 자동 호출하지 않는다는 부분은 매일 깨우기에 한해 [ADR-20261008 / daily-loop](ADR-20261008-daily-loop.md) 이 바꾼다 |
| [ADR-20261008 / agent-memory-grants-admin](ADR-20261008-agent-memory-grants-admin.md) | 관리자가 에이전트의 Memory collection 을 한 번에 바꾸고, 바꾼 것은 누가 언제를 표에 남긴다 | backend, frontend | Accepted. ADR-053 의 「다음」 에 적은 관리 경로를 정한다 |
| [ADR-20261008 / check-finding-reaction](ADR-20261008-check-finding-reaction.md) | 살펴보기 발견의 반응은 판단 피드백 사건으로 받고, 「관심 없음」 은 digest 기간 안에서만 같은 주제를 내린다 | backend, frontend | Accepted. ADR-20261007 / decision-feedback 의 「억제 규칙을 바꾸지 않는다」 와 ADR-081 의 되풀이 규칙에 예외를 둔다 |
| [ADR-20261008 / connector-binding-guards](ADR-20261008-connector-binding-guards.md) | 커넥터는 연결 하나를 에이전트 하나에만 붙이라고, 실행 공간이 있는 profile 에만 붙이라고 선언할 수 있다 | 공통 | Accepted. ADR-083 의 「연결은 여러 에이전트에 붙는다」 에 커넥터가 고르는 예외를 둔다 |
| [ADR-20261008 / connector-card](ADR-20261008-connector-card.md) | 커넥터 아이콘은 plugin 안의 파일을 카탈로그에 실어 같은 출처의 이미지로만 그리고, 링크는 https 만 받는다 | 공통 | Accepted. ADR-043 의 `connector.json` 에 화면용 선택 칸을 더한다 |
| [ADR-20261008 / cron-to-task](ADR-20261008-cron-to-task.md) | Hermes cron 자동화는 예약 작업으로 옮기고, 결과는 assistant 안에서만 받는다 | backend, frontend | Accepted. ADR-076 의 예약 작업에 모델 단계와 「보고할 것 없음」 완료를 더한다 |
| [ADR-20261008 / daily-loop](ADR-20261008-daily-loop.md) | 매일 깨우기가 끝나면 동의한 사용자의 살펴보기 한 번에 가치 평가와 행동 정책을 한 번만 잇는다 | backend, frontend | Accepted. [ADR-20261007 / value-evaluation](ADR-20261007-value-evaluation.md) 의 「살펴보기에서 자동으로 부르지 않는다」 를 매일 깨우기에 한해 바꾸고, ADR-072 와 ADR-074 의 후보 기록과 「내 차례」 항목을 넓힌다 |
| [ADR-20261008 / default-toolsets](ADR-20261008-default-toolsets.md) | 기본 에이전트는 web 과 셸, 파일, 코드 실행을 켜고 시작하되 셸 계열은 실행 공간이 있을 때만 켠다 | 공통 | Accepted. ADR-086 의 도구 저장에 local 로 돌리지 않는 쓰기를 더한다 |
| [ADR-20261008 / memory-facts](ADR-20261008-memory-facts.md) | 짧은 개인 기억은 본문까지 「개인 사실 구역」에 싣고, 답마다 참고한 기억을 사용자에게 보인다 | backend, frontend | Accepted. ADR-20261007 / memory-remember 의 「색인에 제목이 실린다」 를 개정하고 ADR-015 에 예외를 둔다 |
| [ADR-20261008 / memory-remember-guard](ADR-20261008-memory-remember-guard.md) | 바로 저장은 모델이 다듬은 본문도 받고, 부정이 뒤집힌 글과 민감해 보이는 글과 오래된 대화만 제안으로 내린다 | backend, frontend | Accepted. ADR-20261007 / memory-remember 의 바로 저장 조건 2 와 5 를 개정한다 |
| [ADR-20261008 / read-data-flow](ADR-20261008-read-data-flow.md) | 커넥터 READ 결과는 가는 곳마다 따로 판정하고, 판정하지 못하는 길은 감수로 적는다 | 공통 | Accepted. ADR-083 과 ADR-086 의 「감당할 것」 을 흐름마다 나눠 적는다 |
| [ADR-20261008 / tool-catalog-visibility](ADR-20261008-tool-catalog-visibility.md) | 관리자는 그룹의 도구 선택 목록을 정하고 숨김은 활성 상태를 바꾸지 않는다 | backend, frontend | Accepted. ADR-029의 도구 조회와 저장에 그룹별 숨김을 더한다 |
| [ADR-20261009 / adr-per-module](ADR-20261009-adr-per-module.md) | ADR 은 지키는 코드의 모듈에 두고, 여러 모듈에 걸친 것과 작성 규칙만 루트에 둔다 | 공통 | Accepted. ADR-20261007 / numbering-scheme 의 ADR 제목 머리와 파일을 옮기지 않는다는 부분을 개정한다 |
| [ADR-20261009 / skill-package](ADR-20261009-skill-package.md) | 스킬은 zip 묶음으로도 올리고, 스크립트는 실행 공간이 있는 에이전트에만 받는다 | 공통 | Accepted. ADR-034 의 스크립트 기각을 대체한다 |
| [ADR-20261009 / tool-request-flow](ADR-20261009-tool-request-flow.md) | 도구 사용 요청은 따로 저장하고 관리자는 반영을 확인한 뒤 승인한다 | backend, frontend | Accepted. ADR-029의 관리자 도구 변경에 주인의 요청과 결정 이력을 더한다 |

## 보관

결정 전체가 대체되거나 퇴역한 ADR 이다. 파일은 `archive/` 에 있다.

| 식별자 | 제목 | 층 | 상태 |
| --- | --- | --- | --- |
| [ADR-058](archive/ADR-058-기존-개인-지식-저장소는-주인이-검토한-묶음을-화면에서-올려-들여온다.md) | 기존 개인 지식 저장소는 주인이 검토한 묶음을 화면에서 올려 들여온다 | backend, frontend | Retired. 목적지 대조 미완료. 사용자 결정으로 원본·아카이브 퇴역, 도구 제거, 기존 출처 보존 |
