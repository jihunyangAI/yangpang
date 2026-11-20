package com.yangpang.domain.membership;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface MembershipBillingRepository extends JpaRepository<MembershipBilling, Long> {
    List<MembershipBilling> findByMembershipIdOrderByBilledAtDesc(Long membershipId);
}
