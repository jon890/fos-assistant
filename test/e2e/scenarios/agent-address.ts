/**
 * 에이전트의 Hermes 주소를 화면에서 고치는 길을 본다.
 *
 * <p>대역을 하나 더 띄워 공유 gateway 를 대신한다. 주소를 그쪽으로 옮긴 뒤 실행이 실제로 새 주소로
 * 나가는지가 이 시나리오의 본체다. 저장된 값만 보면 값은 바뀌었는데 실행은 옛 주소로 가는 것을
 * 잡지 못한다.
 */
import { call, expect, expectStatus, step, type Scenario } from "../harness.ts";
import { startFakeHermes } from "../fake-hermes.ts";
import { DAD_BINDING } from "./binding.ts";

type AdminAgent = { code: string; apiBaseUrl: string };
type Turn = { assistantText: string };

/** 이 시나리오가 끝낸 실행 수다. 사용량 시나리오가 그 수를 합계에 더한다. */
export const ADDRESS_TURNS = 1;

/** 아무도 듣지 않는 주소다. 확인이 실패해야 하는 것을 검사하는 데 쓴다. */
const UNREACHABLE = "http://127.0.0.1:1/p/dad";

async function addressOf(context: Parameters<Scenario["run"]>[0]): Promise<string> {
  const list = expectStatus(
    await call(context, "/admin/agents", { token: context.tokens.dad }),
    200,
    "에이전트 목록",
  ).json<AdminAgent[]>();
  const agent = list.find((each) => each.code === "dad");
  if (agent === undefined) throw new Error("dad 에이전트가 목록에 없다");
  return agent.apiBaseUrl;
}

function patch(
  context: Parameters<Scenario["run"]>[0],
  apiBaseUrl: string | null,
): Promise<{ status: number; body: string }> {
  return call(context, "/admin/agents/dad", {
    method: "PATCH",
    token: context.tokens.dad,
    body: {
      enabled: true,
      visibility: "PRIVATE",
      ownerEmail: "dad@example.com",
      apiBaseUrl,
    },
  });
}

export const agentAddressScenario: Scenario = {
  name: "에이전트 Hermes 주소 변경",

  async run(context) {
    const original = await addressOf(context);
    const gateway = await startFakeHermes(
      { [DAD_BINDING.profileName]: context.hermesProfileKey },
      "gateway",
    );

    try {
      step("주소를 비워 보내면 지금 값이 그대로 남는다");
      expectStatus(await patch(context, null), 200, "주소 없는 변경");
      expect(await addressOf(context) === original, "주소를 비워 보냈는데 값이 바뀌었다");

      step("닿지 않는 주소는 저장하지 않고 무엇이 왔는지 알린다");
      const refused = await patch(context, UNREACHABLE);
      expect(refused.status === 400, `닿지 않는 주소가 거절되지 않았다: ${refused.status}`);
      expect(
        refused.body.includes("could not reach"),
        `무엇이 막혔는지 알리지 않았다: ${refused.body}`,
      );
      expect(await addressOf(context) === original, "확인이 실패했는데 주소가 바뀌었다");

      step("끝에 슬래시를 붙여 보내면 떼고 저장한다");
      const moved = `${gateway.baseUrl}/p/${DAD_BINDING.profileName}`;
      expectStatus(await patch(context, `${moved}/`), 200, "슬래시를 붙인 주소 변경");
      expect(await addressOf(context) === moved, "끝의 슬래시를 떼지 않았다");

      step("주소를 옮긴 뒤 실행이 새 주소로 나간다");
      const turn = expectStatus(
        await call(context, "/chat/messages", {
          method: "POST",
          token: context.tokens.dad,
          body: { text: "옮긴 주소 검사", agentCode: "dad" },
        }),
        200,
        "옮긴 주소로 보낸 대화",
      ).json<Turn>();
      expect(
        turn.assistantText.includes("fake hermes gateway"),
        `실행이 옛 주소로 나갔다: ${turn.assistantText}`,
      );
    } finally {
      step("다음 시나리오를 위해 원래 주소로 되돌린다");
      expectStatus(await patch(context, original), 200, "주소 복원");
      await gateway.close();
    }
  },
};
