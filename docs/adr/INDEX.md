# ADR 목록

층은 그 결정을 지키는 코드가 있는 쪽이다. 공통은 두 층과 `hermes/` 에 함께 걸린다.

| 번호 | 제목 | 층 | 상태 |
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
| [ADR-012](ADR-012-memory-는-사람이-승인한-것만-남는다.md) | Memory 는 사람이 승인한 것만 남는다 | backend | Accepted. 에이전트별 구분을 기각한 부분은 ADR-053 가 대체한다 |
| [ADR-013](ADR-013-실행-사건은-우리-모델로-정규화해-저장한다.md) | 실행 사건은 우리 모델로 정규화해 저장한다 | backend | Accepted |
| [ADR-014](ADR-014-실제-청구액과-환산액을-나눠-적는다.md) | 실제 청구액과 환산액을 나눠 적는다 | backend | Accepted |
| [ADR-015](ADR-015-memory-는-층을-나눠-싣는다.md) | Memory 는 층을 나눠 싣는다 | backend | Accepted. ADR-003 의 조회 방식을 보완. `always_inject` 칸은 ADR-052 이 `retrieval` 로 넓힌다 |
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
| [ADR-029](ADR-029-에이전트-도구는-control-plane-이-등급으로-판정하고-hermes-설정-api-로-쓴다.md) | 에이전트 도구는 Control Plane 이 등급으로 판정하고 Hermes 설정 API 로 쓴다 | backend | Accepted. ADR-007 에 더한다. 연결용 에이전트의 도구 목록은 ADR-044 와 ADR-045 가 대체한다 |
| [ADR-030](ADR-030-모델과-effort-는-대화가-고르고-기본값은-hermes-profile-이-갖는다.md) | 모델과 effort 는 대화가 고르고 기본값은 Hermes profile 이 갖는다 | backend | Accepted. ADR-007 의 모델 부분을 대체한다. 기본값을 profile 에 두는 부분은 ADR-054 가 대체한다 |
| [ADR-031](ADR-031-mcp-호출의-부모-실행은-profile-플러그인이-서명한-뿌리-session-으로-잇는다.md) | MCP 호출의 부모 실행은 profile 플러그인이 서명한 뿌리 session 으로 잇는다 | backend | Accepted. ADR-017 의 부모 잇기를 정한다. 일부는 ADR-032 가 대체한다 |
| [ADR-032](ADR-032-mcp-토큰은-profile-을-증명하고-실제-사용자는-부모-실행에서-정한다.md) | MCP 토큰은 profile 을 증명하고 실제 사용자는 부모 실행에서 정한다 | backend | Accepted. ADR-003, ADR-017, ADR-028 의 요청자 판정과 ADR-031 의 일부를 대체한다. 하위 에이전트 session 의 요청자는 ADR-037 이 대체한다 |
| [ADR-033](ADR-033-사용자가-에이전트를-만들고-공개해도-만든-사람이-관리한다.md) | 사용자가 에이전트를 만들고, 공개해도 만든 사람이 관리한다 | backend | Accepted. 연결용 에이전트의 일반 편집 권한은 ADR-039 가 대체한다 |
| [ADR-034](ADR-034-올린-스킬은-control-plane-이-버전-디렉터리에-쓰고-hermes-는-읽기만-한다.md) | 올린 스킬은 Control Plane 이 버전 디렉터리에 쓰고 Hermes 는 읽기만 한다 | backend | Accepted |
| [ADR-035](ADR-035-대화창의-스킬-커맨드는-control-plane-이-해석해-hermes-에-넘긴다.md) | 대화창의 스킬 커맨드는 Control Plane 이 해석해 Hermes 에 넘긴다 | backend | Accepted |
| [ADR-036](ADR-036-추천-질문은-사용자의-대화-이력으로-모델이-만들고-메모리에만-둔다.md) | 추천 질문은 사용자의 대화 이력으로 모델이 만들고 메모리에만 둔다 | backend | Accepted |
| [ADR-037](ADR-037-hermes-하위-에이전트-session-의-주인은-만들-때-등록한-줄로-정한다.md) | Hermes 하위 에이전트 session 의 주인은 만들 때 등록한 줄로 정한다 | backend | Accepted. ADR-031, ADR-032 의 하위 에이전트 session 판정을 대체한다 |
| [ADR-038](ADR-038-도구의-명령-원문은-관리자에게만-보내고-사용자에게는-사람-말로-보인다.md) | 도구의 명령 원문은 관리자에게만 보내고 사용자에게는 사람 말로 보인다 | backend, frontend | Accepted. 원문 저장과 관리자 원문 보기는 ADR-047 이 대체한다 |
| [ADR-039](ADR-039-외부-서비스-연결은-사용자별-전용-에이전트로-실행한다.md) | 외부 서비스 연결은 사용자별 전용 에이전트로 실행한다 | backend | Accepted. ADR-033의 일반 편집 권한에 연결용 에이전트 예외를 둔다 |
| [ADR-040](ADR-040-위임-결과는-control-plane-이-부모-대화의-다음-turn-을-열어-전한다.md) | 위임 결과는 Control Plane 이 부모 대화의 다음 turn 을 열어 전한다 | backend | Accepted. ADR-017 의 「결과는 모델이 다시 묻는다」 를 대체한다 |
| [ADR-041](ADR-041-hermes-에-설치하는-plugin-과-profile-틀은-이-저장소가-소유한다.md) | Hermes 에 설치하는 plugin 과 profile 틀은 이 저장소가 소유한다 | 공통 | Accepted |
| [ADR-042](ADR-042-코드-품질-규칙은-도구-설정이-갖고-기존-위반은-기준-파일에-둔다.md) | 코드 품질 규칙은 도구 설정이 갖고 기존 위반은 기준 파일에 둔다 | 공통 | Accepted. ADR-005 가 미룬 Checkstyle 을 넣는다 |
| [ADR-043](ADR-043-커넥터는-plugin-의-connector-json-으로-선언하고-control-plane-은-범용-흐름만-갖는다.md) | 커넥터는 plugin 의 connector.json 으로 선언하고 Control Plane 은 범용 흐름만 갖는다 | backend | Accepted. ADR-039 위에 얹는다 |
| [ADR-044](ADR-044-커넥터-manifest-는-읽기-전용-이미지-도구만-열-수-있다.md) | 커넥터 manifest 는 읽기 전용 이미지 도구만 열 수 있다 | backend | Accepted. ADR-039 의 도구 차단에 예외 하나를 둔다 |
| [ADR-045](ADR-045-커넥터-에이전트는-자기-mcp-서버만-받고-memory-와-control-plane-도구를-받지-않는다.md) | 커넥터 에이전트는 자기 MCP 서버만 받고 Memory 와 Control Plane 도구를 받지 않는다 | backend | Accepted. ADR-039 의 전용 에이전트에 경계를 더한다 |
| [ADR-046](ADR-046-운영-비밀은-operator-env-와-다른-칸으로-선언하고-자식-mcp-프로세스에만-넣는다.md) | 운영 비밀은 operator_env 와 다른 칸으로 선언하고 자식 MCP 프로세스에만 넣는다 | backend | Accepted. ADR-043 의 manifest 에 칸을 더한다. 지금은 그 칸을 거절한다 |
| [ADR-047](ADR-047-도구-내용은-비밀값과-UUID를-가린-뒤-중계하고-저장한다.md) | 도구 내용은 비밀값과 UUID를 가린 뒤 중계하고 저장한다 | backend | Accepted. ADR-038의 관리자 원문 보기를 가린 값으로 바꾼다 |
| [ADR-048](ADR-048-응답-중에-보낸-메시지는-control-plane-이-쌓아-두고-다음-turn-으로-합쳐-보낸다.md) | 응답 중에 보낸 메시지는 Control Plane 이 쌓아 두고 다음 turn 으로 합쳐 보낸다 | backend, frontend | Accepted. ADR-040 의 다음 turn 을 여는 자리를 함께 쓴다 |
| [ADR-049](ADR-049-커넥터-도구-호출은-profile-plugin-의-hook-이-control-plane-에-물어-판정한다.md) | 커넥터 도구 호출은 profile plugin 의 hook 이 Control Plane 에 물어 판정한다 | backend | Accepted. ADR-043 의 manifest 에 도구 정책을 더한다 |
| [ADR-050](ADR-050-커넥터-쓰기는-control-plane-이-승인-줄을-저장하고-승인한-인자로-한-번만-실행한다.md) | 커넥터 쓰기는 Control Plane 이 승인 줄을 저장하고 승인한 인자로 한 번만 실행한다 | backend | Accepted. ADR-040 의 깨우기를 승인 결과로 넓힌다 |
| [ADR-051](ADR-051-화면-색은-새벽-보라로-바꾸고-강조-색은-누를-것과-고른-것과-초점에만-쓴다.md) | 화면 색은 새벽 보라로 바꾸고 강조 색은 누를 것과 고른 것과 초점에만 쓴다 | frontend | Accepted. ADR-023 의 색 값 부분을 대체한다 |
| [ADR-052](ADR-052-memory-는-collection-종류-꺼내는-방식-민감도-판-출처를-가진다.md) | Memory 는 collection, 종류, 꺼내는 방식, 민감도, 판, 출처를 가진다 | backend | Accepted. ADR-015 의 `always_inject` 를 `retrieval` 로 넓힌다. 민감 본문은 평문이라는 부분은 ADR-055 가 대체한다 |
| [ADR-053](ADR-053-에이전트는-허용된-collection-의-memory-만-받는다.md) | 에이전트는 허용된 collection 의 Memory 만 받는다 | backend | Accepted. ADR-012 가 기각한 에이전트별 구분을 뒤집는다 |
| [ADR-054](ADR-054-에이전트-기본-모델과-모델-숨김은-control-plane-db-가-갖는다.md) | 에이전트 기본 모델과 모델 숨김은 Control Plane DB 가 갖는다 | backend | Accepted. ADR-030 의 「기본값은 Hermes profile 이 갖는다」 를 대체한다 |
| [ADR-055](ADR-055-민감-memory-본문은-저장할-때-암호화하고-key-는-환경-변수로-받는다.md) | 민감 Memory 본문은 저장할 때 암호화하고 key 는 환경 변수로 받는다 | backend | Accepted. ADR-052 의 「민감 본문은 평문」 부분을 대체한다 |
| [ADR-056](ADR-056-다른-서비스는-사용자에-묶인-서비스-토큰으로-문서를-읽기만-한다.md) | 다른 서비스는 사용자에 묶인 서비스 토큰으로 문서를 읽기만 한다 | backend | Accepted. ADR-053 이 미룬 서비스 토큰을 정한다 |
| [ADR-057](ADR-057-문서는-사람이-화면에서-직접-쓰고-고친다.md) | 문서는 사람이 화면에서 직접 쓰고 고친다 | backend, frontend | Accepted. ADR-012 의 승인 원칙을 문서에 적용한다 |
| [ADR-058](ADR-058-기존-개인-지식-저장소는-주인이-검토한-묶음을-화면에서-올려-들여온다.md) | 기존 개인 지식 저장소는 주인이 검토한 묶음을 화면에서 올려 들여온다 | backend, frontend | Accepted. ADR-057 의 「옮길 때 사람이 붙여 넣는다」 를 일회성 이관에 한해 넓힌다. 신원 항목의 들이기는 아직 구현 전이다 |
| [ADR-059](ADR-059-꺼진-사용자는-control-plane-이-요청마다-막고-웹이-세션을-끊는다.md) | 꺼진 사용자는 Control Plane 이 요청마다 막고 웹이 세션을 끊는다 | backend, frontend | Accepted |
| [ADR-060](ADR-060-reasoning-effort-의-지원은-확인한-것만-보이고-모르면-미확인으로-둔다.md) | reasoning effort 의 지원은 Hermes 가 알린 것만 확인으로 보이고 모르면 미확인으로 둔다 | backend, frontend | Accepted. ADR-030 의 「받지 않는 effort 는 Hermes 가 맞춘다」 는 그대로 두고 지원 표시와 `none` 선택을 더한다 |
| [ADR-061](ADR-061-재기동-때-남은-실행은-hermes-에-물어-정하고-도는-실행에는-다시-붙는다.md) | 재기동 때 남은 실행은 Hermes 에 물어 정하고 도는 실행에는 다시 붙는다 | backend | Accepted. ADR-011 의 기동 정리 부분을 대체한다 |
| [ADR-062](ADR-062-native-하위-에이전트-사용량은-재조회-작업-줄을-원장으로-넓혀-합계에-더한다.md) | native 하위 에이전트 사용량은 재조회 작업 줄을 원장으로 넓혀 합계에 더한다 | backend | Accepted. ADR-016 이 「자식 토큰을 잃는다」 고 한 부분을 native 자식에 한해 메운다 |
| [ADR-063](ADR-063-관리자-전용-표시와-동작은-관리자-영역에만-두고-일반-경로의-응답은-서버가-역할에-따라-줄인다.md) | 관리자 전용 표시와 동작은 관리자 영역에만 두고 일반 경로의 응답은 서버가 역할에 따라 줄인다 | backend, frontend | Accepted. ADR-038 의 「서버가 응답에서 뺀다」 를 금액과 모델과 토큰으로 넓힌다 |
| [ADR-067](ADR-067-native-하위-에이전트의-provider-는-대시보드-plugin-이-session-저장소에서-읽어-준다.md) | native 하위 에이전트의 provider 는 대시보드 plugin 이 session 저장소에서 읽어 준다 | backend | Accepted. ADR-062 의 「session 응답이 provider 를 주지 않는 동안 native 자식은 모두 가격 미확인」 을 메운다 |
