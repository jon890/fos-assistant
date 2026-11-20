import { Share, SquarePlus, Smartphone, type LucideIcon } from "lucide-react";

const STEPS: readonly { icon: LucideIcon; title: string; body: string }[] = [
  {
    icon: Share,
    title: "공유",
    body: "브라우저의 공유 단추를 눌러요.",
  },
  {
    icon: SquarePlus,
    title: "홈 화면에 추가",
    body: "목록에서 「홈 화면에 추가」 를 골라요.",
  },
  {
    icon: Smartphone,
    title: "앱에서 다시 열기",
    body: "홈 화면에 생긴 앱으로 이 화면을 다시 열어요.",
  },
];

/**
 * 아이폰과 iPad 의 브라우저 탭에서 연 「알림」 화면의 안내다(docs/features/attention/spec.md 의 REQ3).
 *
 * <p>iOS 는 홈 화면에 추가한 웹 앱에서만 웹 푸시를 받는다. 그림 파일 대신 아이콘과 글로 그려 테마를 따른다.
 */
export function IosInstallGuide() {
  return (
    <section aria-labelledby="ios-install-title" className="space-y-3">
      <h2 id="ios-install-title" className="text-sm font-medium">
        홈 화면에 추가하면 이 기기에서 알림을 받을 수 있어요
      </h2>
      <ol className="grid gap-2 sm:grid-cols-3">
        {STEPS.map((step, index) => (
          <li
            key={step.title}
            data-testid="ios-install-step"
            className="flex flex-col items-center gap-2 rounded-lg border border-border p-4 text-center"
          >
            <step.icon aria-hidden="true" className="size-8" />
            <span className="text-sm font-medium">
              {index + 1}. {step.title}
            </span>
            <span className="text-xs text-muted-foreground">{step.body}</span>
          </li>
        ))}
      </ol>
    </section>
  );
}
