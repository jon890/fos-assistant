import { FakeToss, stockInfo, sampleOrder, accountNo } from "../../../hermes/connectors/tossinvest/tests/support.ts";
import { OPERATIONS } from "../../../hermes/connectors/tossinvest/src/api-contract.ts";

// SDK의 실제 HTTP만 받는 loopback 목적지다. auth와 금융 요청을 따로 센다.
const fake = new FakeToss(new Set(OPERATIONS.map((operation) => operation.id)));
let mode = "ok";
fake.on("GET", "/api/v1/accounts", { result: [{ accountSeq: 7, accountNo, accountType: "BROKERAGE" }] });
fake.on("GET", "/api/v1/stocks", { result: [stockInfo("ABC", "합성 종목")] });
fake.on("GET", "/api/v1/orders/old", { result: { ...sampleOrder("old"), symbol: "ABC", quantity: "2", price: "12", orderAmount: null } });
for (const route of ["/api/v1/orders", "/api/v1/orders/old/modify", "/api/v1/orders/old/cancel"]) {
  fake.routes.set(`POST ${route}`, async () => {
    if (mode === "timeout") await Bun.sleep(2500);
    if (["429", "500", "400", "400-unknown"].includes(mode)) {
      const status = mode === "400-unknown" ? 400 : Number(mode);
      const code = mode === "400" ? "invalid-request" : "bad-request";
      return new Response(JSON.stringify({ error: { requestId: "mock", code, message: "mock" } }), { status });
    }
    return new Response(JSON.stringify(mode === "malformed" ? { result: {} } : { result: { orderId: "new-official-id" } }), { headers: { "content-type": "application/json" } });
  });
}
for await (const line of console) {
  if (!line.trim()) continue;
  const command = JSON.parse(line);
  if (command.mode) mode = command.mode;
  if (command.reset) fake.requests.length = 0;
  console.log(JSON.stringify({ url: fake.url, auth: fake.seen("POST", "/oauth2/token").length,
    financial: fake.requests.filter((request) => request.method === "POST" && request.path.startsWith("/api/v1/orders")).length,
    deletes: fake.seen("DELETE").length }));
}
fake.stop();
