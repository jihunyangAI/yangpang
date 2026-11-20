package com.yangpang.domain.order;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface GiftRepository extends JpaRepository<Gift, Long> {

    Optional<Gift> findByToken(String token);

    Optional<Gift> findByOrderId(Long orderId);

    @Query("select g from Gift g where g.status = com.yangpang.domain.order.Gift.Status.WAITING and g.expiresAt < ?1")
    List<Gift> findWaitingExpiredBefore(Instant now);

    /** 기한 전이라도 주문이 결제 실패·취소로 끝난 선물 정리용 */
    @Query("""
            select g from Gift g, Order o where o.id = g.orderId and g.status = com.yangpang.domain.order.Gift.Status.WAITING
            and o.status in (com.yangpang.domain.order.OrderStatus.CANCELED, com.yangpang.domain.order.OrderStatus.PAYMENT_FAILED)""")
    List<Gift> findWaitingWithEndedOrder();
}
