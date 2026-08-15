package com.borderless.proxy.routing;

/**
 * 라우팅 테스트에서 공유하는 샘플 텍스트.
 *
 * <p>주석의 토큰 수는 JTokkit o200k_base 기준 실측값이다.
 * 임계치(기본 50) 경계를 기준으로 짧은 문장과 긴 문단을 구분해두었다.
 */
final class RoutingSamples {

    /** 짧은 영어 인사말. 9 토큰. */
    static final String SHORT_ENGLISH = "Hi there, how are you doing today?";

    /** 짧은 베트남어 문장. 10 토큰. */
    static final String SHORT_VIETNAMESE = "Chào bạn, hôm nay bạn thế nào?";

    /** 짧은 필리핀어 문장. */
    static final String SHORT_TAGALOG = "Kumusta ka ngayon, kaibigan?";

    /** 긴 영어 문단. 58 토큰. */
    static final String LONG_ENGLISH = """
            The deployment pipeline runs automatically whenever a new commit is pushed to the main branch. \
            It builds the application, runs the entire test suite, and then publishes a container image to the registry. \
            If any step fails, the team receives a notification and the release is halted until someone investigates the problem.""";

    /** 긴 베트남어 문단. 86 토큰. 같은 의미의 영어 문단보다 약 1.5배 토큰을 쓴다. */
    static final String LONG_VIETNAMESE = """
            Quy trình triển khai sẽ tự động chạy mỗi khi có một commit mới được đẩy lên nhánh chính. \
            Hệ thống sẽ xây dựng ứng dụng, chạy toàn bộ bộ kiểm thử, sau đó tải ảnh container lên kho lưu trữ. \
            Nếu bất kỳ bước nào thất bại, cả nhóm sẽ nhận được thông báo và bản phát hành sẽ bị tạm dừng cho đến khi có người kiểm tra nguyên nhân.""";

    /** 긴 필리핀어(타갈로그) 문단. 114 토큰. */
    static final String LONG_TAGALOG = """
            Ang proseso ng paglalabas ng bagong bersyon ay awtomatikong tumatakbo tuwing may bagong pagbabago \
            na itinutulak sa pangunahing sangay ng imbakan. Binubuo nito ang aplikasyon, pinapatakbo ang buong \
            hanay ng mga pagsusulit, at pagkatapos ay ipinapadala ang larawan ng lalagyan sa imbakan. Kung mabibigo \
            ang alinmang hakbang, makakatanggap ng abiso ang buong pangkat at ititigil ang paglabas hanggang sa \
            may taong magsiyasat sa dahilan ng problema.""";

    /**
     * 의미가 같은 영어 / 베트남어 / 필리핀어 문장 쌍.
     * 비영어권 언어의 토큰화 효율이 낮다는 전제를 검증하는 데 쓴다. (10 / 14 / 20 토큰)
     */
    static final String EQUIVALENT_ENGLISH = "I wrote the standard operating procedure for the deployment.";
    static final String EQUIVALENT_VIETNAMESE = "Tôi đã viết quy trình vận hành chuẩn cho việc triển khai.";
    static final String EQUIVALENT_TAGALOG = "Nagsulat ako ng pamantayang paraan ng pagpapatakbo para sa paglalabas.";

    /** 이모지와 숫자만으로 구성된 텍스트. 201 토큰이라 임계치는 넘지만 언어를 특정할 수 없다. */
    static final String EMOJI_AND_DIGITS_ONLY = "🚀🎉🔥 1234567890 ".repeat(20);

    /** 공백 문자만 있는 텍스트. */
    static final String WHITESPACE_ONLY = " \t\n\r\u00A0  ";

    /** 아주 긴 텍스트. 영어 문단을 200번 이어붙여 약 11,600 토큰. */
    static final String VERY_LONG_TEXT = (LONG_ENGLISH + "\n\n").repeat(200);

    private RoutingSamples() {
    }
}
