package com.yangpang.domain.reco;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Clock;
import java.util.Collection;

/** 회원 행동 로그 (M4 ②): 조회·담기·찜·주문·검색. 개인화 추천과 함께 본 상품 계산에 쓴다 */
@Service
@RequiredArgsConstructor
@Transactional(propagation = org.springframework.transaction.annotation.Propagation.REQUIRES_NEW) // 읽기 전용 화면(검색 등)에서도 기록되도록
public class UserEventService {

    public enum Type {VIEW, CART, WISH, ORDER, SEARCH}

    private final JdbcTemplate jdbc;
    private final Clock clock;

    public void product(Long memberId, Type type, Long productId) {
        if (memberId == null || productId == null) return;
        jdbc.update("insert into user_event(member_id, event_type, product_id, created_at) values (?, ?, ?, ?)",
                memberId, type.name(), productId, Timestamp.from(clock.instant()));
    }

    /** 장바구니 담기: 판매 오퍼 id로 상품을 찾아 기록 */
    public void cart(Long memberId, Long vendorItemId) {
        if (memberId == null) return;
        jdbc.update("""
                insert into user_event(member_id, event_type, product_id, created_at)
                select ?, 'CART', i.product_id, ? from vendor_item vi join item i on i.id = vi.item_id where vi.id = ?""",
                memberId, Timestamp.from(clock.instant()), vendorItemId);
    }

    public void ordered(Long memberId, Collection<Long> productIds) {
        if (memberId == null) return;
        Timestamp now = Timestamp.from(clock.instant());
        for (Long pid : productIds.stream().distinct().toList()) {
            jdbc.update("insert into user_event(member_id, event_type, product_id, created_at) values (?, 'ORDER', ?, ?)", memberId, pid, now);
        }
    }

    public void search(Long memberId, String keyword) {
        if (memberId == null || keyword == null || keyword.isBlank()) return;
        String k = keyword.trim();
        jdbc.update("insert into user_event(member_id, event_type, keyword, created_at) values (?, 'SEARCH', ?, ?)",
                memberId, k.length() > 100 ? k.substring(0, 100) : k, Timestamp.from(clock.instant()));
    }
}
