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
  /** 일반 요청으로 app_user와 기본 에이전트가 만들어졌는지 나타낸다. */
  joined: boolean;
  /** 마지막으로 로그인 완료를 기록한 시각이다. */
  lastLoginAt: string | null;
  /** 마지막으로 사용자가 보낸 대화 메시지의 시각이다. */
  lastConversationAt: string | null;
};
