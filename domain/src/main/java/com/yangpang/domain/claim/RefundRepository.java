package com.yangpang.domain.claim;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface RefundRepository extends JpaRepository<Refund, Long> {
    List<Refund> findByOrderIdOrderByIdAsc(Long orderId);
}
