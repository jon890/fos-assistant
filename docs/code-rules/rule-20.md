# rule-20

MessageDigest 생성은 Sha256만 맡는다.

검사: [MESSAGE_DIGEST_ONLY_IN_SHA256](../../backend/src/test/java/com/bifos/assistant/architecture/ArchitectureRules.java)

## 까닭

해시 생성의 알고리즘과 오류 처리를 한 곳에 모은다.

## 예외와 검토 범위

공유 구현 밖에서 MessageDigest를 직접 생성하지 않는다.

## 이 저장소의 사례

[Sha256.java](../../backend/src/main/java/com/bifos/assistant/shared/util/Sha256.java)에서 확인한다.
Sha256이 MessageDigest의 생성과 SHA-256 계산을 모은다.
