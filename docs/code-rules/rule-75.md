# rule-75

application 직하의 클래스는 25개를 넘지 않는다.

검사: [APPLICATION_PACKAGE_SIZE](../../backend/src/test/java/com/bifos/assistant/architecture/ArchitectureRules.java)

## 까닭

한 패키지가 이만큼 커지면 이름으로만 찾아야 하고 하위 일 사이의 의존은 검사할 수 없다.

## 예외와 검토 범위

최상위 타입만 센다. 값과 인터페이스를 dto와 port로 옮긴 뒤에도 넘으면 하위 기능으로 나눈다.
하위 기능으로 나누는 기준은 이동 전 application 직하가 30개 이상인 기능이다. 이 기준은 ADR에 있다.

## 이 저장소의 사례

[ADR-20261015-package-layout.md](../../backend/docs/adr/ADR-20261015-package-layout.md)에서 확인한다.
ADR-20261015 / package-layout의 하위 기능 표가 나눈 기능과 그 순서를 갖는다.

결정과 근거는 [ADR-20261015 / package-layout](../../backend/docs/adr/ADR-20261015-package-layout.md)가, 책임과 실행 절차는 [code-architecture.md](../../backend/docs/code-architecture.md)가 갖는다.
