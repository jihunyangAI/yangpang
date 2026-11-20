package com.yangpang.api.web.admin;

import com.yangpang.api.web.layout.CategoryMenu;
import com.yangpang.domain.catalog.CatalogAdminService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/** 관리자 카테고리 관리: 트리 보기, 하위 추가, 이름 변경 (삭제·순서 변경은 이후 단계) */
@Controller
@RequestMapping("/admin/categories")
@RequiredArgsConstructor
public class AdminCategoryController {

    private final CategoryMenu categoryMenu;
    private final CatalogAdminService adminService;

    @GetMapping
    public String tree(Model model) {
        model.addAttribute("roots", categoryMenu.roots());
        return "admin/categories";
    }

    @PostMapping
    public String add(@RequestParam(required = false) Long parentId, @RequestParam String name, RedirectAttributes redirect) {
        adminService.addCategory(parentId, name);
        categoryMenu.invalidate();
        redirect.addFlashAttribute("flashMessage", "카테고리 '" + name.trim() + "'를 추가했어요");
        return "redirect:/admin/categories";
    }

    @PostMapping("/{id}/rename")
    public String rename(@PathVariable Long id, @RequestParam String name, RedirectAttributes redirect) {
        adminService.renameCategory(id, name);
        categoryMenu.invalidate();
        redirect.addFlashAttribute("flashMessage", "이름을 바꿨어요");
        return "redirect:/admin/categories";
    }
}
