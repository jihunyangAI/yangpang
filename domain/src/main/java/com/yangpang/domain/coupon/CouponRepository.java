package com.yangpang.domain.coupon;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;

public interface CouponRepository extends JpaRepository<Coupon, Long> {

    List<Coupon> findByActiveTrueAndDownloadableTrueOrderByIdDesc();

    List<Coupon> findByActiveTrueAndAutoIssue(String autoIssue);

    List<Coupon> findAllByOrderByIdDesc();

    /** 수량 제한 쿠폰을 동시에 받을 때 초과 발급되지 않도록 행 잠금 */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from Coupon c where c.id = :id")
    Optional<Coupon> findForIssue(Long id);
}
