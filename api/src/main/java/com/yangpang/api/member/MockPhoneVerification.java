package com.yangpang.api.member;

import jakarta.servlet.http.HttpSession;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

/**
 * 모의 휴대폰 인증 (기획서 M-02).
 * 실제 SMS 대신 인증번호를 화면(토스트)과 서버 로그에 보여 준다. 세션에 3분간 보관.
 */
@Slf4j
@Component
public class MockPhoneVerification {

    static final Duration TTL = Duration.ofMinutes(3);
    private static final String KEY = "mockPhoneVerification";
    private static final SecureRandom RANDOM = new SecureRandom();

    private record State(String phone, String code, Instant expiresAt, boolean verified) {
    }

    private final Clock clock;

    public MockPhoneVerification(Clock clock) {
        this.clock = clock;
    }

    /** 인증번호 발급. 반환값을 화면에 보여 준다 (모의) */
    public String issue(HttpSession session, String phone) {
        String code = String.format("%06d", RANDOM.nextInt(1_000_000));
        session.setAttribute(KEY, new State(phone, code, clock.instant().plus(TTL), false));
        log.info("[모의 SMS] {} 인증번호: {}", phone, code);
        return code;
    }

    /** 인증번호 확인. 맞으면 인증 완료로 표시 */
    public boolean confirm(HttpSession session, String phone, String code) {
        if (!(session.getAttribute(KEY) instanceof State s)) return false;
        boolean ok = s.phone().equals(phone) && s.code().equals(code) && clock.instant().isBefore(s.expiresAt());
        if (ok) session.setAttribute(KEY, new State(s.phone(), s.code(), s.expiresAt(), true));
        return ok;
    }

    public boolean isVerified(HttpSession session, String phone) {
        return session.getAttribute(KEY) instanceof State s && s.verified() && s.phone().equals(phone);
    }

    public void clear(HttpSession session) {
        session.removeAttribute(KEY);
    }
}
