// 코드 규칙: rule-43. 실제 저장소 Gradle 설정으로 JDT 포맷 계약을 검사한다.
// 실행: node test/spotless-jdt.test.ts (일반 단위 테스트에서는 무거운 Gradle 회귀를 건너뛴다.)
import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { cpSync, mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import test from 'node:test';
const root = fileURLToPath(new URL('../', import.meta.url));
const wideCall = 'String.join("", "' + 'a'.repeat(210) + '", "' + 'b'.repeat(210) + '")';
const source = "import java.util.List;\nimport java.util.ArrayList;\n/** 설명은 그대로 둔다. */\npublic class FormatFixture {\n// 주석은 메서드 안으로 들여쓴다.\n/** 첫 결과다.\n * <p>문단 본문을 같은 줄에 유지한다.\n */\npublic String first(){\nreturn \"a  b\";\n}\npublic List<String> second(){\nreturn List.of(\n\"one\",\n\"two\").stream()\n.map(String::trim)\n.toList();\n}\npublic String text(){return \"\"\"\n        alpha  beta\n          gamma\n        \"\"\";}\npublic record Value(@SuppressWarnings(\"unused\") String name){}\npublic static void main(String[] args){\nvar value=new FormatFixture();\nSystem.out.print(value.first()+value.text());\n}\n}\n".replace('public class FormatFixture {', 'public class FormatFixture {\nprivate final String wide=' + wideCall + ';');

test('실제 Gradle로 JDT 포맷 계약을 확인한다', { skip: !!process.env.NODE_TEST_CONTEXT }, () => {
  const temporary = mkdtempSync(join(tmpdir(), 'fos-jdt-test-'));
  const backend = join(temporary, 'backend');
  mkdirSync(backend);
  try {
    for (const name of ['build.gradle.kts', 'settings.gradle.kts', 'gradle.properties', 'gradle', 'config/spotless']) cpSync(join(root, 'backend', name), join(backend, name), { recursive: true });
    const file = join(backend, 'src/main/java/FormatFixture.java');
    mkdirSync(dirname(file), { recursive: true });
    writeFileSync(file, source);
    function command(program: string, args: string[], success = true) {
      const result = spawnSync(program, args, { encoding: 'utf8', maxBuffer: 16 * 1024 * 1024 });
      assert.equal(result.status === 0, success, (result.stdout + result.stderr).slice(-6000));
      return result.stdout;
    }
    function compile() {
      const classes = join(backend, 'build/fixture-classes');
      mkdirSync(classes, { recursive: true });
      command('javac', ['--release', '21', '-d', classes, file]);
      return command('java', ['-cp', classes, 'FormatFixture']);
    }
    function gradle(task: string, success = true) {
      return command(join(root, 'backend/gradlew'), ['-p', backend, task, '-PformatAll=true', '--console=plain', '--build-cache'], success);
    }
    const original = compile();
    gradle('spotlessCheck', false);
    assert.equal(readFileSync(file, 'utf8'), source, 'check가 원본을 바꾸면 안 된다');
    gradle('spotlessApply');
    const formatted = readFileSync(file, 'utf8');
    for (const text of ['    public String first() {\n        return "a  b";', '/** 설명은 그대로 둔다. */', '    // 주석은 메서드 안으로 들여쓴다.', ' * <p>문단 본문을 같은 줄에 유지한다.', 'return List.of(\n            "one",\n            "two").stream()\n            .map(String::trim)\n            .toList();', 'import java.util.List;\nimport java.util.ArrayList;', 'record Value(@SuppressWarnings("unused") String name)', wideCall + ';']) assert.ok(formatted.includes(text), text);
    assert.match(formatted, /}\n\n\n    public List<String> second/);
    assert.equal(compile(), original, '문자열과 text block 값을 보존한다');
    gradle('spotlessCheck');
    assert.equal(readFileSync(file, 'utf8'), formatted);
    gradle('spotlessApply');
    assert.equal(readFileSync(file, 'utf8'), formatted, '2회 적용은 멱등이어야 한다');
    const settings = join(backend, 'config/spotless/eclipse-formatter.xml');
    writeFileSync(settings, readFileSync(settings, 'utf8').replace('id="org.eclipse.jdt.core.formatter.tabulation.size" value="4"', 'id="org.eclipse.jdt.core.formatter.tabulation.size" value="2"').replace('id="org.eclipse.jdt.core.formatter.indentation.size" value="4"', 'id="org.eclipse.jdt.core.formatter.indentation.size" value="2"'));
    gradle('spotlessCheck', false);
    assert.equal(readFileSync(file, 'utf8'), formatted);
    gradle('spotlessApply');
    assert.ok(readFileSync(file, 'utf8').includes('  public String first() {\n    return "a  b";'));
  } finally { rmSync(temporary, { recursive: true, force: true }); }
});
