package com.yangpang.domain.claim;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface ClaimRepository extends JpaRepository<Claim, Long> {

    Page<Claim> findByMemberIdOrderByRequestedAtDesc(Long memberId, Pageable pageable);

    Optional<Claim> findByClaimNoAndMemberId(String claimNo, Long memberId);

    Optional<Claim> findByClaimNo(String claimNo);

    List<Claim> findByOrderIdOrderByRequestedAtAsc(Long orderId);

    /** 시뮬레이터: 진행 중인 반품·교환 */
    @Query("select c from Claim c where c.status in :statuses and c.type <> com.yangpang.domain.claim.ClaimEnums.Type.CANCEL order by c.statusChangedAt")
    List<Claim> findInProgress(Collection<ClaimEnums.Status> statuses, Pageable pageable);

    /** 셀러센터 검수 목록 (상품까지 함께 읽음) */
    @Query("""
            select distinct c from Claim c left join fetch c.items i left join fetch i.orderItem
            where c.sellerId = :sellerId and c.status in :statuses order by c.requestedAt desc""")
    List<Claim> findForSeller(Long sellerId, Collection<ClaimEnums.Status> statuses);

    Page<Claim> findByStatusInOrderByRequestedAtDesc(Collection<ClaimEnums.Status> statuses, Pageable pageable);

    @Query(value = "select nextval('claim_no_seq')", nativeQuery = true)
    long nextSequence();
}
