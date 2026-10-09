# 먼저 살펴보기

먼저 살펴보기 한 번과 그 발견, 문제 후보, 가치 평가, 행동 정책의 판정과 사용자 선호, 매일 루프의 설정과 시도를 저장하는 표 여덟이다.
칸과 타입, FK, 색인은 마이그레이션이 갖는다. 이 문서는 칸 표로 알 수 없는 까닭과 지울 때의 연쇄를 갖는다.
이 표들을 읽고 쓰는 경로는 [`backend/proactive-check.md`](../proactive-check.md) 가 갖는다.
점검 대화는 [`chat.md`](chat.md) 의 `conversation.purpose` 로 가린다.

## proactive_check

살펴보기 한 번이다. 시작할 때 만들고 끝날 때 갱신한다. 실행별 토큰과 금액은 `agent_execution` 이 갖는다.
깨우기 예산을 측정할 트리 토큰 합계만 여기에도 남긴다.
살펴보기 한 번의 비용은 `root_execution_id` 로 그 트리의 실행 줄을 합쳐 얻는다([`proactive-check.md`](../proactive-check.md) 의 「비용과 효과」).

`root_execution_id` 는 유일하다. 살펴보기 트리를 가리는 기준이라 실행 줄 하나가 두 살펴보기에 속하지 않게 한다. 실행 줄을 만들기 전에 실패하면 비어 있다.
`writes_allowed` 는 시작할 때 그 에이전트의 「먼저 살펴보기에 쓰기 도구 허용」 값을 옮겨 적는다. 도중에 관리자가 바꿔도 그 살펴보기의 경계는 옮겨 적은 값이 정한다(ADR-082).
`report_opened_at` 은 요청자가 「보고 열기」 를 누르거나, 점검 대화를 처음 읽거나, 그 대화에 메시지를 보낸 때 채운다.

색인은 마지막 살펴보기 읽기, session 을 바꿀지 세기, 열지 않은 보고 찾기에 쓴다.

`trigger` 는 MySQL 의 예약어라 칸 이름을 `trigger_type` 으로 둔다.

## proactive_check_finding

결과 블록의 발견 하나다. 다음 살펴보기가 최근에 알린 것을 입력에 싣는 데 쓰고, 「지금」 화면과 할 일로 이을 때 원래 기록이 된다.
살펴보기 줄을 지우면 함께 지운다(`ON DELETE CASCADE`).
`conversation_id` 는 입력에 실을 발견을 대화로 읽으려고 둔다. `proactive_check.conversation_id` 와 같다.

발견의 이유, 사실, 추정, 다음 행동은 대화 메시지에만 있고 이 표에 두지 않는다.
같은 글을 두 곳에 두면 사용자가 대화를 지워도 한쪽에 남는다.

## proactive_check_problem

결과 블록 버전 3의 문제 후보 하나다([ADR-093](../../../backend/docs/adr/ADR-093-문제-찾기는-살펴보기-결과의-문제-후보로-받고-control-plane-이-근거와-중복을-결정적으로-검사한다.md)).
받아들인 것과 버린 것을 모두 남긴다. 다음 살펴보기의 중복 판정과 입력에 쓰고, 우선순위를 정하는 다음 단계가 읽는다.
칸의 뜻과 검사는 [`proactive-check.md`](../proactive-check.md) 의 「문제 후보」 가 갖는다.
살펴보기 줄을 지우면 함께 지운다(`ON DELETE CASCADE`).

후보의 글은 모델이 쓴 글이고 대화에 그리지 않는다. 다섯 칸 보고(`report_json`)처럼 검사한 모양을 그대로 남긴다.
원문 본문과 커넥터 응답은 남기지 않는다.

## `proactive_value_evaluation`

가치 평가 시도 하나다. [가치 평가](../value-evaluation.md)가 입력과 결과, replay 계약을 갖는다.
후보가 나온 살펴보기나 요청 사용자를 지우면 평가도 함께 지운다. 전체 개인 문맥과 원시 모델 응답을 저장하지 않는다.

## `proactive_autonomy_decision`

행동 정책의 판정 하나다. [행동 정책](../autonomy-policy.md)이 수준과 까닭, 실행 키 계약을 갖는다.

`execution_key` 는 유일하다. 원천 살펴보기 하나에서 자동 실행이 한 번만 열리게 하는 장치다. `EXECUTE` 만 채운다.
`candidate_id` 는 FK 가 없다. 평가 스냅샷의 후보 식별자라 지금의 후보 줄이 지워져도 판정이 남는다.
사용자, 평가, 원천 살펴보기를 지우면 함께 지운다. 시작한 살펴보기(`execution_check_id`)를 지우면 그 칸만 비운다(`ON DELETE SET NULL`).
`inputs_json` 에는 판정에 쓴 값만 두고 축 설명과 후보 글은 두지 않는다.

## `user_autonomy_preference`

줄이 없으면 모두 꺼짐이다. 사용자를 지우면 함께 지운다.
다른 종류의 자동 실행을 열 때 칸을 더한다. 한 칸이 여러 종류의 허락을 뜻하지 않게 한다.

## `proactive_loop_setting`

사용자가 에이전트마다 매일 루프를 켠 설정이다. [매일 루프](../proactive-loop.md)의 「사용자 설정」 이 뜻을 갖는다.
줄이 없으면 꺼짐이다. 사용자나 에이전트를 지우면 함께 지운다.

`(user_id, agent_id)` 는 유일하다. 하루 상한을 셀 때 그 사용자의 줄을 모두 쓰기 잠금으로 읽는다.

## `proactive_loop_run`

매일 깨우기 살펴보기 하나를 잇는 시도다. 상태와 순서는 [매일 루프](../proactive-loop.md)가 갖는다.

`source_check_id` 는 유일하다. 살펴보기 하나에 시도 하나라서, 서버가 도중에 멈춰도 같은 원천에서 평가와 판정이 두 번 생기지 않는다.
사용자나 원천 살펴보기를 지우면 함께 지운다. 이 시도가 만든 평가를 지우면 `evaluation_id` 만 비운다(`ON DELETE SET NULL`). 기동 때 닫은 줄도 비어 있다.
글과 원문, provider 이름은 두지 않는다. provider 는 평가의 `evidence_json` 이 갖는다.
