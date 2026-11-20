package com.yangpang.domain.order;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
import java.util.Collection;
import java.util.Optional;

public interface OrderRepository extends JpaRepository<Order, Long> {

    Optional<Order> findByOrderNoAndMemberId(String orderNo, Long memberId);

    /** 화면 표시용: 주문 상품까지 한 번에 */
    @Query("select distinct o from Order o left join fetch o.items where o.orderNo = :orderNo and o.memberId = :memberId")
    Optional<Order> findWithItems(String orderNo, Long memberId);

    Optional<Order> findByOrderNo(String orderNo);

    @Query("select distinct o from Order o left join fetch o.items where o.id = :id")
    Optional<Order> findWithItemsById(Long id);

    @Query("select distinct o from Order o left join fetch o.items where o.subscriptionId = :subscriptionId order by o.orderedAt desc")
    java.util.List<Order> findBySubscription(Long subscriptionId);

    @Query("""
            select o from Order o
            where o.memberId = :memberId
              and o.orderedAt >= :from
              and o.status in :statuses
            order by o.orderedAt desc
            """)
    Page<Order> findMyOrders(Long memberId, Instant from, Collection<OrderStatus> statuses, Pageable pageable);

    @Query(value = "select nextval('order_no_seq')", nativeQuery = true)
    long nextOrderNoSequence();

    /** 관리자 주문 목록: 상태 + 주문번호·받는 분 검색 (keyword는 빈 문자열이면 전체) */
    @Query("""
            select o from Order o
            where o.status in :statuses
              and (:keyword = '' or o.orderNo like concat('%', :keyword, '%') or o.recipient like concat('%', :keyword, '%'))
            order by o.orderedAt desc
            """)
    Page<Order> adminSearch(Collection<OrderStatus> statuses, String keyword, Pageable pageable);

    long countByOrderedAtGreaterThanEqual(Instant from);

    /** 결제 화면에서 결제하지 않고 둔 주문 (재고 선점 만료 대상) */
    @Query("""
            select o from Order o
            where o.status = com.yangpang.domain.order.OrderStatus.PENDING_PAYMENT
              and o.orderedAt < :cutoff
              and not exists (select p.id from MockPayment p where p.orderId = o.id)
            """)
    java.util.List<Order> findUnpaidBefore(Instant cutoff);

    /** 가상계좌 발급 후 입금을 기다리는 주문 */
    @Query("""
            select o from Order o
            where o.status = com.yangpang.domain.order.OrderStatus.PENDING_PAYMENT
              and exists (select p.id from MockPayment p where p.orderId = o.id
                          and p.status = com.yangpang.domain.order.MockPayment.Status.PENDING)
            """)
    java.util.List<Order> findAwaitingDeposit();

    long countByStatus(OrderStatus status);

    @Query("select coalesce(sum(o.paidAmount), 0) from Order o where o.orderedAt >= :from and o.status not in :excluded")
    long sumPaidSince(Instant from, Collection<OrderStatus> excluded);
}
