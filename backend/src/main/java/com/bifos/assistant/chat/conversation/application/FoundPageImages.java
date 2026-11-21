package com.bifos.assistant.chat.conversation.application;

import com.bifos.assistant.chat.conversation.application.dto.FoundImage.Kind;
import java.net.URI;
import java.time.Duration;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/** 읽은 페이지에서 뽑은 사진 주소를 대화별로 잠시 보관한다. 영구 기록은 FoundLinks 의 해시만 쓴다. */
@Component
class FoundPageImages {
    private static final int MAX_PAGES = 256;
    private static final long MAX_AGE = Duration.ofMinutes(30).toNanos();
    private static final Pattern SHARED_IMAGE = Pattern.compile(
        "(?i)(?:^|[/_.-])(?:all[_-]og|og[_-]default|og[_-]image|default|logo|placeholder|favicon|social[_-]share)(?:$|[/_.-])"
            + "|/(?:common|branding|social|site-assets)/");
    private static final Comparator<Candidate> PREFERENCE = Comparator.comparing(Candidate::kind)
        .thenComparing(Comparator.comparingLong(Candidate::size).reversed());

    private final Map<Key, Page> pages = new LinkedHashMap<>();


    synchronized void remember(Long conversationId, String address, URI base, String pageHash, List<Candidate> images) {
        long now = System.nanoTime();
        expire(now);
        Key key = new Key(conversationId, address);
        pages.remove(key);
        pages.put(key, new Page(base.getHost(), pageHash, images, now));
        if (pages.size() > MAX_PAGES) {
            pages.remove(pages.keySet().iterator().next());
        }
    }


    synchronized Optional<Candidate> choose(Long conversationId, String address, Predicate<String> recorded) {
        expire(System.nanoTime());
        Page page = pages.get(new Key(conversationId, address));
        if (page == null) {
            return Optional.empty();
        }
        List<String> commonOg = page.images().stream()
            .filter(image -> image.kind() == Kind.OG && sharedOg(conversationId, page, image))
            .map(Candidate::hash)
            .toList();
        return page.images().stream()
            .filter(image -> image.kind() == Kind.PRODUCT || !sharedPath(image.uri()) && !commonOg.contains(image.hash()))
            .filter(image -> recorded.test(image.hash()))
            .sorted(PREFERENCE)
            .findFirst();
    }


    synchronized void clear(Long conversationId) {
        pages.keySet().removeIf(key -> key.conversationId().equals(conversationId));
    }


    private void expire(long now) {
        pages.values().removeIf(page -> now - page.savedAt() >= MAX_AGE);
    }


    private boolean sharedOg(Long conversationId, Page page, Candidate image) {
        return pages.entrySet().stream()
            .filter(entry -> entry.getKey().conversationId().equals(conversationId))
            .map(Map.Entry::getValue)
            .filter(other -> !page.hash().equals(other.hash()) && page.host().equalsIgnoreCase(other.host()))
            .flatMap(other -> other.images().stream())
            .anyMatch(other -> other.kind() == Kind.OG && other.hash().equals(image.hash()));
    }


    private static boolean sharedPath(URI uri) {
        return SHARED_IMAGE.matcher(uri.getPath()).find();
    }


    record Candidate(URI uri, String hash, Kind kind, long size) {
    }

    private record Key(Long conversationId, String address) {
    }

    private record Page(String host, String hash, List<Candidate> images, long savedAt) {
    }
}
