# backend ADR 목록

Control Plane(`backend/`) 한 층의 코드가 지키는 결정이다.
여러 모듈에 걸친 결정과 둘 곳, 작성 규칙은 [`docs/adr/INDEX.md`](../../../docs/adr/INDEX.md) 가 갖는다.

## 결정 목록

| 식별자 | 제목 | 상태 |
| --- | --- | --- |
| [ADR-002](ADR-002-profile은-나누고-ai-계정은-가족이-함께-쓴다.md) | 사용자마다 profile 을 나누되 AI 계정은 가족이 함께 쓴다 | Accepted. profile 을 사용자마다 하나 둔다는 부분은 ADR-007 이, 금액을 계산할 수 없다는 부분은 ADR-004 가 대체한다 |
| [ADR-003](ADR-003-memory-권한은-주입으로-강제한다.md) | Memory 권한은 주입으로 강제한다 | Accepted. 토큰의 사용자를 먼저 정한다는 부분은 ADR-032 가 대체한다 |
| [ADR-004](ADR-004-구독제에서도-api-가격으로-환산해-보인다.md) | 구독제에서도 API 가격으로 환산해 보인다 | Accepted |
| [ADR-007](ADR-007-에이전트가-모델과-도구를-함께-정한다.md) | 에이전트가 모델과 도구를 함께 정한다 | Accepted. 모델 부분은 ADR-030 이 대체한다. 「결과」 의 한 줄은 ADR-018 이 바꾼다 |
| [ADR-008](ADR-008-스트리밍은-보여주기용이고-저장은-실행-결과로-한다.md) | 스트리밍은 보여주기용이고 저장은 실행 결과로 한다 | Accepted |
| [ADR-010](ADR-010-작업-영역을-제거하고-에이전트가-그-자리를-갖는다.md) | 작업 영역을 제거하고 에이전트가 그 자리를 갖는다 | Accepted |
| [ADR-011](ADR-011-실행은-시작할-때-기록하고-끝날-때-갱신한다.md) | 실행은 시작할 때 기록하고 끝날 때 갱신한다 | Accepted. 기동할 때 남은 줄을 `FAILED` 로 정리한다는 부분은 ADR-061 이 대체한다 |
| [ADR-012](ADR-012-memory-는-사람이-승인한-것만-남는다.md) | Memory 는 사람이 승인한 것만 남는다 | Accepted. 에이전트별 구분을 기각한 부분은 ADR-053 가 대체한다. 사람이 받아들여야 저장된다는 부분은 [ADR-20261007 / memory-remember](../../../docs/adr/ADR-20261007-memory-remember.md) 이 개정한다 |
| [ADR-013](ADR-013-실행-사건은-우리-모델로-정규화해-저장한다.md) | 실행 사건은 우리 모델로 정규화해 저장한다 | Accepted. 사건을 스트림에서 옮겨 적는 경로에 한 번에 받는 경로를 더하는 부분은 ADR-090 가 정한다 |
| [ADR-014](ADR-014-실제-청구액과-환산액을-나눠-적는다.md) | 실제 청구액과 환산액을 나눠 적는다 | Accepted |
| [ADR-015](ADR-015-memory-는-층을-나눠-싣는다.md) | Memory 는 층을 나눠 싣는다 | Accepted. ADR-003 의 조회 방식을 보완. `always_inject` 칸은 ADR-052 이 `retrieval` 로 넓힌다. 짧은 개인 `SEARCH` 항목의 본문을 싣는 예외는 [ADR-20261008 / memory-facts](../../../docs/adr/ADR-20261008-memory-facts.md) 이 둔다 |
| [ADR-018](ADR-018-사람을-더하는-것을-control-plane-이-끝낸다.md) | 사람을 더하는 것을 Control Plane 이 끝낸다 | Accepted. 여는 경로를 둘로 한정한다는 서술은 당시의 맥락이다. 첫 로그인의 모델 읽기는 ADR-030 이 대체한다 |
| [ADR-019](ADR-019-페르소나는-hermes-가-갖고-control-plane-은-화면만-준다.md) | 페르소나는 Hermes 가 갖고 Control Plane 은 화면만 준다 | Accepted. plugin 소유는 ADR-041 이 대체한다 |
| [ADR-020](ADR-020-사진은-공유-디렉터리에-두고-에이전트가-파일로-읽는다.md) | 사진은 공유 디렉터리에 두고 에이전트가 파일로 읽는다 | Accepted. 저장 경로는 ADR-091 이 사용자별 디렉터리로 바꾼다 |
| [ADR-021](ADR-021-중지한-답은-멈춘-자리까지-남긴다.md) | 중지한 답은 멈춘 자리까지 남긴다 | Accepted. ADR-008 에 예외를 둔다 |
| [ADR-028](ADR-028-결과물은-사용자의-대화-폴더에-mcp-도구로-쓴다.md) | 결과물은 사용자의 대화 폴더에 MCP 도구로 쓴다 | Accepted. MCP 토큰이 정한 사용자는 ADR-032 가 대체한다 |
| [ADR-029](ADR-029-에이전트-도구는-control-plane-이-등급으로-판정하고-hermes-설정-api-로-쓴다.md) | 에이전트 도구는 Control Plane 이 등급으로 판정하고 Hermes 설정 API 로 쓴다 | Accepted. ADR-007 에 더한다. 연결용 에이전트의 도구 목록은 ADR-044 와 ADR-045 가 대체했고, 그 예외 부분은 ADR-083 이 대체한다 |
| [ADR-030](ADR-030-모델과-effort-는-대화가-고르고-기본값은-hermes-profile-이-갖는다.md) | 모델과 effort 는 대화가 고르고 기본값은 Hermes profile 이 갖는다 | Accepted. ADR-007 의 모델 부분을 대체한다. 기본값을 profile 에 두는 부분은 ADR-054 가 대체한다 |
| [ADR-031](ADR-031-mcp-호출의-부모-실행은-profile-플러그인이-서명한-루트-session-으로-잇는다.md) | MCP 호출의 부모 실행은 profile 플러그인이 서명한 루트 session 으로 잇는다 | Accepted. ADR-017 의 부모 잇기를 정한다. 일부는 ADR-032 가 대체한다. 하위 에이전트 session 의 주인은 ADR-037 이 정한다 |
| [ADR-032](ADR-032-mcp-토큰은-profile-을-증명하고-실제-사용자는-부모-실행에서-정한다.md) | MCP 토큰은 profile 을 증명하고 실제 사용자는 부모 실행에서 정한다 | Accepted. ADR-003, ADR-017, ADR-028 의 요청자 판정과 ADR-031 의 일부를 대체한다. 하위 에이전트 session 의 요청자는 ADR-037 이 대체한다 |
| [ADR-033](ADR-033-사용자가-에이전트를-만들고-공개해도-만든-사람이-관리한다.md) | 사용자가 에이전트를 만들고, 공개해도 만든 사람이 관리한다 | Accepted. 연결용 에이전트의 일반 편집 권한은 ADR-039 가 대체한다 |
| [ADR-034](ADR-034-올린-스킬은-control-plane-이-버전-디렉터리에-쓰고-hermes-는-읽기만-한다.md) | 올린 스킬은 Control Plane 이 버전 디렉터리에 쓰고 Hermes 는 읽기만 한다 | Accepted |
| [ADR-035](ADR-035-대화창의-스킬-커맨드는-control-plane-이-해석해-hermes-에-넘긴다.md) | 대화창의 스킬 커맨드는 Control Plane 이 해석해 Hermes 에 넘긴다 | Accepted |
| [ADR-036](ADR-036-추천-질문은-사용자의-대화-이력으로-모델이-만들고-메모리에만-둔다.md) | 추천 질문은 사용자의 대화 이력으로 모델이 만들고 메모리에만 둔다 | Accepted |
| [ADR-037](ADR-037-hermes-하위-에이전트-session-의-주인은-만들-때-등록한-줄로-정한다.md) | Hermes 하위 에이전트 session 의 주인은 만들 때 등록한 줄로 정한다 | Accepted. ADR-031, ADR-032 의 하위 에이전트 session 판정을 대체한다 |
| [ADR-039](ADR-039-외부-서비스-연결은-사용자별-전용-에이전트로-실행한다.md) | 외부 서비스 연결은 사용자별 전용 에이전트로 실행한다 | Accepted. ADR-033의 일반 편집 권한에 연결용 에이전트 예외를 둔다. 전용 profile 과 에이전트, 도구 차단, 토큰 저장 부분은 새 연결에 대해 ADR-083 이 대체한다 |
| [ADR-040](ADR-040-위임-결과는-control-plane-이-부모-대화의-다음-turn-을-열어-전한다.md) | 위임 결과는 Control Plane 이 부모 대화의 다음 turn 을 열어 전한다 | Accepted. ADR-017 의 「결과는 모델이 다시 묻는다」 를 대체한다 |
| [ADR-043](ADR-043-커넥터는-plugin-의-connector-json-으로-선언하고-control-plane-은-범용-흐름만-갖는다.md) | 커넥터는 plugin 의 connector.json 으로 선언하고 Control Plane 은 범용 흐름만 갖는다 | Accepted. ADR-039 위에 얹는다. 서비스 오류 코드를 공통 어휘 옆에 커넥터 코드로 전하는 부분은 ADR-092 가 더한다 |
| [ADR-044](ADR-044-커넥터-manifest-는-읽기-전용-이미지-도구만-열-수-있다.md) | 커넥터 manifest 는 읽기 전용 이미지 도구만 열 수 있다 | Accepted. ADR-039 의 도구 차단에 예외 하나를 둔다. `toolsets` 와 `attachments` 부분은 새 연결에 대해 ADR-083 이 대체한다 |
| [ADR-045](ADR-045-커넥터-에이전트는-자기-mcp-서버만-받고-memory-와-control-plane-도구를-받지-않는다.md) | 커넥터 에이전트는 자기 MCP 서버만 받고 Memory 와 Control Plane 도구를 받지 않는다 | Accepted. ADR-039 의 전용 에이전트에 경계를 더한다. 남은 옛 커넥터 에이전트에만 걸린다. worker 경계 부분은 새 연결에 대해 ADR-083 이 대체한다 |
| [ADR-046](ADR-046-운영-비밀은-operator-env-와-다른-칸으로-선언하고-자식-mcp-프로세스에만-넣는다.md) | 운영 비밀은 operator_env 와 다른 칸으로 선언하고 자식 MCP 프로세스에만 넣는다 | Accepted. ADR-043 의 manifest 에 칸을 더한다. 지금은 그 칸을 거절한다 |
| [ADR-047](ADR-047-도구-내용은-비밀값과-UUID를-가린-뒤-중계하고-저장한다.md) | 도구 내용은 비밀값과 UUID를 가린 뒤 중계하고 저장한다 | Accepted. ADR-038의 관리자 원문 보기를 가린 값으로 바꾼다 |
| [ADR-049](ADR-049-커넥터-도구-호출은-profile-plugin-의-hook-이-control-plane-에-물어-판정한다.md) | 커넥터 도구 호출은 profile plugin 의 hook 이 Control Plane 에 물어 판정한다 | Accepted. ADR-043 의 manifest 에 도구 정책을 더한다. 연결용 profile 의 코드 실행 경로 부분은 ADR-083 이 대체한다 |
| [ADR-050](ADR-050-커넥터-쓰기는-control-plane-이-승인-줄을-저장하고-승인한-인자로-한-번만-실행한다.md) | 커넥터 쓰기는 Control Plane 이 승인 줄을 저장하고 승인한 인자로 한 번만 실행한다 | Accepted. ADR-040 의 깨우기를 승인 결과로 넓힌다. 승인한 실행이 실패했을 때 저장하고 전하는 결과는 ADR-092 가 정한다 |
| [ADR-052](ADR-052-memory-는-collection-종류-꺼내는-방식-민감도-판-출처를-가진다.md) | Memory 는 collection, 종류, 꺼내는 방식, 민감도, 판, 출처를 가진다 | Accepted. ADR-015 의 `always_inject` 를 `retrieval` 로 넓힌다. 민감 본문은 평문이라는 부분은 ADR-055 가 대체한다 |
| [ADR-053](ADR-053-에이전트는-허용된-collection-의-memory-만-받는다.md) | 에이전트는 허용된 collection 의 Memory 만 받는다 | Accepted. ADR-012 가 기각한 에이전트별 구분을 뒤집는다 |
| [ADR-054](ADR-054-에이전트-기본-모델과-모델-숨김은-control-plane-db-가-갖는다.md) | 에이전트 기본 모델과 모델 숨김은 Control Plane DB 가 갖는다 | Accepted. ADR-030 의 「기본값은 Hermes profile 이 갖는다」 를 대체한다 |
| [ADR-055](ADR-055-민감-memory-본문은-저장할-때-암호화하고-key-는-환경-변수로-받는다.md) | 민감 Memory 본문은 저장할 때 암호화하고 key 는 환경 변수로 받는다 | Accepted. ADR-052 의 「민감 본문은 평문」 부분을 대체한다 |
| [ADR-056](ADR-056-다른-서비스는-사용자에-묶인-서비스-토큰으로-문서를-읽기만-한다.md) | 다른 서비스는 사용자에 묶인 서비스 토큰으로 문서를 읽기만 한다 | Accepted. ADR-053 이 미룬 서비스 토큰을 정한다 |
| [ADR-061](ADR-061-재기동-때-남은-실행은-hermes-에-물어-정하고-도는-실행에는-다시-붙는다.md) | 재기동 때 남은 실행은 Hermes 에 물어 정하고 도는 실행에는 다시 붙는다 | Accepted. ADR-011 의 기동 정리 부분을 대체한다 |
| [ADR-062](ADR-062-native-하위-에이전트-사용량은-재조회-작업-줄을-원장으로-넓혀-합계에-더한다.md) | native 하위 에이전트 사용량은 재조회 작업 줄을 원장으로 넓혀 합계에 더한다 | Accepted. ADR-016 이 「자식 토큰을 잃는다」 고 한 부분을 native 자식에 한해 메운다. 자식 provider 를 읽는 길은 ADR-067 이 대체한다 |
| [ADR-067](ADR-067-native-하위-에이전트의-provider-는-대시보드-plugin-이-session-저장소에서-읽어-준다.md) | native 하위 에이전트의 provider 는 대시보드 plugin 이 session 저장소에서 읽어 준다 | Accepted. ADR-062 의 「session 응답이 provider 를 주지 않는 동안 native 자식은 모두 가격 미확인」 을 메운다 |
| [ADR-068](ADR-068-최상위-패키지는-한-방향-층-순서를-따르고-거꾸로-가는-의존은-port-나-이동으로-끊는다.md) | 최상위 패키지는 한 방향 층 순서를 따르고 거꾸로 가는 의존은 port 나 이동으로 끊는다 | Accepted |
| [ADR-071](ADR-071-여러-출처의-문맥은-항목마다-출처와-권한과-신선도를-지닌-묶음으로-조립한다.md) | 여러 출처의 문맥은 항목마다 출처와 권한과 신선도를 지닌 묶음으로 조립한다 | Accepted |
| [ADR-076](ADR-076-예약-작업은-control-plane-이-갖고-발화한-실행은-대화-turn-경로로-돈다.md) | 예약 작업은 Control Plane 이 갖고 발화한 실행은 대화 turn 경로로 돈다 | Accepted. 매일 깨우기에 맞춘 개정은 ADR-085 가 한다 |
| [ADR-077](ADR-077-발화는-trigger-와-예정-시각의-유일-제약으로-한-번만-만들고-놓친-발화는-작업마다-정한다.md) | 발화는 trigger 와 예정 시각의 유일 제약으로 한 번만 만들고 놓친 발화는 작업마다 정한다 | Accepted |
| [ADR-079](ADR-079-예약-작업은-사용자당-10개-최소-간격-15분-하루-48번으로-제한한다.md) | 예약 작업은 사용자당 10개, 최소 간격 15분, 하루 48번으로 제한한다 | Accepted. 매일 깨우기에 맞춘 개정은 ADR-085 가 한다 |
| [ADR-081](ADR-081-살펴보기-결과는-답-끝의-구조화-블록으로-받고-control-plane-이-검사해-그린다.md) | 살펴보기 결과는 답 끝의 구조화 블록으로 받고 Control Plane 이 검사해 그린다 | Accepted. ADR-009 의 신뢰하지 않는 글 원칙을 살펴보기 결과에 적용한다. 「관심 없음」 주제를 digest 기간 동안 원문과 changeSinceLast 에 관계없이 내리는 예외는 [ADR-20261008 / check-finding-reaction](../../../docs/adr/ADR-20261008-check-finding-reaction.md) 이 둔다. 매일 깨우기에 맞춘 개정은 ADR-085 가 한다 |
| [ADR-090](ADR-090-한-번에-받는-경로도-hermes-사건-스트림을-열어-도구-사건을-남긴다.md) | 한 번에 받는 경로도 Hermes 사건 스트림을 열어 도구 사건을 남긴다 | Accepted. ADR-013 이 사건을 옮겨 적는 경로에 한 번에 받는 경로를 더한다 |
| [ADR-093](ADR-093-문제-찾기는-살펴보기-결과의-문제-후보로-받고-control-plane-이-근거와-중복을-결정적으로-검사한다.md) | 문제 찾기는 살펴보기 결과의 문제 후보로 받고 Control Plane 이 근거와 중복을 결정적으로 검사한다 | Accepted. ADR-081 의 결과 블록을 버전 3으로 넓힌다 |
| [ADR-20261007 / autonomy-policy](ADR-20261007-autonomy-policy.md) | 행동 수준은 Control Plane 의 결정적 규칙이 정하고 첫 자동 실행은 읽기 전용 살펴보기 한 번이다 | Accepted. `SURFACE` 와 `ASK_APPROVAL` 의 사용자 화면을 만들지 않는다는 부분은 매일 루프 판정에 한해 [ADR-20261008 / daily-loop](../../../docs/adr/ADR-20261008-daily-loop.md) 이 바꾼다 |
| [ADR-20261007 / background-tasks](ADR-20261007-background-tasks.md) | 운영의 백그라운드 작업은 한 빈으로 띄우고, 검사는 끝날 때 모두 join 한다 | Accepted |
| [ADR-20261007 / decision-feedback](ADR-20261007-decision-feedback.md) | 판단 피드백은 제안 열쇠에 덧붙이는 사건으로 남기고 반응은 읽을 때 정한다 | Accepted. 살펴보기 발견의 「관심 없음」 이 되풀이 판정을 바꾸는 예외는 [ADR-20261008 / check-finding-reaction](../../../docs/adr/ADR-20261008-check-finding-reaction.md) 이 둔다. 매일 루프 판정이 화면에 보이고 그 반응이 지금 화면 항목을 빼는 부분은 [ADR-20261008 / daily-loop](../../../docs/adr/ADR-20261008-daily-loop.md) 이 바꾼다 |
| [ADR-20261007 / live-properties](ADR-20261007-live-properties.md) | 운영 코드는 실행 중에 쓰는 설정을 LiveProperties 로 읽는다 | Accepted |
| [ADR-20261007 / proactive-eval](ADR-20261007-proactive-eval.md) | 먼저 살펴보기 루프는 결정적 provider 로 실제 서비스를 replay 해 측정하고 안전 경계만 CI 를 막는다 | Accepted. 매일 루프 판정이 화면에 보이게 된 부분은 [ADR-20261008 / daily-loop](../../../docs/adr/ADR-20261008-daily-loop.md) 이 바꾼다 |
| [ADR-20261007 / test-context-base](ADR-20261007-test-context-base.md) | backend 통합 검사는 Spring 컨텍스트 하나를 함께 쓰고, 설정과 대역은 검사마다 바꿔 끼운다 | Accepted |
| [ADR-20261008 / browser-gateway-token](ADR-20261008-browser-gateway-token.md) | 브라우저 중계의 접근 표식은 바인딩 번호에서 HMAC 으로 만들고 표에 두지 않는다 | Accepted. [ADR-20261007 / user-browser](../../../docs/adr/ADR-20261007-user-browser.md) 의 무작위 표식과 해시 표를 바꾼다 |
| [ADR-20261008 / conversation-purge](ADR-20261008-conversation-purge.md) | 지운 대화는 정리 작업이 본문과 Hermes session 까지 지우고, 대화 줄과 실행 줄은 본문 없이 남긴다 | Accepted. `docs/backend/schema/README.md` 의 「대화를 지워도 메시지와 Hermes session 은 남긴다」 를 바꾼다 |

## 보관

결정 전체가 대체되거나 퇴역한 ADR 이다. 파일은 `archive/` 에 있다.

| 식별자 | 제목 | 상태 |
| --- | --- | --- |
| [ADR-006](archive/ADR-006-작업-영역은-읽기-전용-의존이고-공개-범위는-기본값이-없다.md) | 작업 영역은 읽기 전용 의존이고 공개 범위는 기본값이 없다 | Superseded ([ADR-010](ADR-010-작업-영역을-제거하고-에이전트가-그-자리를-갖는다.md)) |
| [ADR-016](archive/ADR-016-다중-에이전트-조율은-control-plane이-맡는다.md) | 다중 에이전트 조율은 Control Plane이 맡는다 | Superseded ([ADR-017](../../../docs/adr/ADR-017-무엇을-할지는-hermes-가-정하고-control-plane-은-경계만-갖는다.md)) |
