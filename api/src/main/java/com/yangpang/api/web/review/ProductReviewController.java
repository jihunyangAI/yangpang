package com.yangpang.api.web.review;

import com.yangpang.api.security.MemberPrincipal;
import com.yangpang.api.web.Htmx;
import com.yangpang.domain.member.Member;
import com.yangpang.domain.member.MemberRepository;
import com.yangpang.domain.review.InquiryService;
import com.yangpang.domain.review.ProductInquiry;
import com.yangpang.domain.review.ProductInquiryRepository;
import com.yangpang.domain.review.ReviewService;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 상품 상세의 리뷰·문의 영역 (htmx 조각).
 * 상세 화면이 열린 뒤 따로 불러오고, 필터·더보기·도움돼요·문의 작성도 이 영역만 갱신한다.
 */
@Controller
@RequiredArgsConstructor
public class ProductReviewController {

    private final ReviewViewService viewService;
    private final ReviewService reviewService;
    private final InquiryService inquiryService;
    private final ProductInquiryRepository inquiryRepository;
    private final MemberRepository memberRepository;

    /** 리뷰 영역 전체 (요약 + 필터 + 첫 페이지) 또는 "더보기" 다음 페이지 */
    @GetMapping("/products/{id}/reviews")
    public String reviews(@PathVariable Long id,
                          @RequestParam(defaultValue = "BEST") ReviewViewService.SortOption sort,
                          @RequestParam(defaultValue = "0") int rating,
                          @RequestParam(defaultValue = "false") boolean photo,
                          @RequestParam(required = false) String q,
                          @RequestParam(defaultValue = "1") int page,
                          @AuthenticationPrincipal MemberPrincipal me, Model model) {
        if (rating < 0 || rating > 5) rating = 0;
        model.addAttribute("productId", id);
        model.addAttribute("sort", sort);
        model.addAttribute("sorts", ReviewViewService.SortOption.values());
        model.addAttribute("rating", rating);
        model.addAttribute("photo", photo);
        model.addAttribute("q", q == null ? "" : q);
        model.addAttribute("list", viewService.list(id, sort, rating, photo, q, page, me == null ? null : me.id()));
        if (page > 1) return "review/section :: items";
        model.addAttribute("summary", viewService.summary(id));
        return "review/section :: section";
    }

    @PostMapping("/reviews/{id}/helpful")
    public String helpful(@AuthenticationPrincipal MemberPrincipal me, @PathVariable Long id, Model model) {
        model.addAttribute("reviewId", id);
        model.addAttribute("count", reviewService.helpful(me.id(), id));
        model.addAttribute("voted", true);
        return "review/section :: helpful";
    }

    @PostMapping("/reviews/{id}/report")
    public String report(@AuthenticationPrincipal MemberPrincipal me, @PathVariable Long id,
                         HttpServletResponse response, Model model) {
        reviewService.report(me.id(), id);
        Htmx.retarget(response, "#toast");
        model.addAttribute("message", "신고가 접수되었어요");
        return "fragments/toast :: toast";
    }

    // ---------- 상품문의 ----------
    @GetMapping("/products/{id}/inquiries")
    public String inquiries(@PathVariable Long id, @RequestParam(defaultValue = "1") int page,
                            @AuthenticationPrincipal MemberPrincipal me, Model model) {
        Page<ProductInquiry> result = inquiryRepository.findByProductIdOrderByCreatedAtDesc(id, PageRequest.of(Math.max(0, page - 1), 5));
        Map<Long, String> names = memberRepository.findAllById(result.map(ProductInquiry::getMemberId).toSet()).stream()
                .collect(Collectors.toMap(Member::getId, m -> ReviewViewService.mask(m)));
        model.addAttribute("productId", id);
        model.addAttribute("inquiries", result);
        model.addAttribute("names", names);
        model.addAttribute("viewerId", me == null ? null : me.id());
        model.addAttribute("types", ProductInquiry.Type.values());
        return "review/inquiries :: section";
    }

    @PostMapping("/products/{id}/inquiries")
    public String ask(@AuthenticationPrincipal MemberPrincipal me, @PathVariable Long id,
                      @RequestParam(defaultValue = "PRODUCT") ProductInquiry.Type type, @RequestParam String content,
                      @RequestParam(defaultValue = "false") boolean secret, Model model) {
        inquiryService.ask(me.id(), id, type, content, secret);
        model.addAttribute("flashMessage", "문의를 남겼어요. 답변이 달리면 여기에서 볼 수 있어요");
        return inquiries(id, 1, me, model);
    }

    @PostMapping("/inquiries/{inquiryId}/delete")
    public String deleteInquiry(@AuthenticationPrincipal MemberPrincipal me, @PathVariable Long inquiryId, Model model) {
        ProductInquiry q = inquiryRepository.findById(inquiryId).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        inquiryService.delete(me.id(), inquiryId);
        return inquiries(q.getProductId(), 1, me, model);
    }
}
