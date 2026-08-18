package com.borderless.proxy.routing;

import com.borderless.proxy.routing.dto.LanguageDetection;
import com.optimaize.langdetect.LanguageDetectorBuilder;
import com.optimaize.langdetect.ngram.NgramExtractors;
import com.optimaize.langdetect.profiles.LanguageProfile;
import com.optimaize.langdetect.profiles.LanguageProfileReader;
import com.optimaize.langdetect.text.CommonTextObjectFactories;
import com.optimaize.langdetect.text.TextObjectFactory;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.List;

/**
 * 텍스트의 언어를 감지한다. 텍스트를 변형하지 않고 판단만 한다.
 *
 * <p>내부적으로 optimaize language-detector의 내장 프로파일(70개 언어, en/vi/tl 포함)을 사용한다.
 * 프로파일 로딩 비용이 크므로 싱글턴 빈으로 한 번만 초기화한다.
 *
 * <p>짧은 텍스트나 이모지·숫자처럼 언어 특성이 없는 입력은 감지에 실패할 수 있다.
 * 그런 경우 예외를 던지지 않고 {@link LanguageDetection#undetermined()}를 반환하며,
 * 최종 폴백 판단은 신뢰도 임계치를 아는 라우터가 처리한다.
 */
@Slf4j
@Component
public class LanguageDetector {

    /** optimaize의 감지기. 이 클래스와 이름이 겹쳐 정규화된 이름으로 참조한다. */
    private final com.optimaize.langdetect.LanguageDetector delegate;

    private final TextObjectFactory textObjectFactory;

    public LanguageDetector() {
        this(loadBuiltInDetector(), CommonTextObjectFactories.forDetectingOnLargeText());
    }

    LanguageDetector(com.optimaize.langdetect.LanguageDetector delegate, TextObjectFactory textObjectFactory) {
        this.delegate = delegate;
        this.textObjectFactory = textObjectFactory;
    }

    /**
     * 텍스트의 언어와 신뢰도를 반환한다.
     *
     * @param text 감지할 텍스트
     * @return 감지 결과. 언어를 특정할 수 없으면 {@link LanguageDetection#undetermined()}
     */
    public LanguageDetection detect(String text) {
        if (text == null || text.isBlank()) {
            return LanguageDetection.undetermined();
        }

        List<com.optimaize.langdetect.DetectedLanguage> probabilities =
                delegate.getProbabilities(textObjectFactory.forText(text));

        if (probabilities.isEmpty()) {
            log.debug("언어를 특정하지 못했습니다. 길이={}", text.length());
            return LanguageDetection.undetermined();
        }

        com.optimaize.langdetect.DetectedLanguage top = probabilities.getFirst();
        return new LanguageDetection(top.getLocale().getLanguage(), clampToProbability(top.getProbability()));
    }

    private static com.optimaize.langdetect.LanguageDetector loadBuiltInDetector() {
        try {
            List<LanguageProfile> profiles = new LanguageProfileReader().readAllBuiltIn();
            return LanguageDetectorBuilder.create(NgramExtractors.standard())
                    .withProfiles(profiles)
                    .build();
        } catch (IOException e) {
            throw new IllegalStateException("언어 감지 프로파일을 읽지 못했습니다.", e);
        }
    }

    /** 부동소수 오차로 0.0~1.0을 벗어나는 값이 DTO 검증에 걸리지 않도록 잘라낸다. */
    private static double clampToProbability(double value) {
        return Math.clamp(value, 0.0, 1.0);
    }
}
