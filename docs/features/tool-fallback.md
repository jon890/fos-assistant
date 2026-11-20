# 도구 실패 뒤 다른 방법

비서가 도구 한 번이 실패했을 때 바로 사용자에게 넘기지 않고, 다른 방법을 정해진 횟수까지 해 본 뒤에만 사용자에게 묻게 하는 기능이다.

covers: `backend/src/main/java/com/bifos/assistant/context/application/ContextAssembler.java`, `backend/src/main/java/com/bifos/assistant/mcp/application/`, `hermes/plugins/fos-ctx/`

요구와 결정은 스펙 [`tool-fallback/spec.md`](tool-fallback/spec.md) 가 갖는다.
지금은 스펙만 있다. 구현은 스펙의 「범위」 의 PR 순서로 들어온다.
