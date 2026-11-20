package com.yangpang.domain.coupon;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.Collection;
import java.util.List;

public interface MemberCouponRepository extends JpaRepository<MemberCoupon, Long> {

    @Query("select m from MemberCoupon m join fetch m.coupon where m.memberId = :memberId order by m.issuedAt desc")
    List<MemberCoupon> findAllOfMember(Long memberId);

    @Query("select m from MemberCoupon m join fetch m.coupon where m.memberId = :memberId and m.status = 'AVAILABLE'")
    List<MemberCoupon> findAvailable(Long memberId);

    boolean existsByMemberIdAndCouponId(Long memberId, Long couponId);

    @Query("select m.coupon.id from MemberCoupon m where m.memberId = :memberId")
    List<Long> ownedCouponIds(Long memberId);

    List<MemberCoupon> findByUsedOrderId(Long orderId);

    @Query("select m from MemberCoupon m join fetch m.coupon where m.id in :ids and m.memberId = :memberId")
    List<MemberCoupon> findMine(Collection<Long> ids, Long memberId);
}
