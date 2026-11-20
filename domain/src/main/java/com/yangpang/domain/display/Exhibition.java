package com.yangpang.domain.display;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

/** 기획전 (테마별로 상품을 모아 보여주는 페이지, 전용 쿠폰 가능) */
@Getter
@Entity
@Table(name = "exhibition")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Exhibition {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 60)
    private String slug;

    @Column(nullable = false, length = 100)
    private String title;

    @Column(length = 200)
    private String subtitle;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Banner.Theme theme;

    @Column(name = "coupon_id")
    private Long couponId;

    @Column(name = "starts_at", nullable = false)
    private Instant startsAt;

    @Column(name = "ends_at", nullable = false)
    private Instant endsAt;

    @Column(nullable = false)
    private boolean active;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private Instant createdAt;

    static Exhibition create(String slug, String title, String subtitle, Banner.Theme theme, Long couponId,
                             Instant startsAt, Instant endsAt, int sortOrder) {
        Exhibition e = new Exhibition();
        e.slug = slug;
        e.title = title;
        e.subtitle = subtitle;
        e.theme = theme;
        e.couponId = couponId;
        e.startsAt = startsAt;
        e.endsAt = endsAt;
        e.active = true;
        e.sortOrder = sortOrder;
        return e;
    }

    void toggle() {
        this.active = !active;
    }

    public boolean isLive(Instant now) {
        return active && !now.isBefore(startsAt) && now.isBefore(endsAt);
    }
}
