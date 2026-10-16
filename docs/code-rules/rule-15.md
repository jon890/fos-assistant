# rule-15

컨트롤러 안에 record를 두지 않는다.

검사: [CONTROLLERS_HAVE_NO_NESTED_RECORDS](../../backend/src/test/java/com/bifos/assistant/architecture/ArchitectureRules.java)

## 까닭

HTTP 데이터 모양을 재사용할 때 컨트롤러를 import하지 않게 한다.

## 예외와 검토 범위

presentation 패키지의 요청과 응답은 *Dtos.java 하나에 모은다.
패키지당 하나인지는 리뷰로 확인한다.

## 이 저장소의 사례

[AgentAdminDtos.java](../../backend/src/main/java/com/bifos/assistant/agent/admin/presentation/AgentAdminDtos.java)에서 확인한다.
AgentAdminDtos는 관리자 컨트롤러 밖에서 HTTP 요청과 응답 record를 모은다.
