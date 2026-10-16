# rule-14

Jackson 2 core·databind를 쓰지 않는다.

검사: [NO_JACKSON_2_DATABIND](../../backend/src/test/java/com/bifos/assistant/architecture/ArchitectureRules.java)

## 까닭

Spring Boot 4의 Jackson 3과 Jackson 2 databind를 섞지 않는다.

## 예외와 검토 범위

com.fasterxml.jackson.annotation은 Jackson 3에서도 쓰므로 허용한다.
core와 databind만 금지한다.

## 이 저장소의 사례

[ArchitectureRules.java](../../backend/src/test/java/com/bifos/assistant/architecture/ArchitectureRules.java)에서 확인한다.
NO_JACKSON_2_DATABIND는 annotation 의존은 남기고 Jackson 2 core와 databind 의존을 거절한다.
