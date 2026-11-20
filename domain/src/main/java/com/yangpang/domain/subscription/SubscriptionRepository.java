package com.yangpang.domain.subscription;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface SubscriptionRepository extends JpaRepository<Subscription, Long> {

    Optional<Subscription> findByIdAndMemberId(Long id, Long memberId);

    List<Subscription> findByMemberIdOrderByStatusAscIdDesc(Long memberId);

    boolean existsByMemberIdAndVendorItemIdAndStatus(Long memberId, Long vendorItemId, Subscription.Status status);

    /** 주문일(배송 예정일 - 이틀)이 된 정기배송 */
    @Query("""
            select s.id from Subscription s where s.status = com.yangpang.domain.subscription.Subscription.Status.ACTIVE
            and s.nextDeliveryDate <= :lastDelivery order by s.nextDeliveryDate, s.id""")
    List<Long> findDueIds(LocalDate lastDelivery);
}
