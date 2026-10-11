import Link from "next/link";
import { connection } from "next/server";
import { appName } from "@/lib/app-name";
import { Button } from "@/components/ui/button";
import { Card } from "@/components/ui/card";

const sections = [
  {
    title: "서버 관리자의 접근",
    items: [
      "데이터는 이 비서를 설치한 서버에 보관해요. 서버 관리자는 대화를 읽을 수 있어요. 암호화를 풀 수 있는 키도 같은 서버에 있기 때문이에요.",
      "대화 제목, 실행 기록의 일부, 승인 기록, 할 일과 알림의 글은 아직 암호화하지 않아요. 암호화하기 전에 저장한 메시지도 평문으로 남아 있어요.",
    ],
  },
  {
    title: "암호화의 보호 범위",
    items: [
      "서버 관리자가 암호화 키를 설정한 설치에서는 대화 메시지 본문을 암호화해 저장해요.",
      "암호화한 메시지 본문은 데이터베이스 파일이나 백업만 가진 사람, 데이터베이스만 조회하는 사람이 읽을 수 없어요. 서버 관리자의 접근을 막는 장치는 아니에요.",
    ],
  },
  {
    title: "AI에 전달되는 내용",
    items: [
      "대화 내용은 서버 관리자가 정한 AI 모델 제공사에 요청의 일부로 평문으로 전달돼요.",
      "AI 실행 프로그램은 대화 이력을 별도 저장소에 평문으로 보관해요. 대화를 지우면 이 이력도 정리해요.",
    ],
  },
  {
    title: "대화 삭제와 남는 기록",
    items: [
      "대화를 지우면 화면에서 바로 사라져요. 보통 몇 분 안에 메시지, 첨부한 사진, 결과물 파일, 실행 기록의 본문과 AI 실행 프로그램의 대화 이력을 정리해요. 실행 중인 대화는 실행이 끝난 뒤 정리해요. 정리 작업이 실패하면 더 걸릴 수 있어요.",
      "본문 없는 사용량 기록은 남아요. 승인 기록과 할 일, 알림의 글도 아직 함께 지우지 않아요.",
      "이미 만든 데이터베이스 백업에는 지운 대화가 남을 수 있어요. 백업을 언제 버릴지는 서버 관리자가 정해요.",
    ],
  },
  {
    title: "외부 서비스 연결 해제",
    items: [
      "외부 서비스 연결 화면에서 연결을 해제하면 그 연결의 비밀값과 연결을 붙인 에이전트에 복사한 값을 지워요.",
      "외부 서비스의 권한은 해당 서비스에서도 끊을 수 있어요. 브라우저 로그인은 해당 서비스에서 직접 로그아웃해 주세요.",
    ],
  },
];

export default async function PrivacyPage() {
  // 앱 이름은 빌드에 굳히지 않고 실행할 때의 환경에서 읽는다.
  await connection();
  return (
    <article className="mx-auto w-full max-w-2xl space-y-6 pb-6">
      <header className="space-y-2">
        <p className="break-words text-sm text-muted-foreground">{appName()}</p>
        <h1 className="text-2xl font-semibold">데이터 보관 안내</h1>
        <p className="text-sm leading-6 text-muted-foreground">
          로그인 전에 대화가 어디에 남고, 누가 읽을 수 있는지 확인해 주세요.
        </p>
      </header>
      <Card className="gap-6 p-6">
        {sections.map(({ title, items }, index) => (
          <section key={title} aria-labelledby={`privacy-section-${index}`}>
            <h2 id={`privacy-section-${index}`} className="font-semibold">
              {title}
            </h2>
            <ul className="mt-3 list-disc space-y-2 pl-5 text-sm leading-6">
              {items.map((item) => (
                <li key={item}>{item}</li>
              ))}
            </ul>
          </section>
        ))}
      </Card>
      <Button asChild variant="outline">
        <Link href="/signin" prefetch={false}>
          로그인 화면으로
        </Link>
      </Button>
    </article>
  );
}
