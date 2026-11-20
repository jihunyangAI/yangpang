package com.yangpang.api.web.admin;

import com.yangpang.domain.review.InquiryService;
import com.yangpang.domain.review.ProductInquiry;
import com.yangpang.domain.review.ProductInquiryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/** 상품문의 답변 (운영자). 판매자별 답변은 셀러센터(M3) */
@Controller
@RequestMapping("/admin/inquiries")
@RequiredArgsConstructor
public class AdminInquiryController {

    private final ProductInquiryRepository inquiryRepository;
    private final InquiryService inquiryService;

    @GetMapping
    public String list(@RequestParam(defaultValue = "false") boolean all, Model model) {
        Page<ProductInquiry> page = all
                ? inquiryRepository.findAll(PageRequest.of(0, 50, Sort.by(Sort.Direction.DESC, "createdAt")))
                : inquiryRepository.findByAnswerIsNullOrderByCreatedAtAsc(PageRequest.of(0, 50));
        model.addAttribute("inquiries", page);
        model.addAttribute("all", all);
        return "admin/inquiries";
    }

    @PostMapping("/{id}/answer")
    public String answer(@PathVariable Long id, @RequestParam String answer, RedirectAttributes redirect) {
        inquiryService.answer(id, answer);
        redirect.addFlashAttribute("flashMessage", "답변을 등록했어요");
        return "redirect:/admin/inquiries";
    }
}
