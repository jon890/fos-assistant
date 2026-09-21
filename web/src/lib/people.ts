/**
 * 관리 화면이 다루는 한 사람이다.
 *
 * <p>Control Plane 의 허용 목록 한 줄과 같은 모양이다.
 */
export type Person = {
  id: number;
  email: string;
  displayName: string;
  hermesProfile: string;
  /** 거짓이면 로그인할 수 없다. 이미 만들어진 profile 과 에이전트는 그대로 남는다. */
  enabled: boolean;
  /** 거짓이면 아직 한 번도 들어오지 않은 사람이다. 그 사람의 에이전트도 아직 없다. */
  joined: boolean;
};
