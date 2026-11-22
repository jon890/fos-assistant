# rule-88

runtime은 hermes에 의존하지 않는다.

검사: [RUNTIME_DOES_NOT_KNOW_HERMES](../../backend/src/test/java/com/bifos/assistant/architecture/ArchitectureRules.java)

## 까닭

`runtime` 은 런타임에 묶이지 않는 실행 port 와 타입을 갖는다.
다른 Agent Runtime 의 구현이 `runtime` 만 쓰고 붙으려면 이 패키지에 Hermes 의 타입이 들어오면 안 된다.

## 예외와 검토 범위

예외는 없다. 주석이 Hermes 를 예로 드는 것은 검사하지 않는다.
port 의 주석이 런타임 하나에만 맞는 동작을 약속하는지는 리뷰가 본다.

## 이 저장소의 사례

[ADR-20261121 / acp-runtime](../adr/ADR-20261121-acp-runtime.md)에서 확인한다.
Hermes 구현은 `hermes` 패키지에서 `runtime` 의 port 를 구현한다.
