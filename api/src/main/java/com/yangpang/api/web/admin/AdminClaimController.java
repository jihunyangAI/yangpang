package com.yangpang.api.web.admin;

import com.yangpang.api.web.Paging;
import com.yangpang.domain.claim.Claim;
import com.yangpang.domain.claim.ClaimEnums;
import com.yangpang.domain.claim.ClaimRepository;
import com.yangpang.domain.claim.ClaimService;
import com.yangpang.domain.order.OrderRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Controller;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.time.Clock;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;

/** 관리자 클레임 관리: 목록, 다음 단계로 강제 진행 (판매자 승인·거부는 셀러센터 M3) */
@Controller
@RequestMapping("/admin/claims")
@RequiredArgsConstructor
public class AdminClaimController {

    private final ClaimRepository claimRepository;
    private final ClaimService claimService;
    private final OrderRepository orderRepository;
    private final Clock clock;

    @GetMapping
    @Transactional(readOnly = true)
    public String list(@RequestParam(defaultValue = "false") boolean all, @RequestParam(defaultValue = "1") int page, Model model) {
        EnumSet<ClaimEnums.Status> statuses = all ? EnumSet.allOf(ClaimEnums.Status.class)
                : EnumSet.of(ClaimEnums.Status.REQUESTED, ClaimEnums.Status.PICKING_UP, ClaimEnums.Status.PICKED_UP);
        Page<Claim> claims = claimRepository.findByStatusInOrderByRequestedAtDesc(statuses, PageRequest.of(Math.max(0, page - 1), 30));
        Map<Long, String> orderNos = new HashMap<>();
        claims.forEach(c -> {
            c.getItems().forEach(ci -> ci.getOrderItem().getProductName());
            orderRepository.findById(c.getOrderId()).ifPresent(o -> orderNos.put(c.getId(), o.getOrderNo()));
        });
        model.addAttribute("claims", claims);
        model.addAttribute("orderNos", orderNos);
        model.addAttribute("all", all);
        model.addAttribute("pageNumbers", Paging.pageNumbers(claims));
        return "admin/claims";
    }

    @PostMapping("/{claimNo}/advance")
    public String advance(@PathVariable String claimNo, RedirectAttributes redirect) {
        Claim c = claimRepository.findByClaimNo(claimNo).orElseThrow();
        boolean ok = claimService.advance(c.getId(), c.getStatus(), clock.instant());
        redirect.addFlashAttribute("flashMessage", ok ? claimNo + " 다음 단계로 진행했어요" : "진행할 수 없는 상태예요");
        return "redirect:/admin/claims";
    }
}
