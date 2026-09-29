## ADR-035: 대화창의 스킬 커맨드는 Control Plane 이 해석해 Hermes 에 넘긴다

- **status**: `accepted`
- **결정**: 사용자가 입력창 맨 앞에 `/<스킬 이름> 할 일` 을 보내면 Control Plane 이 그것을 스킬 커맨드로 본다.
  이름이 그 에이전트의 켜진 스킬 목록에 있으면, Hermes 에는 「사용자가 그 스킬을 호출했다. `skill_view` 로 읽고 그 절차대로 할 일을 하라」는 입력을 보내고 호출 이력을 남긴다.
  목록에 없으면 Hermes 에 보내지 않고 거절한다.
  대화 기록에는 사용자가 친 글을 그대로 남기고 스킬 표시를 붙인다.
- **맥락**:
  - Hermes 의 CLI 와 채팅 gateway 는 `/<스킬>` 을 스킬 호출로 처리해 스킬 본문을 user 메시지에 넣는다(`agent/skill_commands.py` 의 `build_skill_invocation_message`).
  - **API server 의 `/v1/runs` 는 입력의 `/` 를 해석하지 않는다.** gateway 의 명령 처리를 거치지 않아 `/foo 할 일` 이 그대로 모델에 간다. 2026-09-29 에 v0.21.5(태그 `v2026.9.24`) 소스로 확인했다. 근거는 [`hermes/tools-and-skills.md`](../hermes/tools-and-skills.md) 의 「스킬 커맨드와 API server」 다.
  - 스킬 목록은 대시보드 `GET /api/skills?profile=` 로 얻는다. API server 의 `GET /v1/skills` 는 v0.21.5 에서 늘 500 이다.
  - 대시보드 목록은 전역 `skills.disabled` 만 반영하고 `skills.platform_disabled.api_server` 는 반영하지 않는다.
- **대안 기각**:
  - **글자 그대로 Hermes 에 넘긴다.** 모델이 `/foo` 를 평문으로 받는다. 같은 이름의 스킬을 스스로 읽을 수도 있지만 그것을 보장하는 코드가 없다.
  - **profile 플러그인의 `pre_llm_call` 에서 스킬 본문을 주입한다.** gateway 와 같은 메시지를 만들고 toolset 에 기대지 않는다. 대신 플러그인 운영 부담이 늘고 원래 `/스킬` 글자가 입력에 남는다. 첫 방식으로 모델이 `skill_view` 를 부르지 않는 일이 실제로 보이면 다시 검토한다.
  - **없는 이름도 그대로 보낸다.** gateway 는 모르는 명령을 모델에 보내지 않고 `Unknown command` 로 답한다. 같게 한다.
- **결과**:
  - 얻는 것:
    - 입력창에서 스킬을 골라 바로 그 절차를 돌린다
    - 커맨드로 부른 스킬은 Control Plane 이 정확히 알아 이력이 확실하다
    - 목록과 판별과 변환이 모두 우리 코드에 있어 자동완성을 만들기 쉽다
  - 감당할 것:
    - `skills` toolset 이 꺼진 에이전트에서는 커맨드를 쓸 수 없다. 스킬을 올린 에이전트는 `skills` toolset 을 켠다([ADR-034](ADR-034-올린-스킬은-control-plane-이-버전-디렉터리에-쓰고-hermes-는-읽기만-한다.md))
    - 모델이 도구를 한 번 더 부른다. 스킬 본문은 도구 결과로 대화 기록에 남아 뒤 turn 의 입력을 키운다. gateway 방식도 본문을 user 메시지에 두므로 이 점은 같다
    - `/usr/bin` 처럼 `/` 뒤가 곧바로 공백이 아닌 글은 커맨드로 보지 않는다. 이름 뒤에 공백이나 끝이 올 때만 커맨드다
    - Hermes 가 `GET /v1/skills` 를 고치면 목록 출처를 다시 검토한다
