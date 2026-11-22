package com.bifos.assistant.quality.domain.type;

/** 확인·평가 한 번의 종류다. 문항 후보는 뒤 단계가 더한다. */
public enum QualityRunKind {
    /** 배포 뒤 기능별 확인. */
    DEPLOY_CHECK,
    /** 묶음 문항을 평가 전용 사용자로 돌리는 평가. */
    EVAL
}
