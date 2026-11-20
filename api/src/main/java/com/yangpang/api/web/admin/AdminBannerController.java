package com.yangpang.api.web.admin;

import com.yangpang.api.security.SafeRedirect;
import com.yangpang.domain.common.BusinessException;
import com.yangpang.domain.display.Banner;
import com.yangpang.domain.display.BannerRepository;
import lombok.RequiredArgsConstructor;
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
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

/** 전시 관리: 메인 배너 (기획서 5.16). 홈 섹션 구성은 AdminHomeController */
@Controller
@RequestMapping("/admin/banners")
@RequiredArgsConstructor
public class AdminBannerController {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private final BannerRepository bannerRepository;
    private final Clock clock;

    @GetMapping
    public String list(Model model) {
        model.addAttribute("banners", bannerRepository.findAllByOrderBySortOrderAscIdAsc());
        model.addAttribute("themes", Banner.Theme.values());
        model.addAttribute("now", clock.instant());
        return "admin/banners";
    }

    @PostMapping
    @Transactional
    public String create(@RequestParam String title, @RequestParam(required = false) String subtitle,
                         @RequestParam(required = false) String caption, @RequestParam String linkUrl,
                         @RequestParam Banner.Theme theme, @RequestParam(defaultValue = "10") int sortOrder,
                         @RequestParam(required = false) LocalDate startsOn, @RequestParam(required = false) LocalDate endsOn,
                         RedirectAttributes redirect) {
        if (title.isBlank()) throw new BusinessException("제목을 입력해 주세요");
        // 배너 링크는 사이트 안 경로만 (외부 피싱 링크 방지)
        String link = SafeRedirect.sameSitePath(linkUrl.trim());
        if (link == null) throw new BusinessException("링크는 /로 시작하는 사이트 안 경로만 쓸 수 있어요 (예: /deals)");
        Instant starts = startsOn == null ? null : startsOn.atStartOfDay(KST).toInstant();
        Instant ends = endsOn == null ? null : endsOn.plusDays(1).atStartOfDay(KST).toInstant();
        bannerRepository.save(Banner.create(title.trim(), blank(subtitle), blank(caption), link, theme, sortOrder,
                starts, ends, clock.instant()));
        redirect.addFlashAttribute("flashMessage", "배너를 추가했어요");
        return "redirect:/admin/banners";
    }

    @PostMapping("/{id}/toggle")
    @Transactional
    public String toggle(@PathVariable Long id) {
        bannerRepository.findById(id).orElseThrow().toggle();
        return "redirect:/admin/banners";
    }

    @PostMapping("/{id}/move")
    @Transactional
    public String move(@PathVariable Long id, @RequestParam int sortOrder) {
        bannerRepository.findById(id).orElseThrow().move(sortOrder);
        return "redirect:/admin/banners";
    }

    @PostMapping("/{id}/delete")
    @Transactional
    public String delete(@PathVariable Long id, RedirectAttributes redirect) {
        bannerRepository.deleteById(id);
        redirect.addFlashAttribute("flashMessage", "배너를 삭제했어요");
        return "redirect:/admin/banners";
    }

    private static String blank(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
