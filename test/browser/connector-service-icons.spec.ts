import { readFile } from "node:fs/promises";
import { expect, test } from "./fixtures.ts";

for (const theme of ["light", "dark"] as const) {
  test(`서비스 아이콘이 ${theme === "light" ? "밝은" : "어두운"} 카드에서 보인다`, async ({
    page,
  }, testInfo) => {
    const connectors = await Promise.all(
      ["gmail", "naver-blog"].map(async (id) => {
        const root = new URL(`../../hermes/connectors/${id}/`, import.meta.url);
        const manifest = JSON.parse(
          await readFile(new URL("connector.json", root), "utf8"),
        );
        const icon = await readFile(new URL(manifest.icon, root));
        return {
          id,
          title: manifest.title,
          description: manifest.description,
          icon: `data:image/svg+xml;base64,${icon.toString("base64")}`,
          link: manifest.link,
          myStatus: "DISCONNECTED",
          available: true,
          bindings: [],
          fields: [],
          tools: [],
        };
      }),
    );
    await page.addInitScript(
      (value) => localStorage.setItem("theme", value),
      theme,
    );
    await page.route("**/api/connectors", (route) =>
      route.fulfill({ json: connectors }),
    );
    await page.goto("/connections");
    await expect(page.locator("html")).toHaveClass(
      theme === "dark" ? /\bdark\b/ : /^(?!.*\bdark\b)/,
    );
    const icons = page.getByTestId("connector-icon");
    await expect(icons).toHaveCount(connectors.length);
    for (const [index, connector] of connectors.entries()) {
      const icon = icons.nth(index);
      await expect(icon).toBeVisible();
      await expect(icon).toHaveAttribute("src", connector.icon);
      await expect
        .poll(() =>
          icon.evaluate(
            (element: HTMLImageElement) =>
              element.complete && element.naturalWidth > 0,
          ),
        )
        .toBe(true);
    }
    await page.screenshot({
      path: testInfo.outputPath(`connector-icons-${theme}.png`),
      fullPage: true,
      animations: "disabled",
    });
  });
}
