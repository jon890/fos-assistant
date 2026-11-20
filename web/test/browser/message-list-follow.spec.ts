import { conversationIdOf, expect, test } from "./fixtures.ts";

for (const readingAbove of [false, true]) {
  test(`받은 긴 답이 뒤늦게 펼쳐질 때 ${readingAbove ? "읽던 위치를 지킨다" : "맨 아래를 따라간다"}`, async ({ page }) => {
    await page.goto("/new");
    const composer = page.getByRole("textbox", { name: "메시지" });
    // 첫 답으로 스크롤할 수 있는 이력을 만든다.
    await composer.fill("긴 답 스트림 검사");
    await page.getByRole("button", { name: "보내기", exact: true }).click();
    await expect(page.getByTestId("assistant-message").locator("time")).toHaveCount(1, { timeout: 30_000 });
    const scroll = page.getByTestId("message-scroll");
    await expect.poll(() => scroll.evaluate((element) => element.scrollHeight - element.clientHeight)).toBeGreaterThan(100);

    // delta 하나 뒤에는 스트림을 열어 둔다. 다음 사건이나 done 없이 표시만 진행시킨다.
    const text = Array.from({ length: 80 }, (_, index) => `${index + 1}번째 뒤늦게 펼쳐지는 답 줄`).join("\n\n");
    await page.evaluate(({ conversationId, text }) => {
      const originalFetch = window.fetch.bind(window);
      window.fetch = (input, init) => {
        if (typeof input === "string" && input === "/api/chat/stream" && init?.method === "POST") {
          const stream = new ReadableStream({
            start(controller) {
              const events = [
                { type: "started", conversationId, executionId: 987654 },
                { type: "delta", text },
              ];
              controller.enqueue(new TextEncoder().encode(events.map((event) => `data: ${JSON.stringify(event)}\n\n`).join("")));
            },
          });
          return Promise.resolve(new Response(stream, { headers: { "Content-Type": "text/event-stream" } }));
        }
        return originalFetch(input, init);
      };
    }, { conversationId: conversationIdOf(page.url()), text });
    if (readingAbove) {
      await scroll.evaluate((element) => {
        element.scrollTop = 0;
        element.dispatchEvent(new Event("scroll"));
      });
    }
    await scroll.evaluate((element) => {
      const samples: { height: number; gap: number; top: number }[] = [];
      (window as unknown as { followSamples: typeof samples }).followSamples = samples;
      // 운영 observer 뒤에 등록하여 같은 높이 변화의 스크롤 반영 결과를 읽는다.
      new ResizeObserver(() => {
        const answers = element.querySelectorAll('[data-testid="assistant-message"]');
        const answer = answers[answers.length - 1];
        if (answers.length !== 2 || answer.querySelector("time")) return;
        samples.push({ height: element.scrollHeight, gap: element.scrollHeight - element.scrollTop - element.clientHeight, top: element.scrollTop });
      }).observe(element.firstElementChild!);
    });
    await composer.fill("긴 답 스트림 검사");
    await page.getByRole("button", { name: "보내기", exact: true }).click();
    await expect(page.getByTestId("assistant-message").last()).toContainText("80번째 뒤늦게 펼쳐지는 답 줄");
    await expect(page.getByTestId("assistant-message").last().locator("time")).toHaveCount(0);
    const samples = await page.evaluate(() => (window as unknown as { followSamples: { height: number; gap: number; top: number }[] }).followSamples);
    expect(new Set(samples.map((sample) => sample.height)).size, "받은 글의 길이가 고정된 뒤에도 여러 높이로 펼쳐졌다").toBeGreaterThan(1);
    if (readingAbove) {
      expect(samples.every((sample) => sample.top === 0)).toBe(true);
      await expect(page.getByRole("button", { name: "새 메시지" })).toBeVisible();
      await page.getByRole("button", { name: "새 메시지" }).click();
    } else {
      expect(Math.max(...samples.map((sample) => sample.gap))).toBeLessThanOrEqual(2);
    }
    await expect.poll(() => scroll.evaluate((element) => element.scrollHeight - element.scrollTop - element.clientHeight)).toBeLessThanOrEqual(2);
  });
}
