# rule-40

Java 식별자는 타입·메서드·값 종류별 표준 이름 모양을 따른다.

검사: [PackageName](../../backend/config/checkstyle/checkstyle.xml), [TypeName](../../backend/config/checkstyle/checkstyle.xml), [MethodName](../../backend/config/checkstyle/checkstyle.xml), [MemberName](../../backend/config/checkstyle/checkstyle.xml), [ParameterName](../../backend/config/checkstyle/checkstyle.xml), [LocalVariableName](../../backend/config/checkstyle/checkstyle.xml), [LocalFinalVariableName](../../backend/config/checkstyle/checkstyle.xml), [StaticVariableName](../../backend/config/checkstyle/checkstyle.xml), [LambdaParameterName](../../backend/config/checkstyle/checkstyle.xml), [RecordComponentName](../../backend/config/checkstyle/checkstyle.xml), [ClassTypeParameterName](../../backend/config/checkstyle/checkstyle.xml), [MethodTypeParameterName](../../backend/config/checkstyle/checkstyle.xml), [ConstantName](../../backend/config/checkstyle/checkstyle.xml)

## 까닭

이름만 보고 타입과 메서드, 상수를 구별할 수 있게 한다.

## 예외와 검토 범위

log는 Slf4j가 만드는 이름이므로 ConstantName에서 허용한다.
패턴은 Checkstyle이 소유한다.

## 이 저장소의 사례

[checkstyle.xml](../../backend/config/checkstyle/checkstyle.xml)에서 확인한다.
ConstantName은 Lombok이 만드는 log 이름을 별도로 허용한다.
