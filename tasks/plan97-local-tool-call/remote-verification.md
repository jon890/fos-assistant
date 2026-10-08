# 원격 검증

| 선행 조건 | 실행 위치 | 확인 | 기대값 |
| --- | --- | --- | --- |
| PR push | GitHub CI | backend, web, e2e, unit, hermes, quality, public-safe 와 브라우저 검사 | 필수 검사 통과 |
| Control Plane 배포 | 코디네이터가 운영 저장소 절차로 실행 | 새 session 에서 같은 서버의 읽기 도구 두 개가 필요한 질문을 보내 호출 기록을 확인 | 각 tool_call 의 tools 배열이 한 항목이고 exactly one entry for local tools 거절과 그 재호출이 없음 |
| Control Plane 배포 | 코디네이터가 운영 저장소 절차로 실행 | 기존 대화의 새 turn 과 Control Plane 자식 실행을 확인 | 공통 단건 지침을 받고 단건 호출함. Hermes 네이티브 자식은 별도로 확인 |
