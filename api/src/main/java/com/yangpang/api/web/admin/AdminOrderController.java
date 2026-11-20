package com.yangpang.api.web.admin;

import com.yangpang.api.web.my.MyOrderService;
import com.yangpang.domain.order.Order;
import com.yangpang.domain.order.OrderEventRepository;
import com.yangpang.domain.order.OrderRepository;
import com.yangpang.domain.order.MockPaymentRepository;
import com.yangpang.domain.order.OrderService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/** 관리자 주문 관리: 목록·검색, 상세, 무통장 입금 확인(모의). 강제 상태 변경·클레임 처리는 M2 */
@Controller
@RequestMapping("/admin/orders")
@RequiredArgsConstructor
public class AdminOrderController {

    private final OrderRepository orderRepository;
    private final MockPaymentRepository paymentRepository;
    private final OrderEventRepository eventRepository;
    private final OrderService orderService;

    @GetMapping
    @Transactional(readOnly = true)
    public String list(@RequestParam(defaultValue = "ALL") MyOrderService.StatusFilter status,
                       @RequestParam(required = false) String q, @RequestParam(defaultValue = "1") int page, Model model) {
        Page<Order> orders = orderRepository.adminSearch(status.statuses, q == null ? "" : q.trim(),
                PageRequest.of(Math.max(0, page - 1), 30));
        orders.forEach(o -> o.getItems().size());
        model.addAttribute("orders", orders);
        model.addAttribute("pageNumbers", com.yangpang.api.web.Paging.pageNumbers(orders));
        model.addAttribute("status", status);
        model.addAttribute("statusFilters", MyOrderService.StatusFilter.values());
        model.addAttribute("q", q);
        return "admin/orders";
    }

    @GetMapping("/{orderNo}")
    @Transactional(readOnly = true)
    public String detail(@PathVariable String orderNo, Model model) {
        Order order = orderRepository.findByOrderNo(orderNo).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        order.getItems().size();
        model.addAttribute("o", order);
        model.addAttribute("payment", paymentRepository.findFirstByOrderIdOrderByIdDesc(order.getId()).orElse(null));
        model.addAttribute("events", eventRepository.findByOrderIdOrderByCreatedAtAscIdAsc(order.getId()));
        return "admin/order-detail";
    }

    @PostMapping("/{orderNo}/confirm-deposit")
    public String confirmDeposit(@PathVariable String orderNo, RedirectAttributes redirect) {
        orderService.confirmDeposit(orderNo, "ADMIN");
        redirect.addFlashAttribute("flashMessage", "입금 확인 처리했어요 (모의)");
        return "redirect:/admin/orders/" + orderNo;
    }
}
