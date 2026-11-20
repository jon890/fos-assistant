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
 * 짧은 주소를 펼친 주소는 대화에 없는 주소다.
 *
 * <p>메시지를 공백으로 나눈 낱말 하나가 주소 하나다. 낱말 앞의 여는 괄호와 따옴표, 끝의 문장 부호를 떼고 앞의 scheme 만 뗀다. 그 낱말 전체가
 * 가리킨 주소와 같거나, 낱말을 주소에 쓸 수 없는 첫 문자(한글 조사 등)에서 자른 앞부분이 같을 때만 맞는다. 한글 경로를 그대로 붙여 넣은 주소는 낱말
 * 전체로 맞는다. 낱말 가운데의 다른 주소(추적 링크 안의 주소)나 주소의 앞부분은 맞지 않는다.
 */
@Service
@RequiredArgsConstructor
public class ConversationLinks {
    private static final Pattern SCHEME = Pattern.compile("(?i)https?://");
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");
    /** 메시지에서 주소 앞에 붙어도 주소로 보지 않는 여는 괄호와 따옴표다. */
    private static final String LEADING = "([{<\"'「『“‘";
    /** 주소에 쓸 수 있는 ASCII 문자(RFC 3986 의 unreserved, reserved 와 {@code %})다. 이것이 아닌 문자가 주소의 경계다. */
    private static final String URL_CHARACTERS = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789-._~:/?#[]@!$&'()*+,;=%";
    /** 메시지에서 주소 끝에 붙어도 주소로 보지 않는 문장 부호다. */
    private static final String TRAILING = ".,)!?]}>\"'」』”’;:";

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
            for (String word : WHITESPACE.split(content)) {
                if (target.get().matches(word)) {
                    return target.get().fetchUri();
                }
            }
        }
        return Optional.empty();
    }


    /** 낱말 하나를 주소로 다듬는다. 앞의 여는 괄호와 따옴표, 끝의 문장 부호와 조각을 떼고 앞의 scheme 만 뗀다. */
    private static String trimWord(String word) {
        int begin = 0;
        int finish = word.length();
        while (begin < finish && LEADING.indexOf(word.charAt(begin)) >= 0) {
            begin++;
        }
        while (finish > begin && TRAILING.indexOf(word.charAt(finish - 1)) >= 0) {
            finish--;
        }
        String trimmed = word.substring(begin, finish);
        Matcher scheme = SCHEME.matcher(trimmed);
        if (scheme.lookingAt()) {
            trimmed = trimmed.substring(scheme.end());
        }
        int hash = trimmed.indexOf('#');
        return hash >= 0 ? trimmed.substring(0, hash) : trimmed;
    }


    /** 주소에 쓸 수 없는 첫 문자 앞까지다. 그 문자가 없으면 낱말 전체다. 끝의 문장 부호도 뗀다. */
    private static String urlPrefix(String word) {
        int index = 0;
        while (index < word.length() && URL_CHARACTERS.indexOf(word.charAt(index)) >= 0) {
            index++;
        }
        int finish = index;
        while (finish > 0 && TRAILING.indexOf(word.charAt(finish - 1)) >= 0) {
            finish--;
        }
        return word.substring(0, finish);
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


        /** 메시지의 낱말 하나가 이 주소인가. 낱말 전체나 주소에 쓸 수 없는 첫 문자 앞까지가 같아야 한다. */
        boolean matches(String word) {
            String trimmed = trimWord(word);
            return sameAs(comparable(trimmed)) || sameAs(comparable(urlPrefix(trimmed)));
        }


        private boolean sameAs(String candidate) {
            return candidate.length() == host.length() + rest.length()
                && candidate.regionMatches(true, 0, host, 0, host.length())
                && candidate.startsWith(rest, host.length());
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
