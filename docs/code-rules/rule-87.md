# rule-87

엔티티에 Lombok @Data·@Setter·@EqualsAndHashCode·@ToString을 붙이지 않는다.

검사: [소스 선언 검사](../../backend/src/test/java/com/bifos/assistant/architecture/SourceCodeRules.java)

## 까닭

엔티티의 상태 변경은 뜻이 드러나는 메서드를 거친다. 자동으로 만든 동등성·문자열 메서드는 지연 로딩과 양방향 참조를 건드릴 수 있다.

## 예외와 검토 범위

운영 Java 소스의 Jakarta @Entity 타입과 그 타입의 필드에 붙은 네 annotation을 금지한다.
@Getter·@RequiredArgsConstructor·보호 기본 생성자는 허용하며 일반 DTO는 범위 밖이다.
Lombok이 소스 처리 중 annotation을 지울 수 있으므로 바이트코드 대신 소스 AST를 읽는다. 기준 파일은 두지 않는다.

## 이 저장소의 사례

[소스 검사 fixture는 금지 annotation 네 개와 필드 @Setter를 거절하고 엔티티의 Getter·생성자를 허용한다.](../../backend/src/test/java/com/bifos/assistant/architecture/SourceCodeRulesTest.java)
