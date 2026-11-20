package com.yangpang.domain.deal;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
import java.util.Collection;
import java.util.List;

public interface DealRepository extends JpaRepository<Deal, Long> {

    /** 지금 진행 중인 특가 (품절 포함) */
    @Query("select d from Deal d where d.startsAt <= :now and d.endsAt > :now order by d.id")
    List<Deal> findActive(Instant now);

    @Query("select d from Deal d where d.vendorItemId in :vendorItemIds and d.startsAt <= :now and d.endsAt > :now")
    List<Deal> findActiveFor(Collection<Long> vendorItemIds, Instant now);

    boolean existsBySourceAndStartsAt(String source, Instant startsAt);

    /**
     * 특가 수량 확보: 남은 수량이 충분할 때만 늘린다 (원자적).
     * 동시에 100건이 들어와도 행 잠금 순서대로 처리되어 정확히 남은 만큼만 성공한다.
     * @return 1이면 성공, 0이면 수량 부족·기간 끝남
     */
    @Modifying
    @Query(value = """
            update deal set sold = sold + :quantity
            where id = :id and sold + :quantity <= quantity and starts_at <= :now and ends_at > :now""", nativeQuery = true)
    int reserve(long id, int quantity, Instant now);

    /** 취소된 주문의 특가 수량 되돌리기 */
    @Modifying
    @Query(value = "update deal set sold = greatest(sold - :quantity, 0) where id = :id", nativeQuery = true)
    int release(long id, int quantity);

    @Query(value = """
            select coalesce(sum(oi.quantity), 0) from order_item oi join orders o on o.id = oi.order_id
            where oi.deal_id = :dealId and o.member_id = :memberId and oi.status not in ('CANCELED', 'PAYMENT_FAILED')""",
            nativeQuery = true)
    int boughtBy(long dealId, long memberId);
}
