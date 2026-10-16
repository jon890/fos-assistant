# rule-11

mcp는 Hermes 호출 타입을 직접 쓰지 않는다.

검사: [MCP_DOES_NOT_CALL_HERMES](../../backend/src/test/java/com/bifos/assistant/architecture/ArchitectureRules.java)

## 까닭

MCP는 실행 권한을 판정하고 실제 Hermes 호출은 실행 흐름에 맡긴다.

## 예외와 검토 범위

HermesProfileName 같은 이름 규칙 값은 허용한다.
Client 접미사와 호출 타입 선택은 검사 코드가 갖는다.

## 이 저장소의 사례

[agent-skill.md](../../docs/features/agent-skill.md)에서 확인한다.
다른 에이전트에게 맡기는 MCP 호출은 실행 흐름을 거쳐 Hermes에 전달한다.
