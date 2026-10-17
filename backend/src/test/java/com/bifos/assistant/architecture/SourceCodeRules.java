package com.bifos.assistant.architecture;

import com.sun.source.tree.AnnotationTree;
import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.VariableTree;
import com.sun.source.util.JavacTask;
import com.sun.source.util.TreeScanner;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaFileObject;
import javax.tools.ToolProvider;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

/** rule-86, rule-87: annotation processing 전에 소스를 읽어 필드와 타입 선언을 구별한다. */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
final class SourceCodeRules {
    private static final Set<String> INJECTION = Set.of(
        "org.springframework.beans.factory.annotation.Autowired",
        "org.springframework.beans.factory.annotation.Value", "jakarta.inject.Inject", "javax.inject.Inject");
    private static final Set<String> ENTITY_LOMBOK = Set.of(
        "lombok.Data", "lombok.Setter", "lombok.EqualsAndHashCode", "lombok.ToString");


    static List<String> violations(List<Path> sources) throws IOException {
        var compiler = ToolProvider.getSystemJavaCompiler();
        var diagnostics = new DiagnosticCollector<JavaFileObject>();
        List<String> violations = new ArrayList<>();
        try (var manager = compiler.getStandardFileManager(diagnostics, null, null)) {
            var task = (JavacTask)compiler.getTask(null, manager, diagnostics, List.of("-proc:none"), null,
                manager.getJavaFileObjectsFromPaths(sources));
            for (CompilationUnitTree unit : task.parse()) {
                new TreeScanner<Void, Void>() {
                    @Override
                    public Void visitClass(ClassTree declaration, Void unused) {
                        boolean entity = declaration.getModifiers().getAnnotations().stream()
                            .anyMatch(annotation -> matches(unit, annotation, "jakarta.persistence.Entity"));
                        if (entity) {
                            check(declaration.getModifiers().getAnnotations(), ENTITY_LOMBOK, "rule-87", declaration.getSimpleName().toString());
                        }
                        for (var member : declaration.getMembers()) {
                            if (member instanceof VariableTree field) {
                                String owner = declaration.getSimpleName() + "." + field.getName();
                                check(field.getModifiers().getAnnotations(), INJECTION, "rule-86", owner);
                                if (entity) {
                                    check(field.getModifiers().getAnnotations(), ENTITY_LOMBOK, "rule-87", owner);
                                }
                            }
                        }
                        return super.visitClass(declaration, unused);
                    }


                    private void check(List<? extends AnnotationTree> annotations, Set<String> forbidden, String rule, String owner) {
                        for (AnnotationTree annotation : annotations) {
                            if (forbidden.stream().anyMatch(type -> matches(unit, annotation, type))) {
                                violations.add(rule + ": " + unit.getSourceFile().getName() + ": " + owner + " @" + annotation.getAnnotationType());
                            }
                        }
                    }
                }.scan(unit, null);
            }
        }
        var errors = diagnostics.getDiagnostics().stream().filter(item -> item.getKind() == Diagnostic.Kind.ERROR).toList();
        if (!errors.isEmpty()) {
            throw new IllegalArgumentException("Java 소스를 해석하지 못했다: " + errors);
        }
        return violations;
    }


    private static boolean matches(CompilationUnitTree unit, AnnotationTree annotation, String qualified) {
        String name = annotation.getAnnotationType().toString();
        if (name.equals(qualified)) {
            return true;
        }
        if (!name.equals(qualified.substring(qualified.lastIndexOf('.') + 1))) {
            return false;
        }
        var imports = unit.getImports().stream().filter(item -> !item.isStatic())
            .map(item -> item.getQualifiedIdentifier().toString()).toList();
        var explicit = imports.stream().filter(item -> item.endsWith("." + name)).findFirst();
        if (explicit.isPresent()) {
            return explicit.get().equals(qualified);
        }
        return imports.contains(qualified.substring(0, qualified.lastIndexOf('.')) + ".*");
    }
}
