package com.yangpang.api.web.plus;

import com.yangpang.api.security.MemberPrincipal;
import com.yangpang.domain.membership.MembershipBillingRepository;
import com.yangpang.domain.membership.MembershipService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * 양팡플러스 소개·가입 / 마이양팡 멤버십 관리 (기획서 5.8, MY-08).
 * 해지는 2단계: 관리 화면의 "해지하기" → 확인 화면의 "해지 완료하기". 해지를 막는 설득 화면을 여러 번 넣지 않는다.
 */
@Controller
@RequiredArgsConstructor
public class PlusController {

    private final MembershipService membershipService;
    private final MembershipBillingRepository billingRepository;

    @GetMapping("/plus")
    public String landing(@AuthenticationPrincipal MemberPrincipal me, Model model) {
        model.addAttribute("fee", membershipService.fee());
        model.addAttribute("membership", me == null ? null : membershipService.find(me.id()).orElse(null));
        return "plus/landing";
    }

    @PostMapping("/plus/join")
    public String join(@AuthenticationPrincipal MemberPrincipal me, RedirectAttributes redirect) {
        boolean firstTime = membershipService.find(me.id()).isEmpty();
        membershipService.join(me.id());
        redirect.addFlashAttribute("flashMessage", firstTime
                ? "양팡플러스에 가입했어요! 첫 달은 무료예요" : "양팡플러스에 다시 가입했어요 (월 회비 모의 청구)");
        return "redirect:/my/plus";
    }

    @GetMapping("/my/plus")
    public String manage(@AuthenticationPrincipal MemberPrincipal me, Model model) {
        var membership = membershipService.find(me.id()).orElse(null);
        model.addAttribute("membership", membership);
        if (membership != null) {
            model.addAttribute("savings", membershipService.savings(membership));
            model.addAttribute("billings", billingRepository.findByMembershipIdOrderByBilledAtDesc(membership.getId()));
        }
        model.addAttribute("fee", membershipService.fee());
        return "plus/manage";
    }

    /** 해지 1단계: 확인 화면 (잃게 되는 혜택과 종료일만 한 번 안내) */
    @GetMapping("/my/plus/cancel")
    public String cancelConfirm(@AuthenticationPrincipal MemberPrincipal me, Model model) {
        model.addAttribute("membership", membershipService.find(me.id()).orElse(null));
        return "plus/cancel";
    }

    /** 해지 2단계: 완료 */
    @PostMapping("/my/plus/cancel")
    public String cancel(@AuthenticationPrincipal MemberPrincipal me, RedirectAttributes redirect) {
        membershipService.cancel(me.id());
        redirect.addFlashAttribute("flashMessage", "해지 신청이 완료되었어요. 다음 결제일 전까지는 혜택이 유지돼요");
        return "redirect:/my/plus";
    }

    @PostMapping("/my/plus/undo-cancel")
    public String undoCancel(@AuthenticationPrincipal MemberPrincipal me, RedirectAttributes redirect) {
        membershipService.undoCancel(me.id());
        redirect.addFlashAttribute("flashMessage", "해지 신청을 취소했어요");
        return "redirect:/my/plus";
    }
}
