package com.yangpang.api.web.admin;

import com.yangpang.api.security.MemberPrincipal;
import com.yangpang.domain.policy.PolicyService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 운영자: 운영 기준값 (배송비·반품 기간·수수료율 등). 바꾸면 이유와 함께 이력이 남는다 */
@Controller
@RequestMapping("/admin/policies")
@RequiredArgsConstructor
public class AdminPolicyController {

    private final PolicyService policyService;

    @GetMapping
    public String list(Model model) {
        Map<String, List<PolicyService.Setting>> groups = new LinkedHashMap<>();
        policyService.settings().forEach(s -> groups.computeIfAbsent(s.category(), k -> new java.util.ArrayList<>()).add(s));
        model.addAttribute("groups", groups);
        model.addAttribute("history", policyService.history(30));
        return "admin/policies";
    }

    @PostMapping("/{key}")
    public String update(@AuthenticationPrincipal MemberPrincipal me, @PathVariable String key, @RequestParam long value,
                         @RequestParam(required = false) String reason, RedirectAttributes redirect) {
        boolean changed = policyService.update(key, value, reason, me.getUsername());
        redirect.addFlashAttribute("flashMessage", changed ? "기준값을 바꿨어요. 새로 계산하는 것부터 적용돼요" : "값이 그대로예요");
        return "redirect:/admin/policies";
    }
}
