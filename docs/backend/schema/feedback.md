# 판단 피드백

판단 피드백 사건을 저장하는 표다. 칸과 타입, FK, 색인은 `V20261007044901__decision_feedback_event.sql` 이 갖는다.
사건의 뜻, 기록 지점, 반응 읽기, 보관은 [`../decision-feedback.md`](../decision-feedback.md) 가 갖는다.

## decision_feedback_event

제안 하나의 사건 한 줄이다. 덧붙이기만 하고 고치지 않는다.
`subject_key` 가 같은 제안의 사건을 잇는 열쇠다. 지금 화면의 `itemKey` 와 같은 모양이다.

사용자를 지우면 함께 지운다(`ON DELETE CASCADE`). 다른 사용자는 관리자여도 읽지 못한다.
대화를 지우면 서비스가 `conversation_id` 로 그 대화의 사건을 지운다.
실행, 살펴보기, 행동 정책 판정을 지우면 그 번호 칸만 비운다(`ON DELETE SET NULL`).

제목, 본문, 인자, 결과 글, 모델이 쓴 글을 담는 칸이 없다. 바뀐 칸은 이름만 남기고 값은 담지 않는다.
색인은 읽기 모델, 대화 삭제, 보관 기간 정리가 쓴다.
