package com.yangpang.api.web.my;

import com.yangpang.api.security.MemberPrincipal;
import com.yangpang.api.security.SafeRedirect;
import com.yangpang.api.web.Paging;
import com.yangpang.domain.notification.Notification;
import com.yangpang.domain.notification.NotificationRepository;
import com.yangpang.domain.notification.NotificationService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.List;
import java.util.Set;

/** 마이양팡 알림함 (기획서 5.11) */
@Controller
@RequestMapping("/my/notifications")
@RequiredArgsConstructor
public class NotificationController {

    /** 탭 → 알림 종류 */
    private static Set<Notification.Type> typesOf(String tab) {
        return switch (tab == null ? "" : tab) {
            case "order" -> Set.of(Notification.Type.ORDER, Notification.Type.DELIVERY, Notification.Type.CLAIM);
            case "wish" -> Set.of(Notification.Type.PRICE_DROP, Notification.Type.RESTOCK);
            case "benefit" -> Set.of(Notification.Type.BENEFIT);
            default -> null;
        };
    }

    private final NotificationRepository repository;
    private final NotificationService service;

    @GetMapping
    public String list(@AuthenticationPrincipal MemberPrincipal me, @RequestParam(required = false) String tab,
                       @RequestParam(defaultValue = "1") int page, Model model) {
        var types = typesOf(tab);
        var pageable = PageRequest.of(Math.max(0, page - 1), 30);
        var result = types == null ? repository.findByMemberIdOrderByCreatedAtDescIdDesc(me.id(), pageable)
                : repository.findByMemberIdAndTypeInOrderByCreatedAtDescIdDesc(me.id(), types, pageable);
        boolean[] prefs = service.preferences(me.id());
        model.addAttribute("items", result);
        model.addAttribute("pageNumbers", Paging.pageNumbers(result));
        model.addAttribute("tab", types == null ? "all" : tab);
        model.addAttribute("tabs", List.of(new String[]{"all", "전체"}, new String[]{"order", "주문·배송"},
                new String[]{"wish", "찜 알림"}, new String[]{"benefit", "혜택"}));
        model.addAttribute("notifyDelivery", prefs[0]);
        model.addAttribute("notifyWish", prefs[1]);
        model.addAttribute("emailNotify", prefs[2]);
        model.addAttribute("marketingAgreed", prefs[3]);
        return "my/notifications";
    }

    /** 알림을 누르면 읽음 처리 후 연결된 화면으로 (사이트 안 경로만) */
    @GetMapping("/{id}")
    public String open(@AuthenticationPrincipal MemberPrincipal me, @PathVariable Long id) {
        service.markRead(me.id(), id);
        String link = repository.findById(id).filter(n -> n.getMemberId().equals(me.id())).map(Notification::getLink).orElse(null);
        String safe = link == null ? null : SafeRedirect.sameSitePath(link);
        return "redirect:" + (safe == null ? "/my/notifications" : safe);
    }

    @PostMapping("/read-all")
    public String readAll(@AuthenticationPrincipal MemberPrincipal me, @RequestParam(required = false) String tab,
                          RedirectAttributes redirect) {
        int n = service.markAllRead(me.id());
        redirect.addFlashAttribute("flashMessage", n + "개를 읽음으로 표시했어요");
        return "redirect:/my/notifications" + (tab == null || tab.equals("all") ? "" : "?tab=" + tab);
    }

    @PostMapping("/{id}/delete")
    public String delete(@AuthenticationPrincipal MemberPrincipal me, @PathVariable Long id) {
        service.delete(me.id(), id);
        return "redirect:/my/notifications";
    }

    @PostMapping("/settings")
    public String settings(@AuthenticationPrincipal MemberPrincipal me,
                           @RequestParam(defaultValue = "false") boolean delivery,
                           @RequestParam(defaultValue = "false") boolean wish,
                           @RequestParam(defaultValue = "false") boolean email, RedirectAttributes redirect) {
        service.setPreferences(me.id(), delivery, wish, email);
        redirect.addFlashAttribute("flashMessage", "알림 설정을 저장했어요");
        return "redirect:/my/notifications";
    }
}
