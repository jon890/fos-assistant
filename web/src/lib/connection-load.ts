import {
  readConnection,
  readConnectors,
  type ConnectorConnection,
  type ConnectorSummary,
} from "@/lib/connection";

/** 연결 하나의 화면이 읽은 상태다. */
export type Loaded =
  | { kind: "loading" }
  | { kind: "notFound" }
  | { kind: "failed"; message: string }
  | {
      kind: "ready";
      connector: ConnectorSummary | null;
      connection: ConnectorConnection;
    };

/** 마지막 확인 시각을 한국 시간으로 보인다. */
export function formatChecked(value: string) {
  return new Intl.DateTimeFormat("ko-KR", {
    dateStyle: "medium",
    timeStyle: "short",
    timeZone: "Asia/Seoul",
  }).format(new Date(value));
}

/** 카탈로그와 내 연결을 함께 읽는다. */
export async function fetchLoaded(id: string): Promise<Loaded> {
  const [catalog, connection] = await Promise.all([
    readConnectors(),
    readConnection(id),
  ]);
  if (!connection.ok) {
    return connection.code === "CONNECTOR_NOT_FOUND"
      ? { kind: "notFound" }
      : { kind: "failed", message: connection.message };
  }
  // 카탈로그를 읽지 못한 것을 목록에서 빠진 것으로 보이면 일시 장애에 해제만 남는다.
  if (!catalog.ok) return { kind: "failed", message: catalog.message };
  const connector = catalog.data.find((item) => item.id === id) ?? null;
  return { kind: "ready", connector, connection: connection.data };
}
