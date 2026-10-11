#!/usr/bin/env python3
"""실제 저장소 Gradle 설정으로 JDT 포맷 계약을 검사한다. python3 test/spotless-jdt.test.py"""
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]

# 설정 XML을 복제하지 않고 사용자가 읽는 결과 모양을 독립 계약으로 둔다.
SOURCE = '''import java.util.List;
import java.util.ArrayList;
/** 설명은 그대로 둔다. */
public class FormatFixture {
// 주석은 메서드 안으로 들여쓴다.
/** 첫 결과다.
 * <p>문단 본문을 같은 줄에 유지한다.
 */
public String first(){
return "a  b";
}
public List<String> second(){
return List.of(
"one",
"two").stream()
.map(String::trim)
.toList();
}
public String text(){return """
        alpha  beta
          gamma
        """;}
public record Value(@SuppressWarnings("unused") String name){}
public static void main(String[] args){
var value=new FormatFixture();
System.out.print(value.first()+value.text());
}
}
'''
# 200열 안내선은 자동 줄바꿈 기준이 아니다. 500열 안의 호출은 한 줄을 유지한다.
WIDE_CALL = 'String.join("", "' + "a" * 210 + '", "' + "b" * 210 + '")'
SOURCE = SOURCE.replace('public class FormatFixture {',
                        'public class FormatFixture {\nprivate final String wide=' + WIDE_CALL + ';')


class SpotlessJdtTest(unittest.TestCase):
    def test_real_gradle_contract(self):
        with tempfile.TemporaryDirectory(prefix="fos-jdt-test-") as directory:
            backend = Path(directory) / "backend"
            backend.mkdir()
            for name in ("build.gradle.kts", "settings.gradle.kts", "gradle.properties"):
                shutil.copy2(ROOT / "backend" / name, backend / name)
            for name in ("gradle", "config/spotless"):
                shutil.copytree(ROOT / "backend" / name, backend / name)
            source = backend / "src/main/java/FormatFixture.java"
            source.parent.mkdir(parents=True)
            source.write_text(SOURCE)

            def compile_and_run():
                classes = backend / "build/fixture-classes"
                classes.mkdir(parents=True, exist_ok=True)
                result = subprocess.run(
                    ["javac", "--release", "21", "-d", str(classes), str(source)],
                    capture_output=True, text=True,
                )
                self.assertEqual(result.returncode, 0, result.stderr)
                return subprocess.check_output(["java", "-cp", str(classes), "FormatFixture"])

            original_value = compile_and_run()

            def gradle(task, success=True):
                result = subprocess.run(
                    [str(ROOT / "backend/gradlew"), "-p", str(backend), task,
                     "-PformatAll=true", "--console=plain", "--build-cache"],
                    capture_output=True, text=True,
                )
                output = result.stdout + result.stderr
                self.assertEqual(result.returncode == 0, success, output[-6000:])
                return output

            gradle("spotlessCheck", success=False)
            self.assertEqual(source.read_text(), SOURCE, "check가 원본을 바꾸면 안 된다")
            gradle("spotlessApply")
            formatted = source.read_text()
            self.assertIn('    public String first() {\n        return "a  b";', formatted)
            self.assertIn('/** 설명은 그대로 둔다. */', formatted)
            self.assertIn('    // 주석은 메서드 안으로 들여쓴다.', formatted)
            self.assertIn(' * <p>문단 본문을 같은 줄에 유지한다.', formatted)
            self.assertRegex(formatted, r'}\n\n\n    public List<String> second')
            self.assertIn('return List.of(\n            "one",\n            "two").stream()\n'
                          '            .map(String::trim)\n            .toList();', formatted)
            self.assertIn('import java.util.List;\nimport java.util.ArrayList;', formatted,
                          "사용하지 않는 import도 삭제하거나 재정렬하지 않는다")
            self.assertIn('record Value(@SuppressWarnings("unused") String name)', formatted)
            self.assertIn(WIDE_CALL + ';', formatted)
            self.assertEqual(compile_and_run(), original_value, "문자열과 text block 값을 보존한다")
            gradle("spotlessCheck")
            self.assertEqual(source.read_text(), formatted)
            gradle("spotlessApply")
            self.assertEqual(source.read_text(), formatted, "2회 적용은 멱등이어야 한다")

            # 설정만 바꿔도 이전 UP-TO-DATE/build cache 결과를 쓰지 않아야 한다.
            settings = backend / "config/spotless/eclipse-formatter.xml"
            settings.write_text(settings.read_text().replace(
                'id="org.eclipse.jdt.core.formatter.tabulation.size" value="4"',
                'id="org.eclipse.jdt.core.formatter.tabulation.size" value="2"',
            ).replace(
                'id="org.eclipse.jdt.core.formatter.indentation.size" value="4"',
                'id="org.eclipse.jdt.core.formatter.indentation.size" value="2"',
            ))
            gradle("spotlessCheck", success=False)
            self.assertEqual(source.read_text(), formatted)
            gradle("spotlessApply")
            self.assertIn('  public String first() {\n    return "a  b";', source.read_text())


if __name__ == "__main__":
    unittest.main()
