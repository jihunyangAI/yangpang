package com.yangpang.api.web.my;

import com.yangpang.api.security.MemberPrincipal;
import com.yangpang.api.web.layout.CategoryMenu;
import com.yangpang.domain.catalog.ProductRepository;
import com.yangpang.domain.common.BusinessException;
import com.yangpang.domain.order.OrderItem;
import com.yangpang.domain.order.OrderItemRepository;
import com.yangpang.domain.review.Review;
import com.yangpang.domain.review.ReviewRepository;
import com.yangpang.domain.review.ReviewService;
import com.yangpang.domain.review.ReviewSurvey;
import com.yangpang.domain.review.SurveyJson;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.LinkedHashMap;
import java.util.Map;

/** 마이양팡 리뷰 관리 (기획서 MY-07) */
@Controller
@RequiredArgsConstructor
public class MyReviewController {

    /** 맞춤 평가 입력값은 "s:질문" 이름으로 넘어온다 */
    private static final String SURVEY_PREFIX = "s:";

    private final OrderItemRepository orderItemRepository;
    private final ReviewRepository reviewRepository;
    private final ReviewService reviewService;
    private final ProductRepository productRepository;
    private final CategoryMenu categoryMenu;

    @GetMapping("/my/reviews")
    @Transactional(readOnly = true)
    public String list(@AuthenticationPrincipal MemberPrincipal me, @RequestParam(defaultValue = "writable") String tab, Model model) {
        model.addAttribute("tab", tab);
        model.addAttribute("writable", orderItemRepository.findReviewable(me.id(), PageRequest.of(0, 50)));
        model.addAttribute("written", reviewRepository.findByMemberIdOrderByCreatedAtDesc(me.id(), PageRequest.of(0, 50)).getContent());
        return "my/reviews";
    }

    @GetMapping("/my/reviews/new")
    @Transactional(readOnly = true)
    public String newForm(@AuthenticationPrincipal MemberPrincipal me, @RequestParam Long orderItem, Model model) {
        OrderItem item = orderItemRepository.findMine(orderItem, me.id())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        fill(model, item.getProductId(), item.getProductName(), item.getOptionText(), item.getImageUrl());
        model.addAttribute("orderItemId", item.getId());
        model.addAttribute("review", null);
        model.addAttribute("answers", Map.of());
        return "my/review-form";
    }

    @PostMapping("/my/reviews/new")
    public String create(@AuthenticationPrincipal MemberPrincipal me, @RequestParam Long orderItemId,
                         @RequestParam Map<String, String> params, RedirectAttributes redirect) {
        reviewService.write(me.id(), orderItemId, input(params));
        redirect.addFlashAttribute("flashMessage", "리뷰를 등록했어요. 고마워요!");
        return "redirect:/my/reviews?tab=written";
    }

    @GetMapping("/my/reviews/{id}/edit")
    @Transactional(readOnly = true)
    public String editForm(@AuthenticationPrincipal MemberPrincipal me, @PathVariable Long id, Model model) {
        Review r = reviewRepository.findById(id).filter(x -> x.getMemberId().equals(me.id()))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        var p = productRepository.findById(r.getProductId()).orElseThrow();
        fill(model, p.getId(), p.getName(), r.getOptionText(), "/images/products/" + p.getId() + "/1.svg");
        model.addAttribute("review", r);
        model.addAttribute("answers", SurveyJson.read(r.getSurvey()));
        return "my/review-form";
    }

    @PostMapping("/my/reviews/{id}/edit")
    public String update(@AuthenticationPrincipal MemberPrincipal me, @PathVariable Long id,
                         @RequestParam Map<String, String> params, RedirectAttributes redirect) {
        reviewService.edit(me.id(), id, input(params));
        redirect.addFlashAttribute("flashMessage", "리뷰를 수정했어요");
        return "redirect:/my/reviews?tab=written";
    }

    @PostMapping("/my/reviews/{id}/delete")
    public String delete(@AuthenticationPrincipal MemberPrincipal me, @PathVariable Long id, RedirectAttributes redirect) {
        reviewService.delete(me.id(), id);
        redirect.addFlashAttribute("flashMessage", "리뷰를 삭제했어요");
        return "redirect:/my/reviews?tab=written";
    }

    private void fill(Model model, Long productId, String productName, String optionText, String imageUrl) {
        var product = productRepository.findById(productId).orElseThrow();
        String top = categoryMenu.path(product.getCategory().getId()).stream().findFirst()
                .map(CategoryMenu.Node::name).orElse("");
        model.addAttribute("productName", productName);
        model.addAttribute("optionText", optionText);
        model.addAttribute("imageUrl", imageUrl);
        model.addAttribute("questions", ReviewSurvey.questionsFor(top));
    }

    private static ReviewService.Input input(Map<String, String> params) {
        int rating = parseInt(params.get("rating"), 0);
        Map<String, String> survey = new LinkedHashMap<>();
        params.forEach((k, v) -> {
            if (k.startsWith(SURVEY_PREFIX) && v != null && !v.isBlank()) survey.put(k.substring(SURVEY_PREFIX.length()), v);
        });
        if (survey.size() > 5) throw new BusinessException("맞춤 평가 값이 올바르지 않아요");
        return new ReviewService.Input(rating, params.get("headline"), params.get("content"), survey,
                parseInt(params.get("photoCount"), 0));
    }

    private static int parseInt(String s, int dflt) {
        try {
            return s == null ? dflt : Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return dflt;
        }
    }
}
