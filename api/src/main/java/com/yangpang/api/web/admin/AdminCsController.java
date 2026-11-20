package com.yangpang.api.web.admin;

import com.yangpang.domain.cs.CsService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/** 운영자: 1:1 문의 답변 · FAQ 관리 */
@Controller
@RequestMapping("/admin")
@RequiredArgsConstructor
public class AdminCsController {

    private final CsService csService;

    @GetMapping("/cs")
    public String inquiries(@RequestParam(defaultValue = "WAITING") String status, Model model) {
        model.addAttribute("status", "ANSWERED".equals(status) ? "ANSWERED" : "WAITING");
        model.addAttribute("inquiries", csService.forAdmin(status));
        return "admin/cs";
    }

    @PostMapping("/cs/{id}/answer")
    public String answer(@PathVariable Long id, @RequestParam String answer, RedirectAttributes redirect) {
        csService.answer(id, answer);
        redirect.addFlashAttribute("flashMessage", "답변을 등록했어요. 회원에게 알림이 가요");
        return "redirect:/admin/cs";
    }

    @GetMapping("/faq")
    public String faqs(Model model) {
        model.addAttribute("faqs", csService.faqs(null, null).stream()
                .sorted(java.util.Comparator.comparing((CsService.Faq f) -> f.category().ordinal()).thenComparingInt(CsService.Faq::sortOrder))
                .toList());
        model.addAttribute("categories", CsService.Category.values());
        return "admin/faq";
    }

    @PostMapping("/faq")
    public String save(@RequestParam(required = false) Long id, @RequestParam CsService.Category category, @RequestParam String question,
                       @RequestParam String answer, @RequestParam(defaultValue = "0") int sortOrder, RedirectAttributes redirect) {
        csService.saveFaq(id, category, question, answer, sortOrder);
        redirect.addFlashAttribute("flashMessage", id == null ? "FAQ를 추가했어요" : "FAQ를 고쳤어요");
        return "redirect:/admin/faq";
    }

    @PostMapping("/faq/{id}/delete")
    public String delete(@PathVariable Long id, RedirectAttributes redirect) {
        csService.deleteFaq(id);
        redirect.addFlashAttribute("flashMessage", "FAQ를 지웠어요");
        return "redirect:/admin/faq";
    }
}
