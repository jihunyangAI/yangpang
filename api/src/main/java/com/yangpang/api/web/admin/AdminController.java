package com.yangpang.api.web.admin;

import com.yangpang.domain.catalog.ProductRepository;
import com.yangpang.domain.member.MemberRepository;
import com.yangpang.domain.order.OrderRepository;
import com.yangpang.domain.order.OrderStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.EnumSet;

/** 관리자 대시보드 (/admin/** 는 ADMIN 역할만 접근) */
@Controller
@RequiredArgsConstructor
public class AdminController {

    private final MemberRepository memberRepository;
    private final ProductRepository productRepository;
    private final OrderRepository orderRepository;
    private final Clock clock;

    @GetMapping("/admin")
    public String dashboard(Model model) {
        Instant todayStart = LocalDate.now(clock.withZone(ZoneId.of("Asia/Seoul")))
                .atStartOfDay(ZoneId.of("Asia/Seoul")).toInstant();
        model.addAttribute("members", memberRepository.count());
        model.addAttribute("products", productRepository.count());
        model.addAttribute("ordersToday", orderRepository.countByOrderedAtGreaterThanEqual(todayStart));
        model.addAttribute("salesToday", orderRepository.sumPaidSince(todayStart,
                EnumSet.of(OrderStatus.PENDING_PAYMENT, OrderStatus.CANCELED, OrderStatus.PAYMENT_FAILED)));
        model.addAttribute("pendingPayments", orderRepository.countByStatus(OrderStatus.PENDING_PAYMENT));
        model.addAttribute("toShip", orderRepository.countByStatus(OrderStatus.PAID));
        return "admin/dashboard";
    }
}
