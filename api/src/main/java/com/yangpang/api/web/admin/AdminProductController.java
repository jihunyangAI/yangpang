package com.yangpang.api.web.admin;

import com.yangpang.api.web.layout.CategoryMenu;
import com.yangpang.domain.catalog.CatalogAdminService;
import com.yangpang.domain.catalog.Product;
import com.yangpang.domain.catalog.ProductRepository;
import com.yangpang.domain.catalog.ProductStatus;
import com.yangpang.domain.catalog.search.ProductSearchRepository;
import com.yangpang.domain.common.BusinessException;
import com.yangpang.domain.common.DeliveryType;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import org.springframework.web.util.HtmlUtils;

import java.util.ArrayList;
import java.util.List;

/** 관리자 상품 관리 (기획서 5.16): 목록·검색, 직매입 상품 등록, 상품·판매 오퍼 수정 */
@Controller
@RequestMapping("/admin/products")
@RequiredArgsConstructor
public class AdminProductController {

    /** 소분류 선택 목록용: "대분류 > 중분류 > 소분류" */
    public record LeafOption(Long id, String path) {
    }

    private final ProductSearchRepository searchRepository;
    private final ProductRepository productRepository;
    private final CatalogAdminService adminService;
    private final CategoryMenu categoryMenu;

    @GetMapping
    @Transactional(readOnly = true)
    public String list(@RequestParam(required = false) String q, @RequestParam(required = false) ProductStatus status,
                       @RequestParam(defaultValue = "1") int page, Model model) {
        var products = searchRepository.adminSearch(q, status, PageRequest.of(Math.max(0, page - 1), 30));
        model.addAttribute("products", products);
        model.addAttribute("pageNumbers", com.yangpang.api.web.Paging.pageNumbers(products));
        model.addAttribute("q", q);
        model.addAttribute("status", status);
        model.addAttribute("statuses", ProductStatus.values());
        return "admin/products";
    }

    @GetMapping("/new")
    public String newForm(Model model) {
        model.addAttribute("leaves", leaves());
        return "admin/product-new";
    }

    @PostMapping("/new")
    public String create(@RequestParam Long categoryId, @RequestParam(required = false) String brandName,
                         @RequestParam String name, @RequestParam(required = false) String description,
                         @RequestParam(required = false) String option1Name, @RequestParam(required = false) String option1Values,
                         @RequestParam(required = false) String option2Name, @RequestParam(required = false) String option2Values,
                         @RequestParam long listPrice, @RequestParam long salePrice, @RequestParam int stock,
                         @RequestParam(defaultValue = "YANGPANG") DeliveryType deliveryType, RedirectAttributes redirect) {
        if (name == null || name.isBlank()) throw new BusinessException("상품명을 입력해 주세요");
        Product p = adminService.createDirectProduct(new CatalogAdminService.CreateProduct(categoryId, brandName, name,
                textToHtml(description), option1Name, option1Values, option2Name, option2Values,
                listPrice, salePrice, stock, deliveryType));
        redirect.addFlashAttribute("flashMessage", "상품을 등록했어요 (#" + p.getId() + ")");
        return "redirect:/admin/products/" + p.getId();
    }

    @GetMapping("/{id}")
    @Transactional(readOnly = true)
    public String detail(@PathVariable Long id, Model model) {
        Product p = productRepository.findById(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        p.getItems().forEach(i -> i.getVendorItems().forEach(v -> v.getSeller().getName())); // 화면용 미리 불러오기
        if (p.getBrand() != null) p.getBrand().getName();
        p.getCategory().getName();
        model.addAttribute("p", p);
        model.addAttribute("leaves", leaves());
        model.addAttribute("statuses", ProductStatus.values());
        return "admin/product-detail";
    }

    @PostMapping("/{id}")
    public String update(@PathVariable Long id, @RequestParam String name, @RequestParam Long categoryId,
                         @RequestParam ProductStatus status, RedirectAttributes redirect) {
        Product p = productRepository.findById(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        adminService.updateProduct(id, name, categoryId, status, p.getDescriptionHtml());
        redirect.addFlashAttribute("flashMessage", "상품 정보를 저장했어요");
        return "redirect:/admin/products/" + id;
    }

    @PostMapping("/{id}/offers/{vendorItemId}")
    public String updateOffer(@PathVariable Long id, @PathVariable Long vendorItemId,
                              @RequestParam long listPrice, @RequestParam long salePrice, @RequestParam int stock,
                              @RequestParam ProductStatus status, RedirectAttributes redirect) {
        adminService.updateVendorItem(vendorItemId, listPrice, salePrice, stock, status);
        redirect.addFlashAttribute("flashMessage", "판매 오퍼 #" + vendorItemId + "를 저장했어요");
        return "redirect:/admin/products/" + id;
    }

    /** 관리자 입력 설명은 HTML을 허용하지 않고 문단으로만 바꾼다 (스크립트 삽입 방지) */
    public static String textToHtml(String text) {
        if (text == null || text.isBlank()) return "<p>연구용 데모 상품입니다.</p>";
        StringBuilder sb = new StringBuilder("<section class=\"space-y-3\">");
        for (String para : text.trim().split("\\R\\s*\\R")) {
            sb.append("<p>").append(HtmlUtils.htmlEscape(para.trim()).replaceAll("\\R", "<br>")).append("</p>");
        }
        return sb.append("</section>").toString();
    }

    private List<LeafOption> leaves() {
        return leaves(categoryMenu);
    }

    /** 소분류 카테고리 선택지 (셀러센터에서도 사용) */
    public static List<LeafOption> leaves(CategoryMenu menu) {
        List<LeafOption> result = new ArrayList<>();
        for (CategoryMenu.Node top : menu.roots()) collect(top, top.name(), result);
        return result;
    }

    private static void collect(CategoryMenu.Node node, String path, List<LeafOption> out) {
        if (node.isLeaf()) {
            out.add(new LeafOption(node.id(), path));
            return;
        }
        for (CategoryMenu.Node child : node.children()) collect(child, path + " > " + child.name(), out);
    }
}
