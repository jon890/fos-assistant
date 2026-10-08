# ADR 목록

층은 그 결정을 지키는 코드가 있는 쪽이다. 공통은 두 층과 `hermes/` 에 함께 걸린다.

## 작성과 정렬 규칙

새 ADR 은 `ADR-<YYYYMMDD>-<슬러그>.md` 로 만든다. 날짜는 결정한 날이며 `Date` 와 같다.
제목 머리는 `## ADR-YYYYMMDD: <제목>` 이다. 슬러그는 내용을 나타내는 짧은 소문자 영문과 숫자, 하이픈으로 쓴다.
같은 날의 ADR 은 서로 다른 슬러그로 구분한다. 기존 `ADR-001` 부터의 파일명과 식별자는 유지한다.

목록은 기존 숫자 ADR 을 번호 오름차순으로 먼저 두고, 새 ADR 은 날짜 오름차순으로 그 뒤에 둔다.
같은 날짜는 슬러그의 사전 순서로 정렬한다.
새 ADR 의 식별자 칸과 문서 안의 참조는 `[ADR-YYYYMMDD / 슬러그](ADR-YYYYMMDD-슬러그.md)` 로 쓴다.
날짜만으로 가리키지 않고 파일로 링크하여 같은 날의 결정을 구분한다. 기존 ADR 의 참조 표기는 유지한다.

## 결정 목록

