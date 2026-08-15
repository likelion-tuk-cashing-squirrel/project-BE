package com.borderless.proxy.routing;

import com.knuddels.jtokkit.Encodings;
import com.knuddels.jtokkit.api.Encoding;
import com.knuddels.jtokkit.api.EncodingRegistry;
import com.knuddels.jtokkit.api.EncodingType;
import org.springframework.stereotype.Component;

/**
 * 텍스트의 OpenAI 토큰 수를 계산한다.
 *
 * <p>인코딩은 {@link EncodingType#O200K_BASE}로 고정한다. 기본값이나 CL100K를 쓰면
 * 실제 호출 모델과 토큰 수가 어긋나 라우팅 임계치 판단이 틀어진다.
 *
 * <p>{@link Encoding} 인스턴스 초기화 비용이 크므로 매 호출마다 생성하지 않고
 * 싱글턴 빈의 필드로 한 번만 만들어 재사용한다.
 */
@Component
public class TokenCalculator {

    private static final EncodingRegistry REGISTRY = Encodings.newLazyEncodingRegistry();

    private final Encoding encoding = REGISTRY.getEncoding(EncodingType.O200K_BASE);

    /**
     * o200k_base 기준 토큰 수를 반환한다.
     *
     * @param text 계산할 텍스트. {@code null}이거나 공백뿐이면 0
     */
    public int countTokens(String text) {
        if (isBlank(text)) {
            return 0;
        }
        return encoding.countTokens(text);
    }

    /**
     * 내용이 없는 텍스트인지 판단한다.
     *
     * <p>{@link String#isBlank()}은 {@code Character.isWhitespace}를 쓰기 때문에
     * non-breaking space(U+00A0)처럼 화면상 공백인 문자를 걸러내지 못한다.
     * 붙여넣기로 들어오는 텍스트에 섞이기 쉬운 문자라 {@code isSpaceChar}까지 함께 본다.
     */
    private static boolean isBlank(String text) {
        if (text == null || text.isEmpty()) {
            return true;
        }
        return text.codePoints()
                .allMatch(codePoint -> Character.isWhitespace(codePoint) || Character.isSpaceChar(codePoint));
    }
}
