-- 대화의 DEFAULT 선택(에이전트 기본값으로 돌아가기)을 선택 없음으로 옮긴다(ADR-20261101 / model-setting).
-- 에이전트 기본 모델을 없애 DEFAULT 모드가 뜻이 없어졌다. 선택 없음은 사용자 기본 칸, 그룹 기본 칸 차례로 돈다.
-- DEFAULT 는 단계를 적지 않았지만 지난 요청이 남겼을 수 있어 단계 칸도 함께 비운다. 칸 이름과 모델 이름은 적지 않는다.
-- 칸을 바꾸는 파일이 아니라 줄만 고치는 파일이다. 에이전트 컬럼 삭제(V20261031171510)와 섞지 않아, 삭제만 미룰 수 있다.
UPDATE conversation
   SET model_selection_mode = NULL,
       model_tier = NULL
 WHERE model_selection_mode = 'DEFAULT';
