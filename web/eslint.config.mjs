// 코드 규칙: rule-42, rule-55, rule-59. 까닭과 예외는 docs/code-rules/INDEX.md를 본다.
// web 코드의 lint 규칙이다. 까닭과 예외는 docs/code-rules가 갖고 실행 조건과 구현 설명은 이 파일이 갖는다.
import { defineConfig, globalIgnores } from "eslint/config";
import nextVitals from "eslint-config-next/core-web-vitals";
import nextTs from "eslint-config-next/typescript";

// rule-59: Node 테스트의 런타임 의존이 늘면 아래 목록도 함께 늘린다.
const NODE_TEST_READ_FILES = [
  "src/components/chat/activity/activity-state.ts",
  "src/components/chat/skill-command.ts",
  "src/lib/usage-paging.ts",
  "src/lib/workspace-file.ts",
];

export default defineConfig([
  ...nextVitals,
  ...nextTs,
  globalIgnores([".next/**", "out/**", "build/**", "next-env.d.ts"]),

  {
    // rule-55: 라우트의 오류 응답과 본문 파싱을 검사한다.
    files: ["src/app/api/**/*.ts"],
    rules: {
      "no-restricted-syntax": [
        "error",
        {
          selector:
            "CallExpression[callee.object.name='NextResponse'][callee.property.name='json'] > ObjectExpression.arguments:first-child > Property:matches([key.name='code'], [key.value='code'])",
          message:
            "오류 응답을 `NextResponse.json({ code, ... })` 로 직접 만들지 않는다. 오류 응답 도우미를 쓴다.",
        },
        {
          selector:
            "CallExpression[callee.property.name='json'][callee.object.name=/^(request|req)$/]",
          message:
            "요청 본문을 `request.json()` 으로 직접 읽지 않는다. 요청 본문 도우미를 쓴다.",
        },
      ],
    },
  },

  {
    // rule-55: 화면의 직접 fetch 호출을 검사한다.
    // 화면에서 fetch 를 꼭 써야 하는 파일이 생기면 이 블록의 ignores 에 그 파일과 까닭을 적는다.
    files: ["src/components/**/*.{ts,tsx}", "src/app/**/*.tsx"],
    rules: {
      "no-restricted-globals": [
        "error",
        {
          name: "fetch",
          message:
            "화면에서 fetch 를 직접 부르지 않는다. lib/ 의 호출 함수를 거친다.",
        },
      ],
    },
  },

  {
    // rule-59: 위 Node 런타임 모듈은 별칭 검사에서 제외한다.
    files: ["src/**/*.{ts,tsx}"],
    ignores: NODE_TEST_READ_FILES,
    rules: {
      "no-restricted-imports": [
        "error",
        {
          patterns: [
            { group: ["../*"], message: "상위 경로 대신 `@/` 로 import 한다." },
          ],
        },
      ],
    },
  },

  {
    // rule-42: 실패시키지 않는 경고다.
    files: ["src/**/*.{ts,tsx}"],
    rules: {
      "max-lines-per-function": [
        "warn",
        { max: 150, skipBlankLines: true, skipComments: true },
      ],
    },
  },
]);
