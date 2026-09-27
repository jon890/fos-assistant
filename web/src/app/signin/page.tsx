import { signIn } from "@/auth";
import { Button } from "@/components/ui/button";
import { Card } from "@/components/ui/card";

export default function SignInPage() {
  return (
    <section className="grid min-h-full place-items-center">
      <Card className="w-full max-w-sm items-center gap-0 bg-muted p-6 text-center shadow-sm sm:p-8">
        <span
          aria-hidden="true"
          // 한 번만 쓰는 머리글자 표시다. 브랜드 색 원 안에 글자를 가운데 두는 배치라 부품으로 모으지 않는다.
          className="flex h-12 w-12 items-center justify-center rounded-full bg-primary text-lg font-semibold text-primary-foreground"
        >
          우
        </span>
        <h1 className="mt-4 text-2xl font-semibold">우리집 비서</h1>
        <p className="mt-2 leading-6 text-muted-foreground">
          사용자로 등록된 Google 계정으로 로그인한다.
        </p>
        <form
          className="mt-6 w-full"
          action={async () => {
            "use server";
            await signIn("google", { redirectTo: "/" });
          }}
        >
          <Button type="submit" className="w-full">
            Google 계정으로 로그인
          </Button>
        </form>
      </Card>
    </section>
  );
}
