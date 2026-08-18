package com.borderless.proxy.global.config;

import com.borderless.proxy.member.entity.AuthProvider;
import com.borderless.proxy.member.entity.Member;
import com.borderless.proxy.member.repository.MemberRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.oauth2.client.userinfo.DefaultOAuth2UserService;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserRequest;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class CustomOAuth2UserService extends DefaultOAuth2UserService {

    private final MemberRepository memberRepository;

    @Override
    public OAuth2User loadUser(OAuth2UserRequest userRequest) throws OAuth2AuthenticationException {
        OAuth2User oAuth2User = super.loadUser(userRequest);
        Map<String, Object> attributes = oAuth2User.getAttributes();

        Long kakaoId = (Long) attributes.get("id");
        Map<String, Object> kakaoAccount = (Map<String, Object>) attributes.get("kakao_account");
        Map<String, Object> profile = (Map<String, Object>) kakaoAccount.get("profile");

        String providerId = String.valueOf(kakaoId);
        String nickname = (String) profile.get("nickname");

        // DB에서 조회 후 없으면 신규 가입 진행
        Member member = memberRepository.findByProviderAndProviderId(AuthProvider.kakao, providerId)
                .orElseGet(() -> {
                    Member newMember = Member.builder()
                            .provider(AuthProvider.kakao)
                            .providerId(providerId)
                            .name(nickname)
                            .nativeLang("ko")
                            .build();
                    return memberRepository.save(newMember);
                });

        System.out.println("로그인 성공! 카카오 ID: " + kakaoId + ", 닉네임: " + nickname);

        Map<String, Object> modifiedAttributes = new HashMap<>(attributes);
        modifiedAttributes.put("memberId", member.getId());
        return new DefaultOAuth2User(
                oAuth2User.getAuthorities(),
                modifiedAttributes,
                "id"
        );
    }
}
