## ADR-20261117 / native-delegation-wake: Hermes 내장 위임의 끝은 profile 플러그인 hook 이 Control Plane 에 알리고, Control Plane 이 맡긴 에이전트의 결과와 같은 자동 turn 으로 전한다

- **status**: `accepted`
- Date: 2026-11-17
- **decision-type**: `architecture`
- **areas**: `data, operations`
- **현재 규칙의 원본**: 설계 [`docs/features/agent-team/design.md`](../features/agent-team/design.md) 의 「PR6 내장 위임 결과」, 스펙 [`docs/features/agent-team/spec.md`](../features/agent-team/spec.md) 의 범위 6(사용자 확인 2026-11-17)
- [ADR-040](../../backend/docs/adr/ADR-040-위임-결과는-control-plane-이-부모-대화의-다음-turn-을-열어-전한다.md) 의 깨우기를 Hermes 내장 위임(`delegate_task`)으로 넓힌다. ADR-040 의 결정은 그대로다. 같은 ADR 의 「대안 기각」 에서 내장 위임을 기각한 것은 다른 profile 에 맡기는 길로서였고, 이 ADR 은 모델이 이미 쓰는 내장 위임의 결과를 받는 길이다

### 결정

**fos-ctx 가 `subagent_stop` hook 에서 자식의 끝 소식을 Control Plane 에 보내고, Control Plane 은 그 결과를 하위 에이전트 등록 줄(`hermes_session_binding`)에 적은 뒤 `AutoTurnResultSource` 로 ADR-040 의 자동 turn 에 넣는다.**

| 항목 | 정한 것 |
| --- | --- |
| 깨우는 자식 | 대화의 에이전트가 대화 turn 에서 부른 최상위 자식. 등록할 때 정하고, 등록 본문이 끝 소식을 보낸다고 밝힌 자식(`reports_stop`)만 |
| 모으기 | 끝 소식 뒤 5초 기다렸다 깨운다. Hermes 가 한 호출의 hook 을 잇달아 부른다 |
| 결과 글 | Control Plane 이 저장한 글을 입력에 넣는다. Hermes 이력의 결과 줄에 기대지 않는다 |
| 끝 소식이 없을 때 | 1분마다 도는 훑기가 1시간 뒤 알림 줄을 남긴다. 중지한 turn 의 자식도 훑기가 알림 줄로 끝낸다 |
| 끄기 | ADR-040 의 설정 `assistant.delegation-wake.enabled` 하나 |

### 맥락

운영(2026-11-17)에서 비서가 `delegate_task` 를 쓰면 루트 실행이 먼저 끝나고, 자식이 끝나도 대화에 답이 오지 않았다.
Hermes 는 API run 의 비동기 위임 완료로 새 run 이나 사건을 만들지 않고 부모 session 에 결과 줄 하나만 남긴다([`hermes/docs/hermes-contract.md`](../../hermes/docs/hermes-contract.md) 의 「API 위임 결과는 delivery 기록으로 남는다」). v0.23.2 도 같다.
자식의 끝을 알 수 있는 길은 profile 플러그인의 `subagent_stop` hook 과 session 메시지 조회다. hook 은 결과 줄을 남기기 전에, 한 호출의 자식이 모두 끝난 뒤 잇달아 불린다. hook 에는 호출 번호도 맡긴 일의 글도 없다.
Control Plane 에는 다른 결과를 자동 turn 에 넣는 자리(`AutoTurnResultSource`)와 하위 에이전트 등록 경로(ADR-037)가 이미 있다.

### 대안 기각

- **session 메시지를 주기적으로 조회한다**: 대화마다 Hermes 를 폴링해야 하고, 결과 줄이 생긴 시각으로만 알 수 있어 성공과 실패를 구분하기 어렵다.
- **`delegation.gateway_mode: inline` 으로 같은 답 안에서 기다린다**: v0.23.2 에만 있고, 사용자가 고르지 않았다(스펙 DECISION28).
- **Hermes 이력의 결과 줄만 믿고 짧은 지시로 turn 을 연다**: hook 이 결과 줄보다 먼저 와 그 줄이 아직 없을 수 있다. 다시 전달(ADR-075)에 쓸 글도 없다.
- **`subagent_start` 등록만으로 기한을 센다**: 끝 소식을 보내는 플러그인이 배포되기 전에는 모든 위임이 1시간 뒤 알림을 남긴다. 기존 설정이 켜진 채 배포되므로 막아야 한다.
- **결과를 새 테이블에 둔다**: 테이블을 늘리지 않기로 했다(사용자 결정 2026-11-01). 자식 하나가 등록 줄 하나다.

### 결과

- 얻는 것
  - 사용자가 메시지를 보내지 않아도 내장 위임의 결과로 다음 답이 온다. 깨우기, 상한, 다시 전달, 대기 줄과의 순서가 맡긴 에이전트의 결과와 같다.
  - Hermes core 를 고치지 않는다.
- 감당할 것
  - 플러그인 hook 의 인자와 불리는 때에 기댄다. Hermes 를 올릴 때 계약 문서의 표를 다시 확인한다.
  - Control Plane 은 어느 자식이 같은 호출인지 모른다. 5초 안에 함께 끝난 다른 호출은 한 turn 에 들고, 기한 알림은 turn 마다 한 줄이다.
  - 같은 결과를 모델이 입력과 Hermes 이력으로 두 번 볼 수 있다.
  - 끝 소식은 Hermes 가 결과 줄을 남기기 전에 온다. 끝 소식 30초 안에 시작한 사용자 turn 은 읽지 않은 것으로 보아 그 뒤에 자동 turn 을 한 번 더 연다. 결과를 놓치지 않는 쪽을 고른다.
  - 끝 소식을 잃으면 그 자식은 1시간 뒤 알림 줄로 끝나고, 결과는 다음 사용자 메시지에서 Hermes 이력으로만 쓰인다.
- **확인 방법**: 설계의 「검증 전략」 과 스펙의 「배포 뒤 왕복 확인」
