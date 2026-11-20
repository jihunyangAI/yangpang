package com.yangpang.api.web.admin;

import com.yangpang.domain.common.BusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Controller;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.List;
import java.util.Map;

/** 홈 화면 구성: 섹션 순서·노출·제목 편집 (기획서 D-03, 5.16 전시 관리) */
@Controller
@RequestMapping("/admin/home")
@RequiredArgsConstructor
public class AdminHomeController {

    private final JdbcTemplate jdbc;

    @GetMapping
    public String page(Model model) {
        model.addAttribute("sections", jdbc.queryForList("select kind, title, visible, sort_order from home_section order by sort_order, kind"));
        return "admin/home";
    }

    /** 폼 값: title_KIND, visible_KIND(체크 시 true), order_KIND */
    @PostMapping
    @Transactional
    public String save(@RequestParam Map<String, String> params, RedirectAttributes redirect) {
        List<String> kinds = jdbc.queryForList("select kind from home_section", String.class);
        for (String k : kinds) {
            String title = params.getOrDefault("title_" + k, "").trim();
            if (title.isEmpty() || title.length() > 50) throw new BusinessException("섹션 제목은 1~50자예요");
            int order;
            try {
                order = Integer.parseInt(params.getOrDefault("order_" + k, "0").trim());
            } catch (NumberFormatException e) {
                throw new BusinessException("순서는 숫자로 입력해 주세요");
            }
            jdbc.update("update home_section set title = ?, visible = ?, sort_order = ? where kind = ?",
                    title, "true".equals(params.get("visible_" + k)), order, k);
        }
        redirect.addFlashAttribute("flashMessage", "홈 화면 구성을 저장했어요");
        return "redirect:/admin/home";
    }

    /** 한 칸 위/아래로 */
    @PostMapping("/move")
    @Transactional
    public String move(@RequestParam String kind, @RequestParam String dir) {
        List<String> order = jdbc.queryForList("select kind from home_section order by sort_order, kind", String.class);
        int i = order.indexOf(kind);
        int j = "up".equals(dir) ? i - 1 : i + 1;
        if (i >= 0 && j >= 0 && j < order.size()) {
            order.set(i, order.get(j));
            order.set(j, kind);
            for (int n = 0; n < order.size(); n++) jdbc.update("update home_section set sort_order = ? where kind = ?", (n + 1) * 10, order.get(n));
        }
        return "redirect:/admin/home";
    }
}
