# ADR 목록

| 번호 | 제목 | 상태 |
| --- | --- | --- |
| [ADR-001](ADR-001-hermes를-런타임으로-두고-core를-고치지-않는다.md) | Hermes 를 런타임으로 두고 core 를 고치지 않는다 | Accepted |
| [ADR-002](ADR-002-profile은-나누고-ai-계정은-가족이-함께-쓴다.md) | profile 은 나누고 AI 계정은 가족이 함께 쓴다 | Accepted |
| [ADR-003](ADR-003-memory-권한은-주입으로-강제한다.md) | Memory 권한은 주입으로 강제한다 | Accepted |
| [ADR-004](ADR-004-구독제에서도-api-가격으로-환산해-보인다.md) | 구독제에서도 API 가격으로 환산해 보인다 | Accepted |
| [ADR-005](ADR-005-기존-개인-저장소의-스택을-그대로-쓴다.md) | 기존 개인 저장소의 스택을 그대로 쓴다 | Accepted. Checkstyle 을 뺀 부분은 ADR-042 가 대체한다 |
| [ADR-006](ADR-006-작업-영역은-읽기-전용-의존이고-공개-범위는-기본값이-없다.md) | 작업 영역은 읽기 전용 의존이고 공개 범위는 기본값이 없다 | Superseded ([ADR-010](ADR-010-작업-영역을-제거하고-에이전트가-그-자리를-갖는다.md)) |
| [ADR-007](ADR-007-에이전트가-모델과-도구를-함께-정한다.md) | 에이전트가 모델과 도구를 함께 정한다 | Accepted. 모델 부분은 ADR-030 이 대체한다 |
| [ADR-008](ADR-008-스트리밍은-보여주기용이고-저장은-실행-결과로-한다.md) | 스트리밍은 보여주기용이고 저장은 실행 결과로 한다 | Accepted |
| [ADR-009](ADR-009-에이전트의-답은-신뢰하지-않는-글로-그린다.md) | 에이전트의 답은 신뢰하지 않는 글로 그린다 | Accepted |
| [ADR-010](ADR-010-작업-영역을-제거하고-에이전트가-그-자리를-갖는다.md) | 작업 영역을 제거하고 에이전트가 그 자리를 갖는다 | Accepted |
| [ADR-011](ADR-011-실행은-시작할-때-기록하고-끝날-때-갱신한다.md) | 실행은 시작할 때 기록하고 끝날 때 갱신한다 | Accepted |
| [ADR-012](ADR-012-memory-는-사람이-승인한-것만-남는다.md) | Memory 는 사람이 승인한 것만 남는다 | Accepted |
| [ADR-013](ADR-013-실행-사건은-우리-모델로-정규화해-저장한다.md) | 실행 사건은 우리 모델로 정규화해 저장한다 | Accepted |
| [ADR-014](ADR-014-실제-청구액과-환산액을-나눠-적는다.md) | 실제 청구액과 환산액을 나눠 적는다 | Accepted |
| [ADR-015](ADR-015-memory-는-층을-나눠-싣는다.md) | Memory 는 층을 나눠 싣는다 | Accepted. ADR-003 의 조회 방식을 보완 |
| [ADR-016](ADR-016-다중-에이전트-조율은-control-plane이-맡는다.md) | 다중 에이전트 조율은 Control Plane이 맡는다 | Superseded ([ADR-017](ADR-017-무엇을-할지는-hermes-가-정하고-control-plane-은-경계만-갖는다.md)) |
| [ADR-017](ADR-017-무엇을-할지는-hermes-가-정하고-control-plane-은-경계만-갖는다.md) | 무엇을 할지는 Hermes 가 정하고 Control Plane 은 경계만 갖는다 | Accepted |
| [ADR-018](ADR-018-사람을-더하는-것을-control-plane-이-끝낸다.md) | 사람을 더하는 것을 Control Plane 이 끝낸다 | Accepted. 첫 로그인의 모델 읽기는 ADR-030 으로 없어졌다 |
| [ADR-019](ADR-019-페르소나는-hermes-가-갖고-control-plane-은-화면만-준다.md) | 페르소나는 Hermes 가 갖고 Control Plane 은 화면만 준다 | Accepted |
| [ADR-020](ADR-020-사진은-공유-디렉터리에-두고-에이전트가-파일로-읽는다.md) | 사진은 공유 디렉터리에 두고 에이전트가 파일로 읽는다 | Accepted |
| [ADR-021](ADR-021-중지한-답은-멈춘-자리까지-남긴다.md) | 중지한 답은 멈춘 자리까지 남긴다 | Accepted. ADR-008 에 예외를 둔다 |
| [ADR-022](ADR-022-다시-생성과-수정은-같은-session-에-판으로-쌓는다.md) | 다시 생성과 수정은 같은 session 에 판으로 쌓는다 | Accepted. 수정은 ADR-024 가 대체 |
| [ADR-023](ADR-023-화면-부품은-shadcn-ui-를-저장소에-복사해-쓴다.md) | 화면 부품은 shadcn/ui 를 저장소에 복사해 쓴다 | Accepted |
| [ADR-024](ADR-024-메시지-수정을-없애고-중지한-뒤-다시-보낸다.md) | 메시지 수정을 없애고 중지한 뒤 다시 보낸다 | Accepted. ADR-022 의 수정을 대체 |
| [ADR-025](ADR-025-대화는-주소에-공개-식별자를-쓰고-번호는-안에만-둔다.md) | 대화는 주소에 공개 식별자를 쓰고 번호는 안에만 둔다 | Accepted |
| [ADR-026](ADR-026-에이전트가-물을-것은-답-끝의-태그로-두고-화면이-카드로-그린다.md) | 에이전트가 물을 것은 답 끝의 태그로 두고 화면이 카드로 그린다 | Accepted |
| [ADR-027](ADR-027-에이전트가-만든-html-은-대화별-폴더에-두고-스크립트-없이-보인다.md) | 에이전트가 만든 HTML 은 대화별 폴더에 두고 스크립트 없이 보인다 | Accepted |
| [ADR-028](ADR-028-결과물은-사용자의-대화-폴더에-mcp-도구로-쓴다.md) | 결과물은 사용자의 대화 폴더에 MCP 도구로 쓴다 | Accepted |
| [ADR-029](ADR-029-에이전트-도구는-control-plane-이-등급으로-판정하고-hermes-설정-api-로-쓴다.md) | 에이전트 도구는 Control Plane 이 등급으로 판정하고 Hermes 설정 API 로 쓴다 | Accepted. ADR-007 에 더한다 |
| [ADR-030](ADR-030-모델과-effort-는-대화가-고르고-기본값은-hermes-profile-이-갖는다.md) | 모델과 effort 는 대화가 고르고 기본값은 Hermes profile 이 갖는다 | Accepted. ADR-007 의 모델 부분을 대체한다 |
| [ADR-031](ADR-031-mcp-호출의-부모-실행은-profile-플러그인이-서명한-뿌리-session-으로-잇는다.md) | MCP 호출의 부모 실행은 profile 플러그인이 서명한 뿌리 session 으로 잇는다 | Accepted. ADR-017 의 부모 잇기를 정한다 |
| [ADR-032](ADR-032-mcp-토큰은-profile-을-증명하고-실제-사용자는-부모-실행에서-정한다.md) | MCP 토큰은 profile 을 증명하고 실제 사용자는 부모 실행에서 정한다 | Accepted. ADR-003, ADR-017, ADR-028 의 요청자 판정과 ADR-031 의 일부를 대체한다 |
| [ADR-033](ADR-033-사용자가-에이전트를-만들고-공개해도-만든-사람이-관리한다.md) | 사용자가 에이전트를 만들고, 공개해도 만든 사람이 관리한다 | Accepted |
| [ADR-034](ADR-034-올린-스킬은-control-plane-이-버전-디렉터리에-쓰고-hermes-는-읽기만-한다.md) | 올린 스킬은 Control Plane 이 버전 디렉터리에 쓰고 Hermes 는 읽기만 한다 | Accepted |
| [ADR-035](ADR-035-대화창의-스킬-커맨드는-control-plane-이-해석해-hermes-에-넘긴다.md) | 대화창의 스킬 커맨드는 Control Plane 이 해석해 Hermes 에 넘긴다 | Accepted |
| [ADR-036](ADR-036-추천-질문은-사용자의-대화-이력으로-모델이-만들고-메모리에만-둔다.md) | 추천 질문은 사용자의 대화 이력으로 모델이 만들고 메모리에만 둔다 | Accepted |
| [ADR-037](ADR-037-hermes-하위-에이전트-session-의-주인은-만들-때-등록한-줄로-정한다.md) | Hermes 하위 에이전트 session 의 주인은 만들 때 등록한 줄로 정한다 | Accepted. ADR-031, ADR-032 의 하위 에이전트 session 판정을 대체한다 |
| [ADR-038](ADR-038-도구의-명령-원문은-관리자에게만-보내고-사용자에게는-사람-말로-보인다.md) | 도구의 명령 원문은 관리자에게만 보내고 사용자에게는 사람 말로 보인다 | Accepted |
| [ADR-039](ADR-039-외부-서비스-연결은-사용자별-전용-에이전트로-실행한다.md) | 외부 서비스 연결은 사용자별 전용 에이전트로 실행한다 | Accepted. ADR-033의 일반 편집 권한에 연결용 에이전트 예외를 둔다 |
| [ADR-040](ADR-040-위임-결과는-control-plane-이-부모-대화의-다음-turn-을-열어-전한다.md) | 위임 결과는 Control Plane 이 부모 대화의 다음 turn 을 열어 전한다 | Accepted. ADR-017 의 「결과는 모델이 다시 묻는다」 를 대체한다 |
| [ADR-041](ADR-041-hermes-에-설치하는-plugin-과-profile-틀은-이-저장소가-소유한다.md) | Hermes 에 설치하는 plugin 과 profile 틀은 이 저장소가 소유한다 | Accepted |
| [ADR-042](ADR-042-코드-품질-규칙은-도구-설정이-갖고-기존-위반은-기준-파일에-둔다.md) | 코드 품질 규칙은 도구 설정이 갖고 기존 위반은 기준 파일에 둔다 | Accepted. ADR-005 가 미룬 Checkstyle 을 넣는다 |
| [ADR-043](ADR-043-커넥터는-plugin-의-connector-json-으로-선언하고-control-plane-은-범용-흐름만-갖는다.md) | 커넥터는 plugin 의 connector.json 으로 선언하고 Control Plane 은 범용 흐름만 갖는다 | Accepted. ADR-039 위에 얹는다 |
| [ADR-044](ADR-044-커넥터-manifest-는-읽기-전용-이미지-도구만-열-수-있다.md) | 커넥터 manifest 는 읽기 전용 이미지 도구만 열 수 있다 | Accepted. ADR-039 의 도구 차단에 예외 하나를 둔다 |
| [ADR-047](ADR-047-응답-중에-보낸-메시지는-control-plane-이-쌓아-두고-다음-turn-으로-합쳐-보낸다.md) | 응답 중에 보낸 메시지는 Control Plane 이 쌓아 두고 다음 turn 으로 합쳐 보낸다 | Accepted. ADR-040 의 다음 turn 을 여는 자리를 함께 쓴다 |
