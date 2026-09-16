import { redirect } from "next/navigation";
import { auth } from "@/auth";
import { callControlPlane } from "@/lib/control-plane";

type Execution = {
  id: number;
  provider: string | null;
  model: string | null;
  costMode: string;
  status: string;
  errorCode: string | null;
  inputTokens: number | null;
  cachedInputTokens: number | null;
  outputTokens: number | null;
  latencyMs: number;
  estimatedCostMicros: number | null;
  startedAt: string;
};

function formatCost(execution: Execution): string {
  if (execution.costMode === "SUBSCRIPTION") return "구독 (미산정)";
  if (execution.estimatedCostMicros === null) return "산정 전";
  return `${(execution.estimatedCostMicros / 1_000_000).toFixed(4)}`;
}

export default async function UsagePage() {
  const session = await auth();
  if (!session?.user?.email) {
    redirect("/signin");
  }

  const result = await callControlPlane<Execution[]>("/api/v1/usage/executions?limit=50");
  if (!result.ok) {
    return <p className="text-sm">{result.message}</p>;
  }

  const executions = result.data;
  return (
    <>
      <h1 className="mb-4 text-lg font-semibold">사용량</h1>
      {executions.length === 0 ? (
        <p className="text-sm" style={{ color: "var(--muted)" }}>
          아직 실행 기록이 없다.
        </p>
      ) : (
        <div className="overflow-x-auto">
          <table className="w-full text-left text-sm">
            <thead style={{ color: "var(--muted)" }}>
              <tr>
                <th className="py-2 pr-4">시각</th>
                <th className="py-2 pr-4">모델</th>
                <th className="py-2 pr-4">상태</th>
                <th className="py-2 pr-4">입력</th>
                <th className="py-2 pr-4">캐시</th>
                <th className="py-2 pr-4">출력</th>
                <th className="py-2 pr-4">소요</th>
                <th className="py-2">비용</th>
              </tr>
            </thead>
            <tbody>
              {executions.map((execution) => (
                <tr key={execution.id} className="border-t" style={{ borderColor: "var(--border)" }}>
                  <td className="py-2 pr-4">{new Date(execution.startedAt).toLocaleString("ko-KR")}</td>
                  <td className="py-2 pr-4">
                    {execution.provider ?? "-"} / {execution.model ?? "-"}
                  </td>
                  <td className="py-2 pr-4">{execution.errorCode ?? execution.status}</td>
                  <td className="py-2 pr-4">{execution.inputTokens ?? "-"}</td>
                  <td className="py-2 pr-4">{execution.cachedInputTokens ?? "-"}</td>
                  <td className="py-2 pr-4">{execution.outputTokens ?? "-"}</td>
                  <td className="py-2 pr-4">{execution.latencyMs} ms</td>
                  <td className="py-2">{formatCost(execution)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </>
  );
}
