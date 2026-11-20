package com.yangpang.domain.order;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;

/**
 * 선물하기 (M4 ④). 보내는 분은 받는 분 이름·연락처만 입력하고, 받는 분이 링크(가짜 문자)에서 배송지를 입력해야 출고된다.
 * 링크 주소의 토큰이 곧 열쇠라 로그인 없이 열 수 있다.
 */
@Getter
@Entity
@Table(name = "gift")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Gift {

    /** 받는 분이 이 기간 안에 배송지를 입력하지 않으면 자동 취소·환불 */
    /** 기본값 (실제 값은 관리자 설정 GIFT_ACCEPT_DAYS) */
    public static final Duration ACCEPT_WINDOW = Duration.ofDays(5);

    public enum Status {
        WAITING("배송지 입력 대기"), ACCEPTED("배송지 입력 완료"), EXPIRED("기한 만료 · 자동 취소"), CANCELED("주문 취소");

        private final String label;

        Status(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String ALPHABET = "abcdefghijkmnpqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ23456789";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "order_id", nullable = false, unique = true)
    private Long orderId;

    @Column(nullable = false, unique = true, length = 40)
    private String token;

    @Column(name = "sender_name", nullable = false, length = 50)
    private String senderName;

    @Column(name = "recipient_name", nullable = false, length = 50)
    private String recipientName;

    @Column(name = "recipient_phone", nullable = false, length = 20)
    private String recipientPhone;

    @Column(length = 200)
    private String message;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Status status;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "accepted_at")
    private Instant acceptedAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    static Gift create(Long orderId, String senderName, String recipientName, String recipientPhone, String message, Instant now) {
        return create(orderId, senderName, recipientName, recipientPhone, message, now, ACCEPT_WINDOW);
    }

    static Gift create(Long orderId, String senderName, String recipientName, String recipientPhone, String message, Instant now,
                       Duration window) {
        Gift g = new Gift();
        g.orderId = orderId;
        g.token = newToken();
        g.senderName = senderName;
        g.recipientName = recipientName;
        g.recipientPhone = recipientPhone;
        g.message = message;
        g.status = Status.WAITING;
        g.expiresAt = now.plus(window);
        g.createdAt = now;
        return g;
    }

    static String newToken() {
        StringBuilder sb = new StringBuilder(20);
        for (int i = 0; i < 20; i++) sb.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
        return sb.toString();
    }

    public boolean waiting() {
        return status == Status.WAITING;
    }

    void restartWindow(Instant now) {
        restartWindow(now, ACCEPT_WINDOW);
    }

    void restartWindow(Instant now, Duration window) {
        this.expiresAt = now.plus(window);
    }

    public void accept(Instant now) {
        this.status = Status.ACCEPTED;
        this.acceptedAt = now;
    }

    public void end(Status status) {
        this.status = status;
    }

    /** 받는 분 연락처 가운데를 가린 값: 010-****-5678 */
    public String maskedPhone() {
        String d = recipientPhone.replaceAll("\\D", "");
        return d.length() < 8 ? recipientPhone : d.substring(0, 3) + "-****-" + d.substring(d.length() - 4);
    }
}
