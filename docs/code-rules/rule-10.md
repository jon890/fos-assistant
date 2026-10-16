# rule-10

orchestration은 mcp에 의존하지 않는다.

검사: [ORCHESTRATION_DOES_NOT_DEPEND_ON_MCP](../../backend/src/test/java/com/bifos/assistant/architecture/ArchitectureRules.java)

## 까닭

MCP 진입점이 실행 흐름을 부르고 실행 흐름이 진입점에 되돌아 의존하지 않게 한다.

## 예외와 검토 범위

호출 흐름은 backend 아키텍처 문서가 갖고 이 파일은 방향 규칙만 갖는다.

## 이 저장소의 사례

[ArchitectureRules.java](../../backend/src/test/java/com/bifos/assistant/architecture/ArchitectureRules.java)에서 확인한다.
ORCHESTRATION_DOES_NOT_DEPEND_ON_MCP는 실행 흐름에서 MCP로 되돌아가는 의존을 검사한다.
