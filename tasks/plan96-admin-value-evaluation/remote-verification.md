# 원격 검증

| 선행 조건 | 실행 위치 | 명령 | 기대값 |
|---|---|---|---|
| 머지와 배포 뒤, 운영 설치에 시스템 판단 profile 과 `assistant.value-evaluation` 의 `enabled`, `profile` 을 넣은 뒤 | 운영 웹의 `/admin/agents/{code}` | 받아들인 문제 후보가 있는 내 살펴보기에서 「이 살펴보기 평가하기」 를 누른다 | 절에 결과 상태와 후보별 축 선택, 행동 수준과 까닭 코드가 보이고 새로 고쳐도 남는다 |
| 위 확인 뒤 | 운영 저장소 문서의 읽기 전용 DB 조회 | 그 살펴보기의 `proactive_value_evaluation` 과 `proactive_autonomy_decision` 줄을 센다 | 각각 1줄 이상 |
