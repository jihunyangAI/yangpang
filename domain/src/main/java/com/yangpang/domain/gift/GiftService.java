package com.yangpang.domain.gift;

import com.yangpang.domain.claim.ClaimService;
import com.yangpang.domain.common.BusinessException;
import com.yangpang.domain.common.EntranceType;
import com.yangpang.domain.common.Region;
import com.yangpang.domain.delivery.ArrivalEstimator;
import com.yangpang.domain.member.RegionResolver;
import com.yangpang.domain.notification.Notification;
import com.yangpang.domain.notification.NotificationService;
import com.yangpang.domain.order.Gift;
import com.yangpang.domain.order.GiftRepository;
import com.yangpang.domain.order.Order;
import com.yangpang.domain.order.OrderEvent;
import com.yangpang.domain.order.OrderEventRepository;
import com.yangpang.domain.order.OrderItem;
import com.yangpang.domain.order.OrderRepository;
import com.yangpang.domain.order.OrderStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;

/**
 * 선물하기 (M4 ④): 받는 분의 배송지 입력, 기한(5일) 초과 자동 취소·환불.
 * 문자 발송은 하지 않는다 (보내는 분이 주문 상세에서 링크를 복사해 전하는 가짜 링크).
 */
@Service
@RequiredArgsConstructor
public class GiftService {

    /** 받는 분이 입력하는 배송지 */
    public record Destination(String zipcode, String address1, String address2, EntranceType entranceType,
                              String entranceNote, String requestNote) {
    }

    /** 선물 링크 화면 */
    public record View(Gift gift, Order order) {
        /** 받는 분이 지금 배송지를 입력할 수 있는지 */
        public boolean acceptable() {
            return gift.waiting() && order.isGiftWaiting() && order.getStatus() == OrderStatus.PAID;
        }

        /** 결제 전(가상계좌 입금 대기)이라 아직 받을 수 없음 */
        public boolean unpaid() {
            return gift.waiting() && order.getStatus() == OrderStatus.PENDING_PAYMENT;
        }

        public boolean canceled() {
            return gift.getStatus() == Gift.Status.EXPIRED || gift.getStatus() == Gift.Status.CANCELED
                    || order.getStatus() == OrderStatus.CANCELED || order.getStatus() == OrderStatus.PAYMENT_FAILED;
        }
    }

    private final GiftRepository giftRepository;
    private final OrderRepository orderRepository;
    private final OrderEventRepository eventRepository;
    private final ClaimService claimService;
    private final ArrivalEstimator arrivalEstimator;
    private final NotificationService notificationService;
    private final Clock clock;

    @Transactional(readOnly = true)
    public Optional<View> view(String token) {
        if (token == null || token.length() > 40) return Optional.empty();
        return giftRepository.findByToken(token).flatMap(g -> orderRepository.findWithItemsById(g.getOrderId()).map(o -> new View(g, o)));
    }

    @Transactional(readOnly = true)
    public Optional<Gift> forOrder(Long orderId) {
        return giftRepository.findByOrderId(orderId);
    }

    /** 받는 분이 배송지를 입력 → 그때부터 발주 확인·출고 진행, 도착 예정일도 받는 분 지역으로 다시 계산 */
    @Transactional
    public void accept(String token, Destination d) {
        Gift gift = giftRepository.findByToken(token).orElseThrow(() -> new BusinessException("선물 링크를 찾을 수 없어요"));
        Order order = orderRepository.findWithItemsById(gift.getOrderId()).orElseThrow();
        View v = new View(gift, order);
        if (v.canceled()) throw new BusinessException("취소된 선물이에요");
        if (v.unpaid()) throw new BusinessException("보내는 분의 결제가 끝나면 배송지를 입력할 수 있어요");
        if (!v.acceptable()) throw new BusinessException("이미 배송지를 입력한 선물이에요");
        String zipcode = d.zipcode() == null ? "" : d.zipcode().trim();
        if (!zipcode.matches("^\\d{5}$")) throw new BusinessException("우편번호는 숫자 5자리예요");
        String address1 = d.address1() == null ? "" : d.address1().trim();
        if (address1.isEmpty() || address1.length() > 200) throw new BusinessException("주소를 입력해 주세요");
        String address2 = blankToNull(d.address2());
        if (address2 != null && address2.length() > 200) throw new BusinessException("상세 주소는 200자까지 쓸 수 있어요");
        EntranceType entrance = d.entranceType() == null ? EntranceType.FREE : d.entranceType();
        String entranceNote = entrance == EntranceType.FREE ? null : limit(blankToNull(d.entranceNote()), 100);
        Region region = RegionResolver.resolve(address1);

        Instant now = clock.instant();
        order.applyGiftAddress(zipcode, address1, address2, region, entrance, entranceNote, limit(blankToNull(d.requestNote()), 100));
        for (OrderItem i : order.getItems()) {
            if (i.getStatus() != OrderStatus.PAID) continue;
            i.repromise(arrivalEstimator.arrivalDate(i.getDeliveryType(), region));
            i.restartStage(now);
        }
        gift.accept(now);
        eventRepository.save(OrderEvent.of(order.getId(), order.getStatus(), order.getStatus(), "RECIPIENT",
                "선물 받는 분이 배송지 입력", now));
        notificationService.notify(order.getMemberId(), Notification.Type.DELIVERY, "선물 받는 분이 배송지를 입력했어요",
                gift.getRecipientName() + "님께 " + order.title() + " 배송을 준비할게요", "/my/orders/" + order.getOrderNo(),
                order.getItems().isEmpty() ? null : order.getItems().getFirst().getImageUrl());
    }

    /**
     * 시뮬레이터: 기한(결제 후 5일)이 지나도록 배송지를 받지 못한 선물은 자동 취소·전액 환불(모의).
     * 결제 실패·취소로 이미 끝난 주문의 선물도 정리한다.
     * @return 자동 취소한 선물 수
     */
    @Transactional
    public int expireDue() {
        for (Gift g : giftRepository.findWaitingWithEndedOrder()) g.end(Gift.Status.CANCELED);
        int n = 0;
        for (Gift g : giftRepository.findWaitingExpiredBefore(clock.instant())) {
            Order order = orderRepository.findById(g.getOrderId()).orElseThrow();
            if (order.getStatus() == OrderStatus.PENDING_PAYMENT) continue; // 입금 대기는 입금 기한 처리에 맡긴다
            if (!order.isGiftWaiting()) { // 배송지는 받았는데 상태가 남은 경우
                g.accept(clock.instant());
                continue;
            }
            if (claimService.cancelBySystem(order.getId(), "선물 받는 분이 5일 안에 배송지를 입력하지 않아 자동 취소")) n++;
            g.end(Gift.Status.EXPIRED);
        }
        return n;
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private static String limit(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max);
    }
}
