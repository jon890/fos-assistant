# rule-85

DB 테이블과 컬럼 이름은 lower_snake_case로 쓴다.

검사: [스키마 이름 검사](../../backend/src/test/java/com/bifos/assistant/SchemaIdentifiersMysqlTest.java)

## 까닭

Java 필드 이름과 DB 식별자의 대응을 예측할 수 있고 쿼리마다 이름 모양을 다시 확인하지 않아도 된다.

## 예외와 검토 범위

Flyway를 끝까지 적용한 현재 스키마의 테이블과 컬럼을 검사한다. 첫 글자는 영문 소문자이며 이후 소문자·숫자를 밑줄 하나로 구분한다.
인덱스와 제약 이름은 범위 밖이다. 기존 기본 키 이름을 id로 바꾸라는 규칙이 아니다.
기준 파일 없이 모든 대상에 적용한다.

## 이 저장소의 사례

[스키마 검사 fixture는 실제 MySQL에 정상·위반 테이블과 컬럼을 만들고 같은 INFORMATION_SCHEMA 검사로 판정한다.](../../backend/src/test/java/com/bifos/assistant/SchemaIdentifiersMysqlTest.java)
