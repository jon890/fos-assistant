export type ConnectionStatus = "DISCONNECTED" | "PENDING" | "READY";
export type AccountbookFamily = { uuid: string; name: string };

/** 가계부 연결 화면이 보여 주는 현재 사용자의 상태다. 토큰 원문은 이 형식에 없다. */
export type AccountbookConnection = {
  status: ConnectionStatus;
  tokenPrefix: string | null;
  familyUuid: string | null;
  checkedAt: string | null;
  agentCode: string | null;
  restartRequired: boolean;
};

/** 관리자가 재시작 반영을 확인할 수 있는 다른 사용자의 연결이다. */
export type AdminAccountbookConnection = {
  userId: number;
  displayName: string;
  status: ConnectionStatus;
  agentCode: string | null;
  restartRequired: boolean;
};
