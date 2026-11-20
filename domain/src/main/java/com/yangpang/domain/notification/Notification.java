package com.yangpang.domain.notification;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

/** 알림함 한 건 (앱 푸시·문자 대신 사이트 안 알림함만 둔다) */
@Getter
@Entity
@Table(name = "notification")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Notification {

    public enum Type {
        ORDER("주문"), DELIVERY("배송"), CLAIM("취소·반품"), PRICE_DROP("가격 인하"), RESTOCK("재입고"), BENEFIT("혜택");

        private final String label;

        Type(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }

        public String icon() {
            return switch (this) {
                case ORDER -> "🧾";
                case DELIVERY -> "🚚";
                case CLAIM -> "↩️";
                case PRICE_DROP -> "📉";
                case RESTOCK -> "📦";
                case BENEFIT -> "🎁";
            };
        }
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "member_id", nullable = false)
    private Long memberId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Type type;

    @Column(nullable = false, length = 100)
    private String title;

    @Column(length = 300)
    private String body;

    @Column(length = 200)
    private String link;

    @Column(name = "image_url", length = 200)
    private String imageUrl;

    @Column(name = "read_at")
    private Instant readAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    static Notification of(Long memberId, Type type, String title, String body, String link, String imageUrl, Instant at) {
        Notification n = new Notification();
        n.memberId = memberId;
        n.type = type;
        n.title = title.length() > 100 ? title.substring(0, 100) : title;
        n.body = body != null && body.length() > 300 ? body.substring(0, 300) : body;
        n.link = link;
        n.imageUrl = imageUrl;
        n.createdAt = at;
        return n;
    }

    public boolean isRead() {
        return readAt != null;
    }
}