| 식별자 | 제목 | 층 | 상태 |
| --- | --- | --- | --- |
| [ADR-001](ADR-001-hermes를-런타임으로-두고-core를-고치지-않는다.md) | Hermes 를 런타임으로 두고 core 를 고치지 않는다 | 공통 | Accepted |
| [ADR-002](ADR-002-profile은-나누고-ai-계정은-가족이-함께-쓴다.md) | 사용자마다 profile 을 나누되 AI 계정은 가족이 함께 쓴다 | backend | Accepted. profile 을 사용자마다 하나 둔다는 부분은 ADR-007 이, 금액을 계산할 수 없다는 부분은 ADR-004 가 대체한다 |
| [ADR-003](ADR-003-memory-권한은-주입으로-강제한다.md) | Memory 권한은 주입으로 강제한다 | backend | Accepted. 토큰의 사용자를 먼저 정한다는 부분은 ADR-032 가 대체한다 |
| [ADR-004](ADR-004-구독제에서도-api-가격으로-환산해-보인다.md) | 구독제에서도 API 가격으로 환산해 보인다 | backend | Accepted |
| [ADR-005](ADR-005-기존-개인-저장소의-스택을-그대로-쓴다.md) | 기존 개인 저장소의 스택을 그대로 쓴다 | 공통 | Accepted. Checkstyle 을 뺀 부분은 ADR-042 가 대체한다 |
| [ADR-006](ADR-006-작업-영역은-읽기-전용-의존이고-공개-범위는-기본값이-없다.md) | 작업 영역은 읽기 전용 의존이고 공개 범위는 기본값이 없다 | backend | Superseded ([ADR-010](ADR-010-작업-영역을-제거하고-에이전트가-그-자리를-갖는다.md)) |
| [ADR-007](ADR-007-에이전트가-모델과-도구를-함께-정한다.md) | 에이전트가 모델과 도구를 함께 정한다 | backend | Accepted. 모델 부분은 ADR-030 이 대체한다 |
| [ADR-008](ADR-008-스트리밍은-보여주기용이고-저장은-실행-결과로-한다.md) | 스트리밍은 보여주기용이고 저장은 실행 결과로 한다 | backend | Accepted |
| [ADR-009](ADR-009-에이전트의-답은-신뢰하지-않는-글로-그린다.md) | 에이전트의 답은 신뢰하지 않는 글로 그린다 | frontend | Accepted |
| [ADR-010](ADR-010-작업-영역을-제거하고-에이전트가-그-자리를-갖는다.md) | 작업 영역을 제거하고 에이전트가 그 자리를 갖는다 | backend | Accepted |
| [ADR-011](ADR-011-실행은-시작할-때-기록하고-끝날-때-갱신한다.md) | 실행은 시작할 때 기록하고 끝날 때 갱신한다 | backend | Accepted. 기동할 때 남은 줄을 `FAILED` 로 정리한다는 부분은 ADR-061 이 대체한다 |
| [ADR-012](ADR-012-memory-는-사람이-승인한-것만-남는다.md) | Memory 는 사람이 승인한 것만 남는다 | backend | Accepted. 에이전트별 구분을 기각한 부분은 ADR-053 가 대체한다. 사람이 받아들여야 저장된다는 부분은 [ADR-20261007 / memory-remember](ADR-20261007-memory-remember.md) 이 개정한다 |
| [ADR-013](ADR-013-실행-사건은-우리-모델로-정규화해-저장한다.md) | 실행 사건은 우리 모델로 정규화해 저장한다 | backend | Accepted. 사건을 스트림에서 옮겨 적는 경로에 한 번에 받는 경로를 더하는 부분은 ADR-090 가 정한다 |
| [ADR-014](ADR-014-실제-청구액과-환산액을-나눠-적는다.md) | 실제 청구액과 환산액을 나눠 적는다 | backend | Accepted |
| [ADR-015](ADR-015-memory-는-층을-나눠-싣는다.md) | Memory 는 층을 나눠 싣는다 | backend | Accepted. ADR-003 의 조회 방식을 보완. `always_inject` 칸은 ADR-052 이 `retrieval` 로 넓힌다. 짧은 개인 `SEARCH` 항목의 본문을 싣는 예외는 [ADR-20261008 / memory-facts](ADR-20261008-memory-facts.md) 이 둔다 |
| [ADR-016](ADR-016-다중-에이전트-조율은-control-plane이-맡는다.md) | 다중 에이전트 조율은 Control Plane이 맡는다 | backend | Superseded ([ADR-017](ADR-017-무엇을-할지는-hermes-가-정하고-control-plane-은-경계만-갖는다.md)) |
| [ADR-017](ADR-017-무엇을-할지는-hermes-가-정하고-control-plane-은-경계만-갖는다.md) | 무엇을 할지는 Hermes 가 정하고 Control Plane 은 경계만 갖는다 | 공통 | Accepted. 결과를 모델이 다시 묻는다는 부분은 ADR-040 이, 요청자를 MCP 토큰이 정한다는 부분은 ADR-032 가 대체한다 |
| [ADR-018](ADR-018-사람을-더하는-것을-control-plane-이-끝낸다.md) | 사람을 더하는 것을 Control Plane 이 끝낸다 | backend | Accepted. 여는 경로를 둘로 한정한다는 서술은 당시의 맥락이다. 첫 로그인의 모델 읽기는 ADR-030 이 대체한다 |
| [ADR-019](ADR-019-페르소나는-hermes-가-갖고-control-plane-은-화면만-준다.md) | 페르소나는 Hermes 가 갖고 Control Plane 은 화면만 준다 | backend | Accepted. plugin 소유는 ADR-041 이 대체한다 |
| [ADR-020](ADR-020-사진은-공유-디렉터리에-두고-에이전트가-파일로-읽는다.md) | 사진은 공유 디렉터리에 두고 에이전트가 파일로 읽는다 | backend | Accepted |
| [ADR-021](ADR-021-중지한-답은-멈춘-자리까지-남긴다.md) | 중지한 답은 멈춘 자리까지 남긴다 | backend | Accepted. ADR-008 에 예외를 둔다 |
| [ADR-022](ADR-022-다시-생성과-수정은-같은-session-에-판으로-쌓는다.md) | 다시 생성과 수정은 같은 session 에 판으로 쌓는다 | backend, frontend | Accepted. 수정은 ADR-024 가 대체 |
| [ADR-023](ADR-023-화면-부품은-shadcn-ui-를-저장소에-복사해-쓴다.md) | 화면 부품은 shadcn/ui 를 저장소에 복사해 쓴다 | frontend | Accepted. 색 값을 그대로 둔다는 부분은 ADR-051 이 대체한다 |
| [ADR-024](ADR-024-메시지-수정을-없애고-중지한-뒤-다시-보낸다.md) | 메시지 수정을 없애고 중지한 뒤 다시 보낸다 | backend, frontend | Accepted. ADR-022 의 수정을 대체 |
| [ADR-025](ADR-025-대화는-주소에-공개-식별자를-쓰고-번호는-안에만-둔다.md) | 대화는 주소에 공개 식별자를 쓰고 번호는 안에만 둔다 | backend, frontend | Accepted |
| [ADR-026](ADR-026-에이전트가-물을-것은-답-끝의-태그로-두고-화면이-카드로-그린다.md) | 에이전트가 물을 것은 답 끝의 태그로 두고 화면이 카드로 그린다 | backend, frontend | Accepted |
| [ADR-027](ADR-027-에이전트가-만든-html-은-대화별-폴더에-두고-스크립트-없이-보인다.md) | 에이전트가 만든 HTML 은 대화별 폴더에 두고 스크립트 없이 보인다 | backend, frontend | Accepted |
| [ADR-028](ADR-028-결과물은-사용자의-대화-폴더에-mcp-도구로-쓴다.md) | 결과물은 사용자의 대화 폴더에 MCP 도구로 쓴다 | backend | Accepted. MCP 토큰이 정한 사용자는 ADR-032 가 대체한다 |
| [ADR-029](ADR-029-에이전트-도구는-control-plane-이-등급으로-판정하고-hermes-설정-api-로-쓴다.md) | 에이전트 도구는 Control Plane 이 등급으로 판정하고 Hermes 설정 API 로 쓴다 | backend | Accepted. ADR-007 에 더한다. 연결용 에이전트의 도구 목록은 ADR-044 와 ADR-045 가 대체했고, 그 예외 부분은 ADR-083 이 대체한다 |
| [ADR-030](ADR-030-모델과-effort-는-대화가-고르고-기본값은-hermes-profile-이-갖는다.md) | 모델과 effort 는 대화가 고르고 기본값은 Hermes profile 이 갖는다 | backend | Accepted. ADR-007 의 모델 부분을 대체한다. 기본값을 profile 에 두는 부분은 ADR-054 가 대체한다 |
| [ADR-031](ADR-031-mcp-호출의-부모-실행은-profile-플러그인이-서명한-루트-session-으로-잇는다.md) | MCP 호출의 부모 실행은 profile 플러그인이 서명한 루트 session 으로 잇는다 | backend | Accepted. ADR-017 의 부모 잇기를 정한다. 일부는 ADR-032 가 대체한다 |
| [ADR-032](ADR-032-mcp-토큰은-profile-을-증명하고-실제-사용자는-부모-실행에서-정한다.md) | MCP 토큰은 profile 을 증명하고 실제 사용자는 부모 실행에서 정한다 | backend | Accepted. ADR-003, ADR-017, ADR-028 의 요청자 판정과 ADR-031 의 일부를 대체한다. 하위 에이전트 session 의 요청자는 ADR-037 이 대체한다 |
| [ADR-033](ADR-033-사용자가-에이전트를-만들고-공개해도-만든-사람이-관리한다.md) | 사용자가 에이전트를 만들고, 공개해도 만든 사람이 관리한다 | backend | Accepted. 연결용 에이전트의 일반 편집 권한은 ADR-039 가 대체한다 |
| [ADR-034](ADR-034-올린-스킬은-control-plane-이-버전-디렉터리에-쓰고-hermes-는-읽기만-한다.md) | 올린 스킬은 Control Plane 이 버전 디렉터리에 쓰고 Hermes 는 읽기만 한다 | backend | Accepted |
| [ADR-035](ADR-035-대화창의-스킬-커맨드는-control-plane-이-해석해-hermes-에-넘긴다.md) | 대화창의 스킬 커맨드는 Control Plane 이 해석해 Hermes 에 넘긴다 | backend | Accepted |
| [ADR-036](ADR-036-추천-질문은-사용자의-대화-이력으로-모델이-만들고-메모리에만-둔다.md) | 추천 질문은 사용자의 대화 이력으로 모델이 만들고 메모리에만 둔다 | backend | Accepted |
| [ADR-037](ADR-037-hermes-하위-에이전트-session-의-주인은-만들-때-등록한-줄로-정한다.md) | Hermes 하위 에이전트 session 의 주인은 만들 때 등록한 줄로 정한다 | backend | Accepted. ADR-031, ADR-032 의 하위 에이전트 session 판정을 대체한다 |
| [ADR-038](ADR-038-도구의-명령-원문은-관리자에게만-보내고-사용자에게는-사람-말로-보인다.md) | 도구의 명령 원문은 관리자에게만 보내고 사용자에게는 사람 말로 보인다 | backend, frontend | Accepted. 원문 저장과 관리자 원문 보기는 ADR-047 이 대체한다 |
| [ADR-039](ADR-039-외부-서비스-연결은-사용자별-전용-에이전트로-실행한다.md) | 외부 서비스 연결은 사용자별 전용 에이전트로 실행한다 | backend | Accepted. ADR-033의 일반 편집 권한에 연결용 에이전트 예외를 둔다. 전용 profile 과 에이전트, 도구 차단, 토큰 저장 부분은 새 연결에 대해 ADR-083 이 대체한다 |
| [ADR-040](ADR-040-위임-결과는-control-plane-이-부모-대화의-다음-turn-을-열어-전한다.md) | 위임 결과는 Control Plane 이 부모 대화의 다음 turn 을 열어 전한다 | backend | Accepted. ADR-017 의 「결과는 모델이 다시 묻는다」 를 대체한다 |
| [ADR-041](ADR-041-hermes-에-설치하는-plugin-과-profile-틀은-이-저장소가-소유한다.md) | Hermes 에 설치하는 plugin 과 profile 틀은 이 저장소가 소유한다 | 공통 | Accepted |
| [ADR-042](ADR-042-코드-품질-규칙은-도구-설정이-갖고-기존-위반은-기준-파일에-둔다.md) | 코드 품질 규칙은 도구 설정이 갖고 기존 위반은 기준 파일에 둔다 | 공통 | Accepted. ADR-005 가 미룬 Checkstyle 을 넣는다 |
| [ADR-043](ADR-043-커넥터는-plugin-의-connector-json-으로-선언하고-control-plane-은-범용-흐름만-갖는다.md) | 커넥터는 plugin 의 connector.json 으로 선언하고 Control Plane 은 범용 흐름만 갖는다 | backend | Accepted. ADR-039 위에 얹는다 |
| [ADR-044](ADR-044-커넥터-manifest-는-읽기-전용-이미지-도구만-열-수-있다.md) | 커넥터 manifest 는 읽기 전용 이미지 도구만 열 수 있다 | backend | Accepted. ADR-039 의 도구 차단에 예외 하나를 둔다. `toolsets` 와 `attachments` 부분은 새 연결에 대해 ADR-083 이 대체한다 |
| [ADR-045](ADR-045-커넥터-에이전트는-자기-mcp-서버만-받고-memory-와-control-plane-도구를-받지-않는다.md) | 커넥터 에이전트는 자기 MCP 서버만 받고 Memory 와 Control Plane 도구를 받지 않는다 | backend | Accepted. ADR-039 의 전용 에이전트에 경계를 더한다. 남은 옛 커넥터 에이전트에만 걸린다. worker 경계 부분은 새 연결에 대해 ADR-083 이 대체한다 |
| [ADR-046](ADR-046-운영-비밀은-operator-env-와-다른-칸으로-선언하고-자식-mcp-프로세스에만-넣는다.md) | 운영 비밀은 operator_env 와 다른 칸으로 선언하고 자식 MCP 프로세스에만 넣는다 | backend | Accepted. ADR-043 의 manifest 에 칸을 더한다. 지금은 그 칸을 거절한다 |
| [ADR-047](ADR-047-도구-내용은-비밀값과-UUID를-가린-뒤-중계하고-저장한다.md) | 도구 내용은 비밀값과 UUID를 가린 뒤 중계하고 저장한다 | backend | Accepted. ADR-038의 관리자 원문 보기를 가린 값으로 바꾼다 |
| [ADR-048](ADR-048-응답-중에-보낸-메시지는-control-plane-이-쌓아-두고-다음-turn-으로-합쳐-보낸다.md) | 응답 중에 보낸 메시지는 Control Plane 이 쌓아 두고 다음 turn 으로 합쳐 보낸다 | backend, frontend | Accepted. ADR-040 의 다음 turn 을 여는 자리를 함께 쓴다 |
| [ADR-049](ADR-049-커넥터-도구-호출은-profile-plugin-의-hook-이-control-plane-에-물어-판정한다.md) | 커넥터 도구 호출은 profile plugin 의 hook 이 Control Plane 에 물어 판정한다 | backend | Accepted. ADR-043 의 manifest 에 도구 정책을 더한다. 연결용 profile 의 코드 실행 경로 부분은 ADR-083 이 대체한다 |
| [ADR-050](ADR-050-커넥터-쓰기는-control-plane-이-승인-줄을-저장하고-승인한-인자로-한-번만-실행한다.md) | 커넥터 쓰기는 Control Plane 이 승인 줄을 저장하고 승인한 인자로 한 번만 실행한다 | backend | Accepted. ADR-040 의 깨우기를 승인 결과로 넓힌다 |
| [ADR-051](ADR-051-화면-색은-새벽-보라로-바꾸고-강조-색은-누를-것과-고른-것과-초점에만-쓴다.md) | 화면 색은 새벽 보라로 바꾸고 강조 색은 누를 것과 고른 것과 초점에만 쓴다 | frontend | Accepted. ADR-023 의 색 값 부분을 대체한다 |
| [ADR-052](ADR-052-memory-는-collection-종류-꺼내는-방식-민감도-판-출처를-가진다.md) | Memory 는 collection, 종류, 꺼내는 방식, 민감도, 판, 출처를 가진다 | backend | Accepted. ADR-015 의 `always_inject` 를 `retrieval` 로 넓힌다. 민감 본문은 평문이라는 부분은 ADR-055 가 대체한다 |
| [ADR-053](ADR-053-에이전트는-허용된-collection-의-memory-만-받는다.md) | 에이전트는 허용된 collection 의 Memory 만 받는다 | backend | Accepted. ADR-012 가 기각한 에이전트별 구분을 뒤집는다 |
| [ADR-054](ADR-054-에이전트-기본-모델과-모델-숨김은-control-plane-db-가-갖는다.md) | 에이전트 기본 모델과 모델 숨김은 Control Plane DB 가 갖는다 | backend | Accepted. ADR-030 의 「기본값은 Hermes profile 이 갖는다」 를 대체한다 |
| [ADR-055](ADR-055-민감-memory-본문은-저장할-때-암호화하고-key-는-환경-변수로-받는다.md) | 민감 Memory 본문은 저장할 때 암호화하고 key 는 환경 변수로 받는다 | backend | Accepted. ADR-052 의 「민감 본문은 평문」 부분을 대체한다 |
| [ADR-056](ADR-056-다른-서비스는-사용자에-묶인-서비스-토큰으로-문서를-읽기만-한다.md) | 다른 서비스는 사용자에 묶인 서비스 토큰으로 문서를 읽기만 한다 | backend | Accepted. ADR-053 이 미룬 서비스 토큰을 정한다 |
| [ADR-057](ADR-057-문서는-사람이-화면에서-직접-쓰고-고친다.md) | 문서는 사람이 화면에서 직접 쓰고 고친다 | backend, frontend | Accepted. ADR-012 의 승인 원칙을 문서에 적용한다 |
| [ADR-058](ADR-058-기존-개인-지식-저장소는-주인이-검토한-묶음을-화면에서-올려-들여온다.md) | 기존 개인 지식 저장소는 주인이 검토한 묶음을 화면에서 올려 들여온다 | backend, frontend | Retired. 목적지 대조 미완료. 사용자 결정으로 원본·아카이브 퇴역, 도구 제거, 기존 출처 보존 |
| [ADR-059](ADR-059-꺼진-사용자는-control-plane-이-요청마다-막고-웹이-세션을-끊는다.md) | 꺼진 사용자는 Control Plane 이 요청마다 막고 웹이 세션을 끊는다 | backend, frontend | Accepted |
| [ADR-060](ADR-060-reasoning-effort-의-지원은-확인한-것만-보이고-모르면-미확인으로-둔다.md) | reasoning effort 의 지원은 Hermes 가 알린 것만 확인으로 보이고 모르면 미확인으로 둔다 | backend, frontend | Accepted. ADR-030 의 「받지 않는 effort 는 Hermes 가 맞춘다」 는 그대로 두고 지원 표시와 `none` 선택을 더한다 |
| [ADR-061](ADR-061-재기동-때-남은-실행은-hermes-에-물어-정하고-도는-실행에는-다시-붙는다.md) | 재기동 때 남은 실행은 Hermes 에 물어 정하고 도는 실행에는 다시 붙는다 | backend | Accepted. ADR-011 의 기동 정리 부분을 대체한다 |
| [ADR-062](ADR-062-native-하위-에이전트-사용량은-재조회-작업-줄을-원장으로-넓혀-합계에-더한다.md) | native 하위 에이전트 사용량은 재조회 작업 줄을 원장으로 넓혀 합계에 더한다 | backend | Accepted. ADR-016 이 「자식 토큰을 잃는다」 고 한 부분을 native 자식에 한해 메운다 |
| [ADR-063](ADR-063-관리자-전용-표시와-동작은-관리자-영역에만-두고-일반-경로의-응답은-서버가-역할에-따라-줄인다.md) | 관리자 전용 표시와 동작은 관리자 영역에만 두고 일반 경로의 응답은 서버가 역할에 따라 줄인다 | backend, frontend | Accepted. ADR-038 의 「서버가 응답에서 뺀다」 를 금액과 모델과 토큰으로 넓힌다 |
| [ADR-064](ADR-064-범용-커넥터는-이-저장소의-hermes-connectors-에-두고-저장소가-유지보수한다.md) | 범용 커넥터는 이 저장소의 `hermes/connectors/` 에 두고 저장소가 유지보수한다 | hermes | Accepted. ADR-043 의 plugin 가운데 누구나 쓸 수 있는 것의 자리를 정한다 |
| [ADR-065](ADR-065-외부로-나가는-도구는-상시-허락을-닫는-선언을-둔다.md) | 외부로 나가는 도구는 상시 허락을 닫는 선언을 둔다 | backend, hermes | Accepted. ADR-050 의 상시 허락에 도구별 선언을 더한다 |
| [ADR-066](ADR-066-gmail-커넥터는-직접-만든-mcp-서버와-gmail-modify-scope-하나로-돌고-휴지통은-서버가-막는다.md) | Gmail 커넥터는 직접 만든 MCP 서버와 `gmail.modify` scope 하나로 돌고 휴지통은 서버가 막는다 | hermes | Accepted. 서버와 scope 는 ADR-084 로 부분 대체했다 |
| [ADR-067](ADR-067-native-하위-에이전트의-provider-는-대시보드-plugin-이-session-저장소에서-읽어-준다.md) | native 하위 에이전트의 provider 는 대시보드 plugin 이 session 저장소에서 읽어 준다 | backend | Accepted. ADR-062 의 「session 응답이 provider 를 주지 않는 동안 native 자식은 모두 가격 미확인」 을 메운다 |
| [ADR-068](ADR-068-최상위-패키지는-한-방향-층-순서를-따르고-거꾸로-가는-의존은-port-나-이동으로-끊는다.md) | 최상위 패키지는 한 방향 층 순서를 따르고 거꾸로 가는 의존은 port 나 이동으로 끊는다 | backend | Accepted |
| [ADR-069](ADR-069-사용자-전체-실행-한도는-turn-자리와-실행-줄을-사용자-잠금-하나에서-센다.md) | 사용자 전체 실행 한도는 turn 자리와 실행 줄을 사용자 잠금 하나에서 센다 | backend, frontend | Accepted |
| [ADR-070](ADR-070-알림은-control-plane-의-notification-표가-원장이고-웹은-사용자-단위-SSE-로-받는다.md) | 알림은 Control Plane 의 `notification` 표가 원장이고 웹은 사용자 단위 SSE 로 받는다 | backend, frontend | Accepted |
| [ADR-071](ADR-071-여러-출처의-문맥은-항목마다-출처와-권한과-신선도를-지닌-묶음으로-조립한다.md) | 여러 출처의 문맥은 항목마다 출처와 권한과 신선도를 지닌 묶음으로 조립한다 | backend | Accepted |
| [ADR-072](ADR-072-먼저-알리기의-기본값은-알리지-않음이고-control-plane-기록에서-정한-신호만-화면-안에-올린다.md) | 먼저 알리기의 기본값은 알리지 않음이고 Control Plane 기록에서 정한 신호만 화면 안에 올린다 | backend, frontend | Accepted |
| [ADR-073](ADR-073-할-일은-에이전트가-제안하고-사람이-받아들인-것만-챙긴다.md) | 할 일은 에이전트가 제안하고 사람이 받아들인 것만 챙긴다 | backend, frontend, hermes | Accepted |
| [ADR-074](ADR-074-지금-화면은-원래-기록을-읽어-만든-view-이고-정해진-카드-넷만-그린다.md) | 지금 화면은 원래 기록을 읽어 만든 view 이고 정해진 카드 다섯을 그린다 | frontend | Accepted. ADR-085 에서 보고 카드를 더한다 |
| [ADR-075](ADR-075-결과-전달은-묶음과-시도로-남기고-사용자가-저장된-결과만-다시-전달한다.md) | 결과 전달은 묶음과 시도로 남기고 사용자가 저장된 결과만 다시 전달한다 | backend, frontend | Accepted. ADR-040 의 「turn 이 실패해도 같은 결과로 다시 깨우지 않는다」 는 그대로 두고 사용자가 요청하는 복구를 더한다 |
| [ADR-076](ADR-076-예약-작업은-control-plane-이-갖고-발화한-실행은-대화-turn-경로로-돈다.md) | 예약 작업은 Control Plane 이 갖고 발화한 실행은 대화 turn 경로로 돈다 | backend | Accepted |
| [ADR-077](ADR-077-발화는-trigger-와-예정-시각의-유일-제약으로-한-번만-만들고-놓친-발화는-작업마다-정한다.md) | 발화는 trigger 와 예정 시각의 유일 제약으로 한 번만 만들고 놓친 발화는 작업마다 정한다 | backend | Accepted |
| [ADR-078](ADR-078-예약-작업의-결과는-실행마다-새-대화가-기본이고-목록은-작업으로-묶는다.md) | 예약 작업의 결과는 실행마다 새 대화가 기본이고 목록은 작업으로 묶는다 | backend, frontend | Accepted |
| [ADR-079](ADR-079-예약-작업은-사용자당-10개-최소-간격-15분-하루-48번으로-제한한다.md) | 예약 작업은 사용자당 10개, 최소 간격 15분, 하루 48번으로 제한한다 | backend | Accepted |
| [ADR-080](ADR-080-먼저-살펴보기는-점검-대화의-turn-하나로-돌고-읽기-경계를-control-plane-이-강제한다.md) | 먼저 살펴보기는 점검 대화의 turn 하나로 돌고 읽기 경계를 Control Plane 이 강제한다 | backend, frontend | Accepted. ADR-040 의 「부모는 맡긴 뒤 기다리지 않는다」 에 살펴보기 트리의 예외를 둔다. 맡길 곳을 커넥터 에이전트로 한정한 부분은 ADR-083 이 대체한다 |
| [ADR-081](ADR-081-살펴보기-결과는-답-끝의-구조화-블록으로-받고-control-plane-이-검사해-그린다.md) | 살펴보기 결과는 답 끝의 구조화 블록으로 받고 Control Plane 이 검사해 그린다 | backend | Accepted. ADR-009 의 신뢰하지 않는 글 원칙을 살펴보기 결과에 적용한다. 「관심 없음」 주제를 digest 기간 동안 원문과 changeSinceLast 에 관계없이 내리는 예외는 [ADR-20261008 / check-finding-reaction](ADR-20261008-check-finding-reaction.md) 이 둔다 |
| [ADR-082](ADR-082-먼저-살펴보기의-쓰기-도구는-관리자가-에이전트마다-켜고-커넥터-쓰기는-승인-카드로-보낸다.md) | 먼저 살펴보기의 쓰기 도구는 관리자가 에이전트마다 켜고, 커넥터 쓰기는 승인 카드로 보낸다 | backend, frontend | Accepted. 켠 에이전트에 한해 ADR-080 의 읽기 경계를 넓힌다. 맡길 곳을 커넥터 에이전트로 한정한 부분은 ADR-083 이 대체한다 |
| [ADR-083](ADR-083-커넥터는-사용자가-한-번-연결하고-자기-에이전트에-여럿-붙여-그-에이전트가-도구를-직접-부른다.md) | 커넥터는 사용자가 한 번 연결하고 자기 에이전트에 여럿 붙여 그 에이전트가 도구를 직접 부른다 | 공통 | Accepted. 새 연결에 대해 ADR-039, ADR-044, ADR-045 를, 커넥터 에이전트 부분에 대해 ADR-029, ADR-049, ADR-080, ADR-082 를 대체한다. 처음 붙이기와 스킬 변경의 재시작 대기는 [ADR-20261007 / connector-live-reload](ADR-20261007-connector-live-reload.md) 가 대체한다 |
| [ADR-084](ADR-084-gmail-typescript-filters.md) | Gmail 커넥터는 TypeScript 묶음 파일로 실행하고 필터 권한을 따로 받는다 | hermes | Accepted. ADR-066 의 서버와 단일 scope 를 대체한다 |
| [ADR-085](ADR-085-매일-깨우기는-예약-작업을-다시-쓰고-다섯-칸-보고를-지금-화면에-올린다.md) | 매일 깨우기는 예약 작업을 다시 쓰고 다섯 칸 보고를 지금 화면에 올린다 | backend, frontend | Accepted. ADR-074, 076, 078, 079, 080, 081, 082 의 매일 깨우기와 보고 계약을 개정한다 |
| [ADR-086](ADR-086-셸과-파일-도구는-사용자별-docker-실행-공간에서만-돈다.md) | 셸과 파일 도구는 사용자별 docker 실행 공간에서만 돈다 | backend, frontend, hermes | Accepted. 운영 정책에 등록한 profile 에만 적용한다. ADR-082 의 남는 위험을 사용자 실행 공간 안으로 줄인다. ADR-029 의 「파일 접근을 격리하지 않는다」 전제는 실행 공간이 적용된 profile 에서 바뀐다 |
| [ADR-087](ADR-087-같은-도구의-승인-줄은-묶음으로-보이고-상시-허락을-줄-수-있는-줄만-한꺼번에-승인한다.md) | 같은 도구의 승인 줄은 묶음으로 보이고 상시 허락을 줄 수 있는 줄만 한꺼번에 승인한다 | frontend | Accepted. ADR-050 의 승인 카드에 화면 쪽 조건을 더한다 |
| [ADR-088](ADR-088-대시보드-plugin-은-감싸는-경로의-바꿔-끼우기를-두고-기대는-hermes-내부-지점을-계약-시험으로-확인한다.md) | 대시보드 plugin 은 감싸는 경로의 바꿔 끼우기를 두고, 기대는 Hermes 내부 지점을 계약 시험으로 확인한다 | hermes | Accepted. ADR-041 의 plugin 을 기능 모듈로 나누는 경계를 정한다 |
| [ADR-089](ADR-089-커넥터는-식별자-인자를-선언하고-승인-카드는-그-값을-길이로-가리지-않는다.md) | 커넥터는 식별자 인자를 선언하고 승인 카드는 그 값을 길이로 가리지 않는다 | backend, frontend, hermes | Accepted. ADR-065 의 「가려진 인자」 에 커넥터 선언으로만 받는 예외를 더한다 |
| [ADR-090](ADR-090-한-번에-받는-경로도-hermes-사건-스트림을-열어-도구-사건을-남긴다.md) | 한 번에 받는 경로도 Hermes 사건 스트림을 열어 도구 사건을 남긴다 | backend | Accepted. ADR-013 이 사건을 옮겨 적는 경로에 한 번에 받는 경로를 더한다 |
| [ADR-091](ADR-091-사진-첨부는-사용자별로-저장하고-실행-공간에는-그-사용자만-붙인다.md) | 사진 첨부는 사용자별로 저장하고 실행 공간에는 그 사용자만 붙인다 | backend, hermes | Accepted. ADR-020 의 저장 경로와 ADR-086 의 전체 첨부 mount 를 바꾼다 |
| [ADR-092](ADR-092-승인한-실행의-실패는-커넥터가-선언한-오류-코드와-복구-어휘와-정수-세부만-에이전트까지-전한다.md) | 승인한 실행의 실패는 커넥터가 선언한 오류 코드와 복구 어휘와 정수 세부만 에이전트까지 전한다 | backend, hermes | Accepted. ADR-043 의 공통 오류 어휘 옆에 커넥터가 선언한 코드와 복구 계약을 더하고 ADR-050 의 실패 결과 저장을 바꾼다 |
| [ADR-093](ADR-093-문제-찾기는-살펴보기-결과의-문제-후보로-받고-control-plane-이-근거와-중복을-결정적으로-검사한다.md) | 문제 찾기는 살펴보기 결과의 문제 후보로 받고 Control Plane 이 근거와 중복을 결정적으로 검사한다 | backend | Accepted. ADR-081 의 결과 블록을 버전 3으로 넓힌다 |
| [ADR-20261007 / autonomy-policy](ADR-20261007-autonomy-policy.md) | 행동 수준은 Control Plane 의 결정적 규칙이 정하고 첫 자동 실행은 읽기 전용 살펴보기 한 번이다 | backend | Accepted |
| [ADR-20261007 / background-tasks](ADR-20261007-background-tasks.md) | 운영의 백그라운드 작업은 한 빈으로 띄우고, 검사는 끝날 때 모두 join 한다 | backend | Accepted |
| [ADR-20261007 / connector-live-reload](ADR-20261007-connector-live-reload.md) | 커넥터를 붙이면 공유 gateway 의 MCP 설정 맞추기로 반영하고 Control Plane 이 두 주기 뒤 스스로 확인한다 | hermes, backend | Accepted. ADR-083 의 「붙이면 재시작 뒤 관리자 반영 완료로 READY」 를 첫 붙이기와 스킬 변경에서 대체한다 |
| [ADR-20261007 / connector-owner-attachments](ADR-20261007-connector-owner-attachments.md) | 사용자 첨부를 읽는 커넥터는 바인딩 주인의 첨부 디렉터리를 설치가 정한 env 로만 받는다 | hermes, backend | Accepted. ADR-091 의 사용자별 첨부 경계를 커넥터 MCP 서버까지 넓힌다 |
| [ADR-20261007 / decision-feedback](ADR-20261007-decision-feedback.md) | 판단 피드백은 제안 열쇠에 덧붙이는 사건으로 남기고 반응은 읽을 때 정한다 | backend | Accepted. 살펴보기 발견의 「관심 없음」 이 되풀이 판정을 바꾸는 예외는 [ADR-20261008 / check-finding-reaction](ADR-20261008-check-finding-reaction.md) 이 둔다 |
| [ADR-20261007 / live-properties](ADR-20261007-live-properties.md) | 운영 코드는 실행 중에 쓰는 설정을 LiveProperties 로 읽는다 | backend | Accepted |
| [ADR-20261007 / memory-remember](ADR-20261007-memory-remember.md) | 사용자가 대화에서 직접 말한 사실은 에이전트가 바로 기억하고 그 밖은 제안으로 남긴다 | backend, frontend, hermes | Accepted. ADR-012 의 「사람이 받아들여야 저장된다」 와 「에이전트가 스스로 쓰는 경로를 두지 않는다」 를 개정한다. 색인에 제목만 실린다는 부분은 [ADR-20261008 / memory-facts](ADR-20261008-memory-facts.md) 이 개정한다 |
| [ADR-20261007 / naver-blog-connector](ADR-20261007-naver-blog-connector.md) | 네이버 블로그 커넥터는 사용자의 Chrome 에 CDP 로 붙고, 임시저장은 승인한 뒤 백그라운드 작업으로 돈다 | hermes, backend | Accepted. ADR-083 의 바인딩으로 블로그 전용 profile 을 대신한다 |
| [ADR-20261007 / numbering-scheme](ADR-20261007-numbering-scheme.md) | Flyway 는 UTC 시각 버전을 쓰고 ADR 은 결정 날짜와 슬러그로 구분한다 | 공통 | Accepted |
| [ADR-20261007 / proactive-eval](ADR-20261007-proactive-eval.md) | 먼저 살펴보기 루프는 결정적 provider 로 실제 서비스를 replay 해 측정하고 안전 경계만 CI 를 막는다 | backend | Accepted |
| [ADR-20261007 / test-context-base](ADR-20261007-test-context-base.md) | backend 통합 검사는 Spring 컨텍스트 하나를 함께 쓰고, 설정과 대역은 검사마다 바꿔 끼운다 | backend | Accepted |
| [ADR-20261007 / user-browser](ADR-20261007-user-browser.md) | 사용자마다 브라우저 하나를 Control Plane 이 관리하고, 커넥터는 바인딩이 준 중계 주소로만 닿는다 | backend, frontend, hermes | Accepted. ADR-20261007 / naver-blog-connector 의 CDP 주소 연결 칸을 바꾼다 |
| [ADR-20261007 / value-evaluation](ADR-20261007-value-evaluation.md) | 가치 판단은 축별 근거와 재평가 입력을 남기고 행동 정책과 분리한다 | backend, hermes | Accepted |
| [ADR-20261008 / agent-memory-grants-admin](ADR-20261008-agent-memory-grants-admin.md) | 관리자가 에이전트의 Memory collection 을 한 번에 바꾸고, 바꾼 것은 누가 언제를 표에 남긴다 | backend, frontend | Accepted. ADR-053 의 「다음」 에 적은 관리 경로를 정한다 |
| [ADR-20261008 / check-finding-reaction](ADR-20261008-check-finding-reaction.md) | 살펴보기 발견의 반응은 판단 피드백 사건으로 받고, 「관심 없음」 은 digest 기간 안에서만 같은 주제를 내린다 | backend, frontend | Accepted. ADR-20261007 / decision-feedback 의 「억제 규칙을 바꾸지 않는다」 와 ADR-081 의 되풀이 규칙에 예외를 둔다 |
| [ADR-20261008 / connector-card](ADR-20261008-connector-card.md) | 커넥터 아이콘은 plugin 안의 파일을 카탈로그에 실어 같은 출처의 이미지로만 그리고, 링크는 https 만 받는다 | 공통 | Accepted. ADR-043 의 `connector.json` 에 화면용 선택 칸을 더한다 |
| [ADR-20261008 / connector-output-files](ADR-20261008-connector-output-files.md) | 커넥터는 계산할 목록을 그 에이전트 실행 공간에 읽기 전용으로 붙는 파일로 내고, 계산은 스크립트가 한다 | hermes | Accepted. ADR-086 의 실행 공간에 커넥터 출력 디렉터리를 더한다 |
| [ADR-20261008 / memory-facts](ADR-20261008-memory-facts.md) | 짧은 개인 기억은 본문까지 「개인 사실 구역」에 싣고, 답마다 참고한 기억을 사용자에게 보인다 | backend, frontend | Accepted. ADR-20261007 / memory-remember 의 「색인에 제목이 실린다」 를 개정하고 ADR-015 에 예외를 둔다 |
