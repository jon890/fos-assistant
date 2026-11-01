-- 대화의 CUSTOM 선택(사용자가 provider, 모델, effort 를 직접 고른 것)을 선택 없음으로 옮긴다(ADR-20261101 / model-setting).
-- 사용자는 슬라이더 칸만 고르게 되어 CUSTOM 모드가 없어졌다. 선택 없음은 사용자 기본 칸, 그룹 기본 칸 차례로 돈다.
-- 직접 고른 모델과 effort 의 짝은 칸 하나에 맞춰지지 않아 가까운 칸으로 옮길 근거가 없다. 그래서 값도 함께 비운다.
-- 컬럼을 지우지 않고 행만 고친다. provider, 모델, effort 의 세 컬럼은 이 파일 뒤로 늘 비어 있고, 지우는 일은 따로 한다.
UPDATE conversation
   SET model_selection_mode = NULL,
       model_tier = NULL,
       model_provider = NULL,
       model = NULL,
       reasoning_effort = NULL
 WHERE model_selection_mode = 'CUSTOM';
