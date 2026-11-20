package com.yangpang.domain.order;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

/** 주문 상태 변경 이력 (누가, 언제, 무엇을) */
@Getter
@Entity
@Table(name = "order_event")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class OrderEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "order_id", nullable = false)
    private Long orderId;

    @Column(name = "order_item_id")
    private Long orderItemId;

    @Enumerated(EnumType.STRING)
    @Column(name = "from_status", length = 30)
    private OrderStatus fromStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "to_status", nullable = false, length = 30)
    private OrderStatus toStatus;

    @Column(nullable = false, length = 30)
    private String actor;

    private String note;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    public static OrderEvent ofItem(OrderItem item, OrderStatus from, OrderStatus to, String actor, String note, Instant now) {
        OrderEvent e = of(item.getOrder().getId(), from, to, actor, note, now);
        e.orderItemId = item.getId();
        return e;
    }

    public static OrderEvent of(Long orderId, OrderStatus from, OrderStatus to, String actor, String note, Instant now) {
        OrderEvent e = new OrderEvent();
        e.orderId = orderId;
        e.fromStatus = from;
        e.toStatus = to;
        e.actor = actor;
        e.note = note;
        e.createdAt = now;
        return e;
    }
}
