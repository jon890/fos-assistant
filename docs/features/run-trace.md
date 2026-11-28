# 실행 추적

실행 하나가 모델을 부른 차례와 시간, 토큰, 도구마다의 시간과 실패 원인을 실행 기록에 남겨 「왜 느렸는지」 를 보이는 기능이다.

covers: `hermes/plugins/fos-ctx/run_trace.py`, `backend/src/main/java/com/bifos/assistant/orchestration/application/RunTraceIntake.java`, `backend/src/main/java/com/bifos/assistant/mcp/presentation/RunTraceController.java`, `backend/src/main/java/com/bifos/assistant/mcp/application/dto/RunTraceBatch.java`, `backend/src/main/java/com/bifos/assistant/usage/execution/application/ToolTrace*`, `backend/src/main/java/com/bifos/assistant/hermes/infra/ToolElapsed.java`

요구와 결정은 스펙 [`run-trace/spec.md`](run-trace/spec.md), 모듈 경계와 데이터 흐름은 설계 [`run-trace/design.md`](run-trace/design.md) 가 갖는다.
지금은 도구의 걸린 시간과 실패한 도구의 오류 요약을 남긴다. 실행 트리 화면 표시와 모델 호출 기록은 아직 구현하지 않았다.
지금의 실행 기록과 실행 트리는 [`execution.md`](execution.md) 가 갖는다.
