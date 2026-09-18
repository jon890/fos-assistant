package com.bifos.assistant.context;

import com.bifos.assistant.memory.application.MemoryService;
import com.bifos.assistant.memory.domain.Memory;
import com.bifos.assistant.memory.domain.MemoryScope;
import com.bifos.assistant.shared.auth.CurrentUser;
import java.util.ArrayList;
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

    /** 제목과 항목, 항목과 항목 사이에 넣는 구분 줄이다. */
    private static final String SEPARATOR = "\n\n";

    private final MemoryService memories;
    private final ContextProperties properties;

    /**
     * 고르는 자리를 여기 하나로 모은다. 항목이 많아지면 이 클래스에서 검색으로 바꾼다.
     *
     * <p>색인 층에 쓸 자리를 먼저 떼어 두고 항상 층을 담는다. 그러지 않으면 본문이 긴 항목 하나가
     * 상한을 거의 채워 색인이 통째로 빠지고, 색인이 없으면 {@code memory_read} 로 읽을 번호도
     * 사라져 에이전트가 나머지 Memory 에 닿을 길이 없어진다.
     */
    public AssembledContext assemble(CurrentUser user) {
        List<Memory> always = memories.alwaysInjectedFor(user);
        List<Memory> indexed = memories.indexedFor(user);
        long maxChars = properties.maxChars();
        long indexBudget = Math.min(indexLength(indexed), maxChars / properties.indexBudgetRatio());

        ContextBuilder builder = new ContextBuilder(maxChars);
        builder.limit(maxChars - indexBudget);
        appendAlways(builder, FAMILY_HEADER, always, MemoryScope.FAMILY);
        appendAlways(builder, USER_HEADER, always, MemoryScope.USER);
        builder.limit(maxChars);
        appendIndex(builder, indexed);
        return builder.build();
    }

    private static void appendAlways(
            ContextBuilder builder, String header, List<Memory> memories, MemoryScope scope) {
        memories.stream()
                .filter(memory -> memory.scope() == scope)
                .sorted(Comparator.comparing(Memory::id))
                .forEach(memory -> builder.append(memory.id(), header, "- " + memory.content()));
    }

    private static void appendIndex(ContextBuilder builder, List<Memory> memories) {
        memories.stream()
                .sorted(Comparator.comparing(Memory::id))
                .forEach(memory -> builder.append(memory.id(), INDEX_HEADER, indexItem(memory)));
    }

    private static String indexItem(Memory memory) {
        return "- [" + memory.id() + "] " + memory.title();
    }

    /**
     * 색인 층을 통째로 실을 때 늘어나는 글자 수다.
     *
     * <p>항상 층 뒤에 붙으므로 그 사이의 구분 줄까지 센다. 색인할 항목이 없으면 0 이고, 그때는 떼어
     * 두는 자리도 없다.
     */
    private static long indexLength(List<Memory> memories) {
        if (memories.isEmpty()) {
            return 0;
        }
        StringBuilder rendered = new StringBuilder();
        Set<String> headers = new HashSet<>();
        memories.stream()
                .sorted(Comparator.comparing(Memory::id))
                .forEach(memory -> ContextBuilder.appendTo(rendered, headers, INDEX_HEADER, indexItem(memory)));
        return rendered.length() + SEPARATOR.length();
    }

    private static final class ContextBuilder {
        private final long maxChars;
        private final StringBuilder full = new StringBuilder();
        private final StringBuilder selected = new StringBuilder();
        private final Set<String> fullHeaders = new HashSet<>();
        private final Set<String> selectedHeaders = new HashSet<>();
        private final List<Long> omitted = new ArrayList<>();

        /** 지금 담는 층이 쓸 수 있는 자리다. 층을 옮길 때마다 바꾼다. */
        private long limit;

        private ContextBuilder(long maxChars) {
            this.maxChars = maxChars;
            this.limit = maxChars;
        }

        private void limit(long limit) {
            this.limit = Math.max(0, Math.min(limit, maxChars));
        }

        /**
         * 항목 하나를 담는다.
         *
         * <p>그 항목이 남은 자리에 들어가지 않으면 <b>그 항목만</b> 빼고 다음 항목을 계속 본다. 한 번
         * 넘쳤다고 뒤를 모두 멈추면 본문이 긴 항목 하나가 나머지와 색인까지 밀어낸다.
         *
         * <p>넘친 항목을 잘라서 싣지도 않는다. 잘린 사실은 틀린 사실이 될 수 있다.
         */
        private void append(Long memoryId, String header, String item) {
            appendTo(full, fullHeaders, header, item);
            String itemWithHeader = next(selected, selectedHeaders, header, item);
            if (selected.length() + itemWithHeader.length() > limit) {
                omitted.add(memoryId);
                return;
            }
            appendTo(selected, selectedHeaders, header, item);
        }

        private static String next(StringBuilder value, Set<String> headers, String header, String item) {
            String prefix = value.isEmpty() ? "" : SEPARATOR;
            return prefix + (headers.contains(header) ? item : header + SEPARATOR + item);
        }

        private static void appendTo(StringBuilder value, Set<String> headers, String header, String item) {
            value.append(next(value, headers, header, item));
            headers.add(header);
        }

        private AssembledContext build() {
            if (!omitted.isEmpty()) {
                log.warn(
                        "memory context omitted items={} chars={}",
                        omitted.size(),
                        full.length() - selected.length());
            }
            return selected.isEmpty()
                    ? new AssembledContext(null, 0, omitted)
                    : new AssembledContext(selected.toString(), selected.length(), omitted);
        }
    }
}
