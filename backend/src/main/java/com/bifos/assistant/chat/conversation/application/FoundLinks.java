package com.bifos.assistant.chat.conversation.application;

import com.bifos.assistant.chat.conversation.application.dto.FoundImage;
import com.bifos.assistant.chat.conversation.application.dto.FoundImageLink;
import com.bifos.assistant.chat.conversation.application.dto.FoundPage;
import com.bifos.assistant.chat.conversation.application.dto.LinkTarget;
import com.bifos.assistant.chat.conversation.infra.ConversationRepository;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 비서가 웹 검색과 페이지 읽기로 찾은 주소를 대화마다 해시로 남기고, 모델이 가리킨 주소가 그 안에 있는지 답한다
 * (ADR-20261121 / found-link-image-source, 스펙 agent-found-image REQ2).
 *
 * <p>데이터베이스에는 해시와 읽은 페이지·고른 사진의 표시만 둔다. {@code conversation.found_link_hashes} 에 32자씩 이어 붙이고,
 * 더하기는 {@code UPDATE} 한 문장이라 한 실행의 도구 결과 여럿이 동시에 알려도 서로를 덮지 않는다. 상한을 넘으면 오래된 것부터 밀려난다.
 *
 * <p>같은 주소로 보는 규칙은 {@link LinkTarget} 이 갖는다. 거기에 결과만으로 정해지는 변환(엔티티 풀기, {@code //}, 상대 주소)을 더한다
 * (스펙 agent-found-image DECISION19). 읽은 페이지 자신의 주소와 같은 후보는 남기지 않는다(DECISION25).
 */
@Service
@RequiredArgsConstructor
public class FoundLinks {
    /** 대화 하나에 남기는 해시의 수다. 넘으면 오래된 것부터 버린다. */
    static final int MAX_HASHES = 20_000;
    static final int HASH_CHARS = 32;
    /** 후보 하나의 길이 상한이다. {@code link:} 가리킴의 상한과 같다. */
    static final int MAX_LINK_CHARS = 2_048;

    private final ConversationRepository conversations;
    private final FoundPageImages pageImages;


    /**
     * 후보에서 만든 해시를 그 대화에 더한다. 지운 대화에는 더하지 않는다. 남길 것이 없으면 아무것도 하지 않는다.
     *
     * @param typed 비서가 읽기 도구에 적어 넣은 주소. 읽기 서비스가 결과의 주소를 넘어간 뒤의 최종 주소로 바꿔 줄 수 있어 따로 받는다
     * @return 남긴 해시의 수
     */
    @Transactional
    public int record(Long conversationId, List<FoundPage> pages, List<String> typed) {
        // 비서가 읽기 도구에 적어 넣은 주소다. 본문이 되풀이해도 찾은 주소로 남기지 않는다(DECISION25).
        Set<String> excluded = new LinkedHashSet<>();
        for (String url : typed) {
            // scheme 없이 적어 넣은 주소도 같은 주소로 본다.
            LinkTarget.of(url).map(LinkTarget::hash).ifPresent(excluded::add);
        }
        Set<String> hashes = new LinkedHashSet<>();
        List<ReadPage> readPages = new ArrayList<>();
        for (FoundPage page : pages) {
            URI base = absolute(page.base());
            String own = base == null ? null : LinkTarget.of(base.toString()).map(LinkTarget::hash).orElse(null);
            Set<String> pageHashes = new LinkedHashSet<>();
            for (String link : page.links()) {
                // 후보에서 만든 꼴 가운데 하나라도 페이지 자신이나 적어 넣은 주소면 그 후보의 꼴을 모두 뺀다.
                // 꼴마다 따로 빼면 본문이 자기 주소를 &amp; 로 되풀이했을 때 적힌 꼴이 남는다.
                List<String> formed = forms(link, base).stream()
                    .map(LinkTarget::of)
                    .flatMap(Optional::stream)
                    .map(LinkTarget::hash)
                    .toList();
                if (formed.stream().noneMatch(hash -> hash.equals(own) || excluded.contains(hash))) {
                    pageHashes.addAll(formed);
                }
            }
            hashes.addAll(pageHashes);
            if (base != null && own != null && page.images() != null) {
                ReadPage read = readPage(page, base, own, pageHashes, excluded);
                read.addresses().forEach(address -> hashes.add(readMarker(address)));
                readPages.add(read);
            }
        }
        if (hashes.isEmpty()) {
            return 0;
        }
        List<String> kept = hashes.stream().skip(Math.max(0, hashes.size() - MAX_HASHES)).toList();
        if (conversations.appendFoundLinkHashes(conversationId, String.join("", kept), MAX_HASHES * HASH_CHARS) > 0) {
            for (ReadPage read : readPages) {
                read.addresses().forEach(address -> pageImages.remember(conversationId, address, read.base(), read.hash(), read.images()));
            }
        }
        return kept.size();
    }


    private static ReadPage readPage(FoundPage page, URI base, String own, Set<String> pageHashes, Set<String> excluded) {
        Set<String> addresses = new LinkedHashSet<>();
        addresses.add(own);
        // 요청 주소는 이 호출에서 비서가 읽은 주소로 함께 알려졌을 때만 이동 전 주소로 기억한다.
        LinkTarget.of(page.requested()).map(LinkTarget::hash).filter(excluded::contains).ifPresent(addresses::add);
        List<FoundPageImages.Candidate> images = page.images().stream()
            .filter(image -> page.links().contains(image.url()))
            .map(image -> photoCandidate(image, base, pageHashes))
            .flatMap(Optional::stream)
            .toList();
        return new ReadPage(base, own, addresses, images);
    }


    private static Optional<FoundPageImages.Candidate> photoCandidate(FoundImage image, URI base, Set<String> pageHashes) {
        // HTML 엔티티가 든 주소는 푼 쪽을 먼저 쓰고 같은 후보의 두 꼴을 중복해서 고르지 않는다.
        return forms(LinkTarget.decodeEntities(image.url()), base).stream()
            .map(LinkTarget::of)
            .flatMap(Optional::stream)
            .filter(target -> pageHashes.contains(target.hash()))
            .flatMap(target -> target.fetchUri()
                .map(uri -> new FoundPageImages.Candidate(uri, target.hash(), image.kind(), image.size()))
                .stream())
            .findFirst();
    }


    private static String readMarker(String address) {
        // 첫 글자는 16진수 밖에 둬 주소 해시와 구별하고, 저장 칸의 폭은 같다.
        return "p" + address.substring(1);
    }


    private static String photoMarker(String address) {
        return "i" + address.substring(1);
    }


    /**
     * @param link 모델이 {@code link:} 뒤에 적은 주소
     * @return 비서가 그 대화에서 찾은 주소면 받을 주소. 가리킨 글에 엔티티가 있으면 푼 주소다. 아니면 빈 값이다
     */
    @Transactional
    public Optional<URI> find(Long conversationId, String link) {
        return find(conversationId, link, false).map(FoundImageLink::uri);
    }


    /** 읽은 상품 페이지의 대화 확인을 통과한 뒤, 같은 대화에서 확인된 대표 사진 주소를 고른다. */
    @Transactional
    public Optional<FoundImageLink> findForImage(Long conversationId, String link) {
        return find(conversationId, link, true);
    }


    private Optional<FoundImageLink> find(Long conversationId, String link, boolean chooseImage) {
        if (link == null) {
            return Optional.empty();
        }
        String stored = conversations.findFoundLinkHashes(conversationId).orElse("");
        if (stored.isEmpty()) {
            return Optional.empty();
        }
        Set<String> candidates = new LinkedHashSet<>();
        candidates.add(LinkTarget.decodeEntities(link));
        candidates.add(link);
        for (String candidate : candidates) {
            Optional<LinkTarget> target = LinkTarget.of(candidate);
            if (target.isPresent()) {
                String hash = target.get().hash();
                if (!contains(stored, hash)) {
                    continue;
                }
                String marker = readMarker(hash);
                String directMarker = photoMarker(hash);
                boolean directPhoto = chooseImage && contains(stored, directMarker);
                if (chooseImage && !directPhoto && contains(stored, marker)) {
                    Optional<FoundPageImages.Candidate> photo = pageImages.choose(conversationId, hash, image -> contains(stored, image));
                    String photoHashes = photo.map(image -> image.hash() + photoMarker(image.hash())).orElse("");
                    conversations.appendFoundLinkHashes(conversationId, hash + marker + photoHashes, MAX_HASHES * HASH_CHARS);
                    return Optional.of(new FoundImageLink(photo.map(FoundPageImages.Candidate::uri).orElse(null), true));
                }
                // 쓴 주소를 뒤로 옮겨, 뒤에 읽은 페이지들에 밀려나 다음 장면에서 빠지는 일을 줄인다.
                conversations.appendFoundLinkHashes(conversationId, directPhoto ? hash + directMarker : hash, MAX_HASHES * HASH_CHARS);
                return target.get().fetchUri().map(uri -> new FoundImageLink(uri, directPhoto));
            }
        }
        return Optional.empty();
    }


    /** 대화를 지울 때 남긴 해시를 비운다(스펙 agent-found-image DECISION16). */
    @Transactional
    public void clear(Long conversationId) {
        conversations.clearFoundLinkHashes(conversationId);
        pageImages.clear(conversationId);
    }


    /** 해시를 32자 경계로만 비교한다. 구분 문자가 없어 글 전체에서 찾으면 두 해시에 걸친 일치가 생긴다. */
    private static boolean contains(String stored, String hash) {
        for (int at = 0; at + HASH_CHARS <= stored.length(); at += HASH_CHARS) {
            if (stored.startsWith(hash, at)) {
                return true;
            }
        }
        return false;
    }


    /** 후보 하나에서 남길 주소의 꼴을 만든다. 적힌 그대로와 엔티티를 푼 꼴을 각각 절대 주소로 바꾼다. 바꾸지 못한 꼴은 뺀다. */
    private static Set<String> forms(String link, URI base) {
        Set<String> forms = new LinkedHashSet<>();
        if (link == null) {
            return forms;
        }
        String raw = link.strip();
        if (raw.isEmpty() || raw.length() > MAX_LINK_CHARS || raw.chars().anyMatch(Character::isWhitespace)) {
            return forms;
        }
        for (String written : List.of(raw, LinkTarget.decodeEntities(raw))) {
            String resolved = resolve(written, base);
            if (resolved != null) {
                forms.add(resolved);
            }
        }
        return forms;
    }


    private static String resolve(String written, URI base) {
        String lower = written.toLowerCase(Locale.ROOT);
        if (lower.startsWith("https://") || lower.startsWith("http://")) {
            return written;
        }
        if (written.startsWith("//")) {
            return "https:" + written;
        }
        if (base == null) {
            return null;
        }
        if (written.startsWith("?")) {
            // java.net.URI 는 쿼리만 있는 상대 주소를 풀 때 경로의 마지막 조각을 떼어 표준(RFC 3986)과 다르다. 경로에 직접 붙인다.
            String whole = base.toString();
            int cut = indexOfAny(whole, "?#");
            return (cut < 0 ? whole : whole.substring(0, cut)) + written;
        }
        try {
            URI resolved = base.resolve(new URI(LinkTarget.asciiEncoded(written)));
            String scheme = resolved.getScheme();
            if (resolved.getHost() == null || scheme == null || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) {
                return null;
            }
            return resolved.toString();
        } catch (URISyntaxException | IllegalArgumentException ex) {
            return null;
        }
    }


    private static int indexOfAny(String text, String characters) {
        for (int index = 0; index < text.length(); index++) {
            if (characters.indexOf(text.charAt(index)) >= 0) {
                return index;
            }
        }
        return -1;
    }


    /** 기준 주소가 http(s) 절대 주소면 그 주소를, 아니면 null 을 돌려준다. */
    private static URI absolute(String base) {
        if (base == null || base.length() > MAX_LINK_CHARS) {
            return null;
        }
        try {
            URI uri = new URI(LinkTarget.asciiEncoded(base.strip()));
            String scheme = uri.getScheme();
            if (uri.getHost() == null || scheme == null || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) {
                return null;
            }
            return uri;
        } catch (URISyntaxException ex) {
            return null;
        }
    }


    private record ReadPage(URI base, String hash, Set<String> addresses, List<FoundPageImages.Candidate> images) {
    }
}
