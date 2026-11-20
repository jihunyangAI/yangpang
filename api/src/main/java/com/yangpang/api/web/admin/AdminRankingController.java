package com.yangpang.api.web.admin;

import com.yangpang.domain.catalog.RankingService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.HashMap;
import java.util.Map;

/** 양팡 랭킹 가중치 조정 (기획서 5.18) */
@Controller
@RequestMapping("/admin/ranking")
@RequiredArgsConstructor
public class AdminRankingController {

    private final RankingService rankingService;

    @GetMapping
    public String page(Model model) {
        var weights = rankingService.weights();
        model.addAttribute("weights", weights);
        model.addAttribute("total", weights.stream().mapToInt(RankingService.Weight::weight).sum());
        model.addAttribute("top", rankingService.top(20));
        return "admin/ranking";
    }

    @PostMapping
    public String save(@RequestParam Map<String, String> params, RedirectAttributes redirect) {
        Map<String, Integer> w = new HashMap<>();
        params.forEach((k, v) -> {
            if (k.startsWith("w_")) {
                try {
                    w.put(k.substring(2), Integer.parseInt(v.trim()));
                } catch (NumberFormatException e) {
                    w.put(k.substring(2), -1); // 서비스에서 범위 오류로 안내
                }
            }
        });
        int n = rankingService.saveWeights(w);
        redirect.addFlashAttribute("flashMessage", "가중치를 저장하고 상품 " + n + "개의 순위를 다시 계산했어요");
        return "redirect:/admin/ranking";
    }

    @PostMapping("/recompute")
    public String recompute(RedirectAttributes redirect) {
        redirect.addFlashAttribute("flashMessage", "상품 " + rankingService.recompute() + "개의 순위를 다시 계산했어요");
        return "redirect:/admin/ranking";
    }
}
