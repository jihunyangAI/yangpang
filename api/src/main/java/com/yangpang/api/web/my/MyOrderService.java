package com.yangpang.api.web.my;

import com.yangpang.domain.order.MockPayment;
import com.yangpang.domain.order.MockPaymentRepository;
import com.yangpang.domain.order.Order;
import com.yangpang.domain.order.OrderEvent;
import com.yangpang.domain.order.OrderEventRepository;
import com.yangpang.domain.order.OrderRepository;
import com.yangpang.domain.order.OrderStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/** 마이양팡 주문 조회 (기획서 MY-01, MY-02). 화면에서 쓸 주문 상품까지 트랜잭션 안에서 불러 둔다 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class MyOrderService {

    /** 주문목록 기간 필터 */
    public enum Period {
        M3("3개월", 90), M6("6개월", 180), Y1("1년", 365), ALL("전체", 365 * 20);

        public final String label;
        public final int days;

        Period(String label, int days) {
            this.label = label;
            this.days = days;
        }
    }

    /** 주문목록 상태 필터 */
    public enum StatusFilter {
        ALL("전체", EnumSet.allOf(OrderStatus.class)),
        ONGOING("진행중", EnumSet.of(OrderStatus.PENDING_PAYMENT, OrderStatus.PAID, OrderStatus.PREPARING,
                OrderStatus.SHIPPED, OrderStatus.IN_TRANSIT)),
        DONE("배송완료", EnumSet.of(OrderStatus.DELIVERED, OrderStatus.CONFIRMED)),
        CANCELED("취소·반품", EnumSet.of(OrderStatus.CANCELED, OrderStatus.PAYMENT_FAILED, OrderStatus.RETURNED,
                OrderStatus.RETURN_REQUESTED, OrderStatus.RETURN_PICKING_UP, OrderStatus.RETURN_PICKED_UP,
                OrderStatus.EXCHANGE_REQUESTED, OrderStatus.EXCHANGE_PICKING_UP));

        public final String label;
        public final Set<OrderStatus> statuses;

        StatusFilter(String label, Set<OrderStatus> statuses) {
            this.label = label;
            this.statuses = statuses;
        }
    }

    /** @param gift 선물 주문이면 선물 정보 (아니면 null) */
    public record OrderDetail(Order order, MockPayment payment, List<OrderEvent> events, com.yangpang.domain.order.Gift gift) {
    }

    private final OrderRepository orderRepository;
    private final MockPaymentRepository paymentRepository;
    private final OrderEventRepository eventRepository;
    private final com.yangpang.domain.order.GiftRepository giftRepository;
    private final Clock clock;

    public Page<Order> orders(Long memberId, Period period, StatusFilter filter, int page, int size) {
        Instant from = clock.instant().minus(Duration.ofDays(period.days));
        Page<Order> result = orderRepository.findMyOrders(memberId, from, filter.statuses, PageRequest.of(page, size));
        result.forEach(o -> o.getItems().size()); // 주문 상품 미리 불러오기 (batch fetch)
        return result;
    }

    public OrderDetail detail(Long memberId, String orderNo) {
        Order order = orderRepository.findWithItems(orderNo, memberId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        return new OrderDetail(order,
                paymentRepository.findFirstByOrderIdOrderByIdDesc(order.getId()).orElse(null),
                eventRepository.findByOrderIdOrderByCreatedAtAscIdAsc(order.getId()),
                giftRepository.findByOrderId(order.getId()).orElse(null));
    }

    /** 마이양팡 홈: 상태별 주문 수 (최근 3개월) */
    public long count(Long memberId, StatusFilter filter) {
        Instant from = clock.instant().minus(Duration.ofDays(Period.M3.days));
        return orderRepository.findMyOrders(memberId, from, filter.statuses, PageRequest.of(0, 1)).getTotalElements();
    }
}
