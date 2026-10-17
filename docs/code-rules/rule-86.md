# rule-86

운영 코드에 필드 주입을 두지 않는다.

검사: [소스 선언 검사](../../backend/src/test/java/com/bifos/assistant/architecture/SourceCodeRules.java)

## 까닭

생성자에 필요한 의존성이 드러나면 불완전한 객체를 만들기 어렵고 Spring 없이도 테스트할 수 있다.

## 예외와 검토 범위

운영 Java 소스의 필드에 붙은 Spring @Autowired·@Value와 Jakarta·javax @Inject를 금지한다.
생성자와 메서드, 파라미터의 annotation은 이 검사의 범위 밖이다. 테스트 코드의 필드 주입은 허용한다.
기준 파일 없이 모든 대상에 적용하며 생성자 모양의 Lombok 전환은 기존 Checkstyle 규칙이 맡는다.

## 이 저장소의 사례

[소스 검사 fixture는 필드 주입을 거절하고 생성자·파라미터·메서드 주입을 허용한다.](../../backend/src/test/java/com/bifos/assistant/architecture/SourceCodeRulesTest.java)
