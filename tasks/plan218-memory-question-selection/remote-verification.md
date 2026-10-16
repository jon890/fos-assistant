# 원격 검증

임베딩 서비스의 구성과 주소, 측정 명령은 운영 저장소(`fos-home-infra`)가 갖는다. 이 표에는 무엇을 확인하는지와 결과 기준만 적는다.

| 선행 조건 | 시점 | 실행 위치 | 명령 | 기대값 |
|---|---|---|---|---|
| 없음 | PR 전 | 임베딩 서비스를 띄운 개발 머신 | `cd backend && MEMORY_EMBEDDING_BASE_URL=<개발 머신의 임베딩 서비스> MEMORY_EMBEDDING_MODEL=<BGE-M3 또는 KURE-v1> ./gradlew memoryEmbeddingEval` 를 두 모델로 한 번씩 | 두 보고서의 `hybrid` 전체 recall@5 가 `factsOn` 이상이고 `RECALL_GAP` recall@5 가 `factsOn` 보다 높다. 권한 경계 노출 0. `semanticMinSimilarity` 기본값을 이 결과로 정해 PR 본문에 두 모델의 값과 함께 적는다 |
| 배포 | PR 뒤 | 운영 서버, 운영 저장소의 절차 | 운영 저장소의 임베딩 서비스 지연 측정 절차(질문 길이의 글 100개 이상을 한 건씩 보낸다) | 질문 임베딩 p95 가 `assistant.memory.embedding.query-timeout` 안이다. 넘으면 더 작은 모델로 내리거나 예산을 다시 정한다. 결과 수치만 `docs/features/memory.md` 에 적는다 |
| 배포 | PR 뒤 | 운영 서버, 운영 저장소의 절차 | 임베딩을 켠 뒤 채우는 작업이 끝날 때까지 기다리고, 실제 대화 한 번을 왕복시킨다 | 그 실행의 선택 근거에 `search-hybrid-v1` 줄이 있다. 임베딩 서비스를 멈춘 상태의 다음 대화는 답하고 근거가 `search-keyword-v1` 이다 |
