package com.bifos.assistant.chat.conversation.application;

import com.bifos.assistant.chat.conversation.domain.ChatMessage;
import com.bifos.assistant.chat.conversation.domain.type.MessageRole;
import com.bifos.assistant.chat.conversation.infra.ChatMessageRepository;
import com.bifos.assistant.shared.auth.CurrentUser;
import java.io.ByteArrayOutputStream;
import java.net.IDN;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 모델이 가리킨 링크가 요청자가 그 대화에 보낸 메시지에 있는지 확인한다(ADR-20261120 / link-image-source, 스펙 generated-image DECISION43).
 *
 * <p>같은 주소로 보는 범위는 앞의 {@code https://} 나 {@code http://} 유무, 호스트의 대소문자, 퍼센트 인코딩 차이뿐이다. 비서가 파라미터를 지우거나
 * 짧은 주소를 펼친 주소는 대화에 없는 주소다. 메시지 글을 미리 자르지 않는다. 한글 경로가 한글에서 잘리기 때문이다.
 */
@Service
@RequiredArgsConstructor
public class ConversationLinks {
    private static final Pattern SCHEME = Pattern.compile("(?i)https?://");
    /** 주소에 쓸 수 있는 ASCII 문자(RFC 3986 의 unreserved, reserved 와 {@code %})다. 이것이 아닌 문자가 주소의 경계다. */
    private static final String URL_CHARACTERS = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789-._~:/?#[]@!$&'()*+,;=%";
    /** 메시지에서 주소 끝에 붙어도 주소로 보지 않는 문장 부호다. */
    private static final String TRAILING = ".,)!?";

    private final ChatMessageRepository messages;


    /**
     * @param link 모델이 {@code link:} 뒤에 적은 주소
     * @return 대화에 있으면 받을 주소. {@code https://}, 소문자 호스트, ASCII 밖 문자를 퍼센트 인코딩한 꼴이고 조각은 뺀다. 없으면 빈 값이다
     */
    @Transactional(readOnly = true)
    public Optional<URI> findSentLink(CurrentUser user, Long conversationId, String link) {
        Optional<Target> target = Target.of(link);
        if (target.isEmpty()) {
            return Optional.empty();
        }
        for (ChatMessage message : messages.findByConversationIdOrderByIdAsc(conversationId)) {
            if (message.role() != MessageRole.USER || !user.id().equals(message.senderUserId())) {
                continue;
            }
            String content = message.content();
            if (content == null || ChatMessage.UNREADABLE_CONTENT.equals(content)) {
                continue;
            }
            if (contains(comparable(SCHEME.matcher(content).replaceAll(" ")), target.get())) {
                return target.get().fetchUri();
            }
        }
        return Optional.empty();
    }


    private static boolean contains(String text, Target target) {
        int from = 0;
        while (true) {
            int index = indexOfIgnoreCaseHost(text, target, from);
            if (index < 0) {
                return false;
            }
            if ((index == 0 || isBoundary(text.charAt(index - 1))) && endsAtBoundary(text, index + target.length())) {
                return true;
            }
            from = index + 1;
        }
    }


    /** 호스트 부분만 대소문자 없이, 나머지는 글자 그대로 맞는 첫 자리다. */
    private static int indexOfIgnoreCaseHost(String text, Target target, int from) {
        for (int i = from; i + target.length() <= text.length(); i++) {
            if (text.regionMatches(true, i, target.host(), 0, target.host().length())
                && text.startsWith(target.rest(), i + target.host().length())) {
                return i;
            }
        }
        return -1;
    }


    private static boolean endsAtBoundary(String text, int end) {
        if (end < text.length() && text.charAt(end) == '#') {
            // 조각은 받을 주소에서 버리므로 메시지 쪽 조각의 시작도 주소의 끝으로 본다.
            return true;
        }
        int index = end;
        while (index < text.length() && TRAILING.indexOf(text.charAt(index)) >= 0) {
            index++;
        }
        if (index == end) {
            return end == text.length() || isBoundary(text.charAt(end));
        }
        return index == text.length() || isBoundary(text.charAt(index));
    }


    private static boolean isBoundary(char character) {
        return URL_CHARACTERS.indexOf(character) < 0;
    }


    /** 비교에 쓰는 글이다. 올바른 {@code %XX} 를 UTF-8 로 풀고 틀린 {@code %} 는 그대로 둔다. */
    static String comparable(String text) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        for (int i = 0; i < text.length(); i++) {
            char character = text.charAt(i);
            if (character == '%' && i + 2 < text.length() && isHex(text.charAt(i + 1)) && isHex(text.charAt(i + 2))) {
                bytes.write(Integer.parseInt(text.substring(i + 1, i + 3), 16));
                i += 2;
            } else {
                bytes.writeBytes(String.valueOf(character).getBytes(StandardCharsets.UTF_8));
            }
        }
        return bytes.toString(StandardCharsets.UTF_8);
    }


    private static boolean isHex(char character) {
        return Character.digit(character, 16) >= 0;
    }


    /**
     * 모델이 가리킨 주소를 비교 글과 받을 주소로 나눈 것이다.
     *
     * @param host 비교할 호스트(퍼센트 인코딩을 푼 글)
     * @param rest 호스트 뒤의 비교할 글(퍼센트 인코딩을 푼 글)
     * @param rawHost 받을 주소의 호스트(원래 글)
     * @param rawRest 받을 주소의 경로와 쿼리(원래 글, 조각 뺌)
     */
    private record Target(String host, String rest, String rawHost, String rawRest) {

        static Optional<Target> of(String link) {
            if (link == null) {
                return Optional.empty();
            }
            String raw = link.strip();
            Matcher scheme = SCHEME.matcher(raw);
            if (scheme.lookingAt()) {
                raw = raw.substring(scheme.end());
            }
            int hash = raw.indexOf('#');
            if (hash >= 0) {
                raw = raw.substring(0, hash);
            }
            int split = indexOfAny(raw, "/?");
            String rawHost = split < 0 ? raw : raw.substring(0, split);
            String rawRest = split < 0 ? "" : raw.substring(split);
            if (rawHost.isEmpty() || rawHost.chars().anyMatch(Character::isWhitespace) || rawRest.chars().anyMatch(Character::isWhitespace)) {
                return Optional.empty();
            }
            return Optional.of(new Target(comparable(rawHost), comparable(rawRest), rawHost, rawRest));
        }


        int length() {
            return host.length() + rest.length();
        }


        Optional<URI> fetchUri() {
            try {
                String asciiHost = IDN.toASCII(rawHost.toLowerCase(Locale.ROOT), IDN.ALLOW_UNASSIGNED).toLowerCase(Locale.ROOT);
                return Optional.of(new URI("https://" + asciiHost + asciiEncoded(rawRest)));
            } catch (URISyntaxException | IllegalArgumentException ex) {
                return Optional.empty();
            }
        }


        private static int indexOfAny(String text, String characters) {
            for (int i = 0; i < text.length(); i++) {
                if (characters.indexOf(text.charAt(i)) >= 0) {
                    return i;
                }
            }
            return -1;
        }


        /** ASCII 밖 문자와 주소에 쓸 수 없는 문자를 UTF-8 퍼센트 인코딩한다. 이미 있는 {@code %XX} 는 그대로 둔다. */
        private static String asciiEncoded(String text) {
            StringBuilder result = new StringBuilder();
            for (byte value : text.getBytes(StandardCharsets.UTF_8)) {
                int unsigned = value & 0xff;
                if (unsigned < 0x80 && URL_CHARACTERS.indexOf(unsigned) >= 0) {
                    result.append((char)unsigned);
                } else {
                    result.append('%').append(String.format(Locale.ROOT, "%02X", unsigned));
                }
            }
            return result.toString();
        }
    }
}
