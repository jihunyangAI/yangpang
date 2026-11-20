package com.yangpang.domain.display;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

/** 메인 배너 (관리자 전시 관리) */
@Getter
@Entity
@Table(name = "banner")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Banner {

    public enum Theme {
        PLUS("양팡플러스 청회색", "from-sea-600 to-sea-600"),
        SALE("특가 다홍", "from-brand-600 to-brand-600"),
        FRESH("프레시 초록", "from-fresh to-fresh"),
        BRAND("양팡 테라코타", "from-brand-700 to-brand-700"),
        DARK("짙은 먹색", "from-gray-900 to-gray-900");

        private final String label;
        private final String gradient;

        Theme(String label, String gradient) {
            this.label = label;
            this.gradient = gradient;
        }

        public String label() {
            return label;
        }

        /** Tailwind 배경 클래스 (디자인 개편 뒤 단색: 시작·끝 색이 같다) (app.css에 포함되도록 이 파일 문자열이 그대로 쓰임) */
        public String gradient() {
            return gradient;
        }
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 60)
    private String title;

    @Column(length = 100)
    private String subtitle;

    @Column(length = 40)
    private String caption;

    @Column(name = "link_url", nullable = false, length = 300)
    private String linkUrl;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Theme theme;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    @Column(nullable = false)
    private boolean active;

    @Column(name = "starts_at")
    private Instant startsAt;

    @Column(name = "ends_at")
    private Instant endsAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    public static Banner create(String title, String subtitle, String caption, String linkUrl, Theme theme, int sortOrder,
                                Instant startsAt, Instant endsAt, Instant now) {
        Banner b = new Banner();
        b.title = title;
        b.subtitle = subtitle;
        b.caption = caption;
        b.linkUrl = linkUrl;
        b.theme = theme;
        b.sortOrder = sortOrder;
        b.active = true;
        b.startsAt = startsAt;
        b.endsAt = endsAt;
        b.createdAt = now;
        return b;
    }

    public void toggle() {
        this.active = !this.active;
    }

    public void move(int sortOrder) {
        this.sortOrder = sortOrder;
    }

    /** 지금 노출 중인지 (켜져 있고 기간 안) */
    public boolean isShowing(Instant now) {
        return active && (startsAt == null || !now.isBefore(startsAt)) && (endsAt == null || now.isBefore(endsAt));
    }
}
