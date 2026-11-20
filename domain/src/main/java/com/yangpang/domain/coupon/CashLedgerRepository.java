package com.yangpang.domain.coupon;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface CashLedgerRepository extends JpaRepository<CashLedger, Long> {

    @Query("select coalesce(sum(l.amount), 0) from CashLedger l where l.memberId = :memberId")
    long balance(Long memberId);

    Page<CashLedger> findByMemberIdOrderByCreatedAtDescIdDesc(Long memberId, Pageable pageable);

    boolean existsByMemberIdAndTypeAndOrderIdAndDescription(Long memberId, CashLedger.Type type, Long orderId, String description);
}
