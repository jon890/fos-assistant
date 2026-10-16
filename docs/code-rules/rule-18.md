# rule-18

트랜잭션 경계는 application에 둔다.

검사: [TRANSACTIONAL_ONLY_IN_APPLICATION](../../backend/src/test/java/com/bifos/assistant/architecture/ArchitectureRules.java)

## 까닭

트랜잭션 경계는 유스케이스를 아는 층이 정한다.

## 예외와 검토 범위

Spring과 Jakarta의 Transactional을 모두 검사한다.

## 이 저장소의 사례

[MemoryCollectionService.java](../../backend/src/main/java/com/bifos/assistant/memory/application/MemoryCollectionService.java)에서 확인한다.
MemoryCollectionService는 사용자 요청을 처리하는 application 서비스다.
