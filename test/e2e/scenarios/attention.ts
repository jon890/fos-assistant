/**
 * 실패한 turn 이 지금 화면의 실패 카드에 올라오고, 같은 대화에서 다시 성공하면 내려가는지 본다.
 *
 * <p>계약은 `docs/backend/attention.md` 의 「API」 가 갖는다. 응답에 실행의 오류 코드가 실리지 않는 것과,
 * 사이드바가 읽는 건수가 카드의 합과 같은 것도 함께 본다.
 */
import { call, expect, expectStatus, step, type Scenario } from "../harness.ts";

type Turn = { conversationId: string };
type Item = { itemKey: string; attention: string; conversationId: string | null };
type Card = { key: string; status: string; nowCount: number; items: Item[] };
type View = { nowCount: number; cards: Card[] };

export const attentionScenario: Scenario = {
  name: "지금 화면의 실패 카드",

  async run(context) {
    const token = context.tokens.dad;
    const started = expectStatus(
      await call(context, "/chat/messages", {
        method: "POST",
        token,
        body: { text: "주간 장보기 목록 정리", agentCode: "dad" },
      }),
      200,
      "실패시킬 대화의 첫 turn",
    ).json<Turn>();
    const itemKey = `conversation:${started.conversationId}`;

    step("붐벼서 실패한 turn 을 하나 남긴다");
    context.hermes.busy();
    try {
      expectStatus(
        await call(context, "/chat/messages", {
          method: "POST",
          token,
          body: { conversationId: started.conversationId, text: "목록을 다시 정리해 줘", agentCode: "dad" },
        }),
        429,
        "붐빌 때 보낸 turn",
      );
    } finally {
      context.hermes.clearBusy();
    }

    step("실패 카드에 그 대화가 NOW 로 있고 응답에 오류 코드가 없다");
    const viewed = expectStatus(await call(context, "/attention", { token }), 200, "지금 화면");
    expect(!viewed.body.includes("HERMES_BUSY"), `응답에 실행의 오류 코드가 실렸다:\n${viewed.body}`);
    const view = viewed.json<View>();
    const failure = failuresOf(view).find((item) => item.itemKey === itemKey);
    expect(failure !== undefined, `실패 카드에 ${itemKey} 가 없다:\n${viewed.body}`);
    expect(failure!.attention === "NOW", `실패 항목이 NOW 가 아니다: ${failure!.attention}`);
    expect(
      failure!.conversationId === started.conversationId,
      `실패 항목의 대화가 다르다: ${failure!.conversationId}`,
    );

    step("사이드바의 건수가 지금 화면의 건수와 같다");
    const summary = expectStatus(
      await call(context, "/attention/summary", { token }),
      200,
      "지금 화면 건수",
    ).json<{ nowCount: number }>();
    expect(
      summary.nowCount === view.nowCount,
      `건수가 다르다: summary ${summary.nowCount}, 지금 화면 ${view.nowCount}`,
    );

    step("같은 대화에서 다시 성공하면 그 항목이 사라진다");
    expectStatus(
      await call(context, "/chat/messages", {
        method: "POST",
        token,
        body: { conversationId: started.conversationId, text: "목록을 다시 정리해 줘", agentCode: "dad" },
      }),
      200,
      "다시 보낸 turn",
    );
    const resolved = expectStatus(await call(context, "/attention", { token }), 200, "해소 뒤 지금 화면");
    expect(
      !failuresOf(resolved.json<View>()).some((item) => item.itemKey === itemKey),
      `다시 성공했는데 실패 카드에 ${itemKey} 가 남았다:\n${resolved.body}`,
    );

    // 토큰 없는 요청은 다른 /api/v1 경로와 같이 보안 필터가 403 으로 막는다.
    step("토큰 없이 부르면 거절한다");
    expectStatus(await call(context, "/attention"), 403, "토큰 없는 지금 화면");
    expectStatus(await call(context, "/attention/summary"), 403, "토큰 없는 지금 화면 건수");
  },
};

function failuresOf(view: View): Item[] {
  const card = view.cards.find((each) => each.key === "failures");
  expect(card !== undefined, `실패 카드가 없다: ${JSON.stringify(view.cards.map((each) => each.key))}`);
  expect(card!.status === "OK", `실패 카드를 읽지 못했다: ${card!.status}`);
  return card!.items;
}
