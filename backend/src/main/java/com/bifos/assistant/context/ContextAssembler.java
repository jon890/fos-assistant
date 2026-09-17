package com.bifos.assistant.context;

import com.bifos.assistant.memory.application.MemoryService;
import com.bifos.assistant.memory.domain.Memory;
import com.bifos.assistant.memory.domain.MemoryScope;
import com.bifos.assistant.shared.auth.CurrentUser;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/** 실행 하나에 넣을 instructions 를 조립한다. */
@Service
@RequiredArgsConstructor
public class ContextAssembler {

    private static final Logger log = LoggerFactory.getLogger(ContextAssembler.class);
    private static final String FAMILY_HEADER = "# 우리 가족이 함께 아는 것";
    private static final String USER_HEADER = "# 지금 묻는 사람에 대해 아는 것";
    private static final String INDEX_HEADER = """
            # 더 물어볼 수 있는 것

            아래는 제목만 적은 것이다. 필요하면 memory_read 도구로 본문을 읽는다.
            """.stripTrailing();

    private final MemoryService memories;
    private final ContextProperties properties;

    /** 고르는 자리를 여기 하나로 모은다. 항목이 많아지면 이 클래스에서 검색으로 바꾼다. */
    public AssembledContext assemble(CurrentUser user) {
        ContextBuilder builder = new ContextBuilder(properties.maxChars());
        appendAlways(builder, FAMILY_HEADER, memories.alwaysInjectedFor(user), MemoryScope.FAMILY);
        appendAlways(builder, USER_HEADER, memories.alwaysInjectedFor(user), MemoryScope.USER);
        appendIndex(builder, memories.indexedFor(user));
        return builder.build();
    }

    private static void appendAlways(
            ContextBuilder builder, String header, List<Memory> memories, MemoryScope scope) {
        memories.stream()
                .filter(memory -> memory.scope() == scope)
                .sorted(Comparator.comparing(Memory::id))
                .forEach(memory -> builder.append(header, "- " + memory.content()));
    }

    private static void appendIndex(ContextBuilder builder, List<Memory> memories) {
        memories.stream()
                .sorted(Comparator.comparing(Memory::id))
                .forEach(memory -> builder.append(INDEX_HEADER, "- [" + memory.id() + "] " + memory.title()));
    }

    private static final class ContextBuilder {
        private final long maxChars;
        private final StringBuilder full = new StringBuilder();
        private final StringBuilder selected = new StringBuilder();
        private final Set<String> fullHeaders = new HashSet<>();
        private final Set<String> selectedHeaders = new HashSet<>();
        private boolean truncated;

        private ContextBuilder(long maxChars) {
            this.maxChars = maxChars;
        }

        private void append(String header, String item) {
            appendTo(full, fullHeaders, header, item);
            if (truncated) {
                return;
            }
            String itemWithHeader = next(selected, selectedHeaders, header, item);
            if (selected.length() + itemWithHeader.length() > maxChars) {
                truncated = true;
                return;
            }
            appendTo(selected, selectedHeaders, header, item);
        }

        private static String next(StringBuilder value, Set<String> headers, String header, String item) {
            String prefix = value.isEmpty() ? "" : "\n\n";
            return prefix + (headers.contains(header) ? item : header + "\n\n" + item);
        }

        private static void appendTo(StringBuilder value, Set<String> headers, String header, String item) {
            value.append(next(value, headers, header, item));
            headers.add(header);
        }

        private AssembledContext build() {
            if (truncated) {
                log.warn("memory context truncated omittedChars={}", full.length() - selected.length());
            }
            return selected.isEmpty()
                    ? AssembledContext.empty()
                    : new AssembledContext(selected.toString(), selected.length());
        }
    }
}
