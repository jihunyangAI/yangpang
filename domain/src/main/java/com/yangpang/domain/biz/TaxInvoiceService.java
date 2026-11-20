package com.yangpang.domain.biz;

import com.yangpang.domain.common.BusinessException;
import com.yangpang.domain.member.Member;
import com.yangpang.domain.member.MemberRepository;
import com.yangpang.domain.notification.Notification;
import com.yangpang.domain.notification.NotificationService;
import com.yangpang.domain.order.Order;
import com.yangpang.domain.order.OrderRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;

/**
 * 비즈 회원 세금계산서 요청 (M4 ⑤). 결제한 주문마다 한 번 요청하고, 시뮬레이터가 1시간 뒤 모의 발행한다.
 * 사업자등록번호는 형식(000-00-00000)만 검사한다 (가짜 번호).
 */
@Service
@RequiredArgsConstructor
@Transactional
public class TaxInvoiceService {

    /** 요청 후 모의 발행까지 걸리는 시간 (시뮬레이션 시각) */
    static final Duration ISSUE_DELAY = Duration.ofHours(1);

    public record BusinessInfo(String businessNo, String companyName, String ceoName, String email) {
    }

    private final TaxInvoiceRepository repository;
    private final OrderRepository orderRepository;
    private final MemberRepository memberRepository;
    private final NotificationService notificationService;
    private final Clock clock;

    /** 요청 금액: 실제 낸 금액(카드 등 + 캐시) - 환불액 */
    public static long invoiceAmount(Order o) {
        return Math.max(0, o.getPaidAmount() + o.getCashUsed() - o.getRefundedAmount());
    }

    public static boolean requestable(Order o) {
        return o.getStatus().step() >= 1 && invoiceAmount(o) > 0;
    }

    public TaxInvoice request(Long memberId, String orderNo, BusinessInfo raw) {
        Member m = memberRepository.findById(memberId).orElseThrow();
        if (!m.isBiz()) throw new BusinessException("세금계산서는 비즈(사업자) 회원만 요청할 수 있어요");
        Order o = orderRepository.findByOrderNoAndMemberId(orderNo, memberId).orElseThrow(() -> new BusinessException("주문을 찾을 수 없어요"));
        if (!requestable(o)) throw new BusinessException("결제가 끝난 주문만 세금계산서를 요청할 수 있어요");
        if (repository.findByOrderId(o.getId()).isPresent()) throw new BusinessException("이미 세금계산서를 요청한 주문이에요");
        BusinessInfo info = validate(raw);
        TaxInvoice t = repository.save(TaxInvoice.request(o.getId(), memberId, info, invoiceAmount(o), clock.instant()));
        return t;
    }

    static BusinessInfo validate(BusinessInfo r) {
        String no = r.businessNo() == null ? "" : r.businessNo().trim();
        if (no.matches("^\\d{10}$")) no = no.substring(0, 3) + "-" + no.substring(3, 5) + "-" + no.substring(5);
        if (!no.matches("^\\d{3}-\\d{2}-\\d{5}$")) throw new BusinessException("사업자등록번호 형식은 000-00-00000 이에요 (가짜 번호)");
        String company = trim(r.companyName(), 50, "상호를 입력해 주세요");
        String ceo = trim(r.ceoName(), 30, "대표자 이름을 입력해 주세요");
        String email = trim(r.email(), 100, "세금계산서 받을 이메일을 입력해 주세요");
        if (!email.matches("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")) throw new BusinessException("이메일 형식을 확인해 주세요");
        return new BusinessInfo(no, company, ceo, email);
    }

    private static String trim(String s, int max, String message) {
        if (s == null || s.isBlank()) throw new BusinessException(message);
        String t = s.trim();
        if (t.length() > max) throw new BusinessException(message + " (" + max + "자까지)");
        return t;
    }

    @Transactional(readOnly = true)
    public Optional<TaxInvoice> forOrder(Long orderId) {
        return repository.findByOrderId(orderId);
    }

    @Transactional(readOnly = true)
    public List<TaxInvoice> mine(Long memberId) {
        return repository.findByMemberIdOrderByRequestedAtDesc(memberId);
    }

    /** 다음 요청 때 채워 줄 사업자 정보 (마지막 요청) */
    @Transactional(readOnly = true)
    public Optional<BusinessInfo> lastInfo(Long memberId) {
        return repository.findFirstByMemberIdOrderByRequestedAtDesc(memberId)
                .map(t -> new BusinessInfo(t.getBusinessNo(), t.getCompanyName(), t.getCeoName(), t.getEmail()));
    }

    /** 시뮬레이터: 요청 1시간 뒤 모의 발행. @return 발행한 수 */
    public int issueDue() {
        Instant now = clock.instant();
        int n = 0;
        for (TaxInvoice t : repository.findByStatusAndRequestedAtBefore(TaxInvoice.Status.REQUESTED, now.minus(ISSUE_DELAY))) {
            String no = t.getRequestedAt().plus(ISSUE_DELAY).atZone(ZoneId.of("Asia/Seoul")).format(DateTimeFormatter.BASIC_ISO_DATE)
                    + "-YP-" + String.format("%07d", t.getId());
            t.issue(no, t.getRequestedAt().plus(ISSUE_DELAY));
            orderRepository.findById(t.getOrderId()).ifPresent(o ->
                    notificationService.notify(t.getMemberId(), Notification.Type.ORDER, "세금계산서가 발행되었어요 (모의)",
                            o.getOrderNo() + " · " + String.format("%,d", t.getAmount()) + "원 · " + t.getEmail() + "로 보냈다고 가정해요",
                            "/my/biz", null));
            n++;
        }
        return n;
    }
}
