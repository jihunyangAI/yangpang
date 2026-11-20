package com.yangpang.domain.order;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface OrderItemRepository extends JpaRepository<OrderItem, Long> {

    /** 시뮬레이터: 진행할 상품 (오래 기다린 것부터). 선물 배송지 입력 전 상품은 빼고 */
    @Query("""
            select i from OrderItem i join fetch i.order o
            where i.status in :statuses
              and not (o.giftWaiting = true and i.status = com.yangpang.domain.order.OrderStatus.PAID)
            order by i.statusChangedAt asc, i.id asc
            """)
    List<OrderItem> findByStatuses(Collection<OrderStatus> statuses, Pageable pageable);

    @Query("select i from OrderItem i join fetch i.order o where i.id = :id and o.memberId = :memberId")
    Optional<OrderItem> findMine(Long id, Long memberId);

    /** 리뷰를 쓸 수 있는 상품 (배송완료·구매확정, 아직 리뷰 없음) */
    @Query("""
            select i from OrderItem i join fetch i.order o
            where o.memberId = :memberId
              and i.status in (com.yangpang.domain.order.OrderStatus.DELIVERED, com.yangpang.domain.order.OrderStatus.CONFIRMED)
              and not exists (select r.id from com.yangpang.domain.review.Review r where r.orderItemId = i.id)
            order by i.statusChangedAt desc
            """)
    List<OrderItem> findReviewable(Long memberId, Pageable pageable);
}
