package com.yangpang.domain.membership;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface MembershipRepository extends JpaRepository<Membership, Long> {

    Optional<Membership> findByMemberId(Long memberId);

    /** 기간이 끝나 갱신·종료해야 하는 멤버십 */
    @Query("select m from Membership m where m.status <> com.yangpang.domain.membership.Membership.Status.ENDED and m.currentPeriodEnd <= :now")
    List<Membership> findDue(Instant now);
}
