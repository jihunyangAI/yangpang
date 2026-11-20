package com.yangpang.api.web.admin;

import com.yangpang.api.search.ProductIndexer;
import com.yangpang.domain.common.BusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.Arrays;
import java.util.stream.Collectors;

/** 검색 동의어 편집 (기획서 5.3 검색, 운영자 5.16). 저장하면 Meilisearch 설정에 바로 반영 */
@Controller
@RequestMapping("/admin/synonyms")
@RequiredArgsConstructor
public class AdminSynonymController {

    private final JdbcTemplate jdbc;
    private final ProductIndexer indexer;

    @GetMapping
    public String list(Model model) {
        model.addAttribute("synonyms", jdbc.queryForList("select id, words, created_at from search_synonym order by id"));
        return "admin/synonyms";
    }

    @PostMapping
    public String add(@RequestParam String words, RedirectAttributes redirect) {
        String normalized = Arrays.stream(words.split(",")).map(String::trim).filter(w -> !w.isEmpty()).distinct()
                .collect(Collectors.joining(", "));
        if (normalized.split(", ").length < 2) throw new BusinessException("같은 뜻 단어를 쉼표로 2개 이상 입력해 주세요 (예: 노트북, 랩탑)");
        if (normalized.length() > 300) throw new BusinessException("너무 길어요 (300자까지)");
        jdbc.update("insert into search_synonym(words) values (?)", normalized);
        redirect.addFlashAttribute("flashMessage", "동의어를 추가했어요" + apply());
        return "redirect:/admin/synonyms";
    }

    @PostMapping("/{id}/delete")
    public String delete(@PathVariable Long id, RedirectAttributes redirect) {
        jdbc.update("delete from search_synonym where id = ?", id);
        redirect.addFlashAttribute("flashMessage", "동의어를 지웠어요" + apply());
        return "redirect:/admin/synonyms";
    }

    private String apply() {
        return indexer.pushSettings() ? " · 검색에 바로 반영됐어요" : " · 검색 엔진이 꺼져 있어 다음 실행 때 반영돼요";
    }
}
