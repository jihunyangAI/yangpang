package com.yangpang.domain.seller;

import com.yangpang.domain.common.BusinessException;
import com.yangpang.domain.order.OrderItem;
import com.yangpang.domain.order.OrderItemRepository;
import com.yangpang.domain.order.OrderProgressService;
import com.yangpang.domain.order.OrderStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.regex.Pattern;

/**
 * 판매자 주문 처리 (기획서 5.15): 발주 확인(결제완료 → 상품준비중), 송장 등록(→ 출고완료).
 * 판매자가 손대지 않으면 시뮬레이터가 정해진 시각에 대신 처리한다(가상 판매자).
 */
@Service
@RequiredArgsConstructor
@Transactional
public class SellerOrderService {

    public static final List<String> CARRIERS = List.of("가상택배", "연구로지스", "샘플익스프레스");
    private static final Pattern TRACKING = Pattern.compile("^[0-9]{10,14}$");

    private final OrderItemRepository itemRepository;
    private final OrderProgressService progressService;

    /** 발주 확인. 처리한 수 */
    public int confirm(Long sellerId, List<Long> orderItemIds) {
        int n = 0;
        for (Long id : orderItemIds) {
            OrderItem item = mine(sellerId, id);
            if (item.getStatus() == OrderStatus.PAID
                    && progressService.advance(id, OrderStatus.PAID, "SELLER", "판매자 발주 확인")) n++;
        }
        if (n == 0) throw new BusinessException("발주 확인할 수 있는 주문이 없어요 (결제완료 상태만 가능)");
        return n;
    }

    /** 송장 등록 → 출고완료 */
    public void ship(Long sellerId, Long orderItemId, String carrier, String trackingNo) {
        OrderItem item = mine(sellerId, orderItemId);
        if (item.getStatus() != OrderStatus.PREPARING) throw new BusinessException("상품준비중인 주문만 송장을 등록할 수 있어요");
        if (!CARRIERS.contains(carrier)) throw new BusinessException("택배사를 골라 주세요");
        String no = trackingNo == null ? "" : trackingNo.replaceAll("[\\s-]", "");
        if (!TRACKING.matcher(no).matches()) throw new BusinessException("송장번호는 숫자 10~14자리예요");
        item.registerTracking(carrier, no);
        progressService.advance(orderItemId, OrderStatus.PREPARING, "SELLER", carrier + " " + no);
    }

    private OrderItem mine(Long sellerId, Long orderItemId) {
        OrderItem item = itemRepository.findById(orderItemId).orElseThrow(() -> new BusinessException("주문 상품이 없어요"));
        if (!item.getSellerId().equals(sellerId)) throw new BusinessException("내 주문이 아니에요");
        return item;
    }
}
