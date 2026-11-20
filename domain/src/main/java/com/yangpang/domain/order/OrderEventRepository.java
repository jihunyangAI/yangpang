package com.yangpang.domain.order;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface OrderEventRepository extends JpaRepository<OrderEvent, Long> {
    List<OrderEvent> findByOrderIdOrderByCreatedAtAscIdAsc(Long orderId);
}
