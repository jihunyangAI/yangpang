package com.yangpang.domain.catalog;

import com.yangpang.domain.common.BusinessException;
import com.yangpang.domain.common.DeliveryType;
import com.yangpang.domain.seller.Seller;
import com.yangpang.domain.seller.SellerRepository;
import com.yangpang.domain.seller.SellerType;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;

/**
 * 관리자 상품·카테고리 관리 (기획서 5.16).
 * MVP에서는 양팡 직매입(1P) 상품만 관리자 화면에서 등록한다. 판매자 상품 등록은 셀러센터(M3).
 */
@Service
@RequiredArgsConstructor
@Transactional
public class CatalogAdminService {

    /**
     * 직매입 상품 등록 값.
     * @param option1Values 쉼표로 구분 (예: "블랙,화이트"). 비우면 단일 상품
     */
    public record CreateProduct(Long categoryId, String brandName, String name, String descriptionHtml,
                                String option1Name, String option1Values, String option2Name, String option2Values,
                                long listPrice, long salePrice, int stock, DeliveryType deliveryType) {
    }

    private final EntityManager em;
    private final CategoryRepository categoryRepository;
    private final SellerRepository sellerRepository;
    private final ProductRepository productRepository;
    private final VendorItemRepository vendorItemRepository;
    private final ProductSummaryService summaryService;
    private final CategoryPaths categoryPaths;
    private final org.springframework.jdbc.core.JdbcTemplate jdbc;

    /** 카테고리 필수 속성 (대분류~소분류에 정의된 것 모두) */
    public record Attribute(String name, boolean required, List<String> options) {
    }

    /** 관리자 직매입 등록 (운영자는 필수 속성 검사 생략) */
    public Product createDirectProduct(CreateProduct c) {
        if (c.deliveryType() != DeliveryType.YANGPANG && c.deliveryType() != DeliveryType.FRESH) {
            throw new BusinessException("직매입 상품은 양팡배송 또는 양팡프레시만 선택할 수 있습니다");
        }
        Seller direct = sellerRepository.findFirstBySellerTypeOrderByIdAsc(SellerType.DIRECT)
                .orElseThrow(() -> new BusinessException("양팡 직매입 판매자가 없습니다"));
        return createProduct(direct, c, java.util.Map.of(), false);
    }

    /**
     * 판매자 새 상품 등록 (기획서 5.15). 배송 유형은 판매자 유형으로 정해진다.
     * 카테고리 필수 속성을 빠뜨리면 등록 실패 (기획서 시나리오 9).
     */
    public Product createSellerProduct(Long sellerId, CreateProduct c, java.util.Map<String, String> attributes) {
        Seller seller = activeSeller(sellerId);
        DeliveryType delivery = deliveryOf(seller, c.deliveryType());
        CreateProduct fixed = new CreateProduct(c.categoryId(), c.brandName(), c.name(), c.descriptionHtml(), c.option1Name(),
                c.option1Values(), c.option2Name(), c.option2Values(), c.listPrice(), c.salePrice(), c.stock(), delivery);
        return createProduct(seller, fixed, attributes, true);
    }

    @org.springframework.transaction.annotation.Transactional(readOnly = true)
    public List<Attribute> attributesOf(Long categoryId) {
        Set<Long> path = categoryPaths.all().getOrDefault(categoryId, Set.of());
        if (path.isEmpty()) return List.of();
        return jdbc.query(con -> {
            var ps = con.prepareStatement("""
                    select a.name, a.required, a.options from category_attribute a join category c on c.id = a.category_id
                    where a.category_id = any(?) order by c.depth, a.sort_order, a.id""");
            ps.setArray(1, con.createArrayOf("bigint", path.toArray()));
            return ps;
        }, (rs, i) -> new Attribute(rs.getString(1), rs.getBoolean(2),
                rs.getString(3) == null ? List.of() : Arrays.stream(rs.getString(3).split(",")).map(String::trim).toList()));
    }

    private Product createProduct(Seller seller, CreateProduct c, java.util.Map<String, String> attributes, boolean enforceAttributes) {
        Category category = categoryRepository.findById(c.categoryId())
                .orElseThrow(() -> new BusinessException("카테고리를 선택해 주세요"));
        if (categoryRepository.existsByParentId(category.getId())) {
            throw new BusinessException("가장 아래 단계(소분류) 카테고리를 선택해 주세요");
        }
        if (blank(c.name())) throw new BusinessException("상품명을 입력해 주세요");
        validatePrice(c.listPrice(), c.salePrice(), c.stock());
        java.util.Map<String, String> attrs = new java.util.LinkedHashMap<>();
        for (Attribute a : attributesOf(category.getId())) {
            String v = attributes.get(a.name()) == null ? "" : attributes.get(a.name()).trim();
            if (v.isEmpty()) {
                if (a.required() && enforceAttributes) throw new BusinessException("필수 속성 [" + a.name() + "]을(를) 입력해 주세요");
                continue;
            }
            if (!a.options().isEmpty() && !a.options().contains(v)) {
                throw new BusinessException("[" + a.name() + "]은(는) " + String.join(", ", a.options()) + " 중에서 골라 주세요");
            }
            attrs.put(a.name(), v.length() > 100 ? v.substring(0, 100) : v);
        }
        Seller direct = seller;

        List<String> values1 = split(c.option1Values());
        List<String> values2 = split(c.option2Values());
        if (values1.size() > 1 || !values1.getFirst().isEmpty()) {
            if (blank(c.option1Name())) throw new BusinessException("첫째 옵션 이름을 입력해 주세요 (예: 색상)");
        }
        if (!values2.getFirst().isEmpty() && (blank(c.option2Name()) || values1.getFirst().isEmpty())) {
            throw new BusinessException("둘째 옵션은 첫째 옵션이 있을 때만 쓸 수 있습니다");
        }

        Brand brand = blank(c.brandName()) ? null : findOrCreateBrand(c.brandName().trim());
        Product product = Product.create(category, brand, c.name().trim(),
                values1.getFirst().isEmpty() ? null : c.option1Name().trim(),
                values2.getFirst().isEmpty() ? null : c.option2Name().trim(),
                c.descriptionHtml(), "{\"제조사\":\"" + (brand == null ? "양팡" : brand.getName()).replace("\"", "")
                        + "\",\"원산지\":\"국내산\",\"품질보증기준\":\"관련 법 및 소비자분쟁해결기준에 따름\"}");
        em.persist(product);

        int sort = 0;
        for (String v1 : values1) {
            for (String v2 : values2) {
                Item item = Item.create(product, v1.isEmpty() ? null : v1, v2.isEmpty() ? null : v2, null, sort++);
                em.persist(item);
                em.persist(VendorItem.create(item, direct, c.listPrice(), c.salePrice(), c.stock(), c.deliveryType(),
                        c.deliveryType() == DeliveryType.FRESH ? 10 : 99));
            }
        }
        for (int n = 1; n <= 3; n++) {
            em.persist(ProductImage.create(product, "/images/products/" + product.getId() + "/" + n + ".svg", n - 1));
        }
        em.flush();
        attrs.forEach((k, v) -> jdbc.update("insert into product_attribute(product_id, name, value) values (?, ?, ?)",
                product.getId(), k, v));
        summaryService.refresh(Set.of(product.getId()));
        return product;
    }

    /** 기존 상품(SKU)에 내 판매 오퍼 추가 = 카탈로그 매칭. 이후 위너 경쟁 */
    public VendorItem addOffer(Long sellerId, Long itemId, long listPrice, long salePrice, int stock) {
        Seller seller = activeSeller(sellerId);
        validatePrice(listPrice, salePrice, stock);
        Item item = em.find(Item.class, itemId);
        if (item == null) throw new BusinessException("상품 옵션을 찾을 수 없어요");
        Long exists = jdbc.queryForObject("select count(*) from vendor_item where item_id = ? and seller_id = ?", Long.class, itemId, sellerId);
        if (exists != null && exists > 0) throw new BusinessException("이미 이 옵션을 판매하고 있어요");
        VendorItem vi = VendorItem.create(item, seller, listPrice, salePrice, stock, deliveryOf(seller, null), 99);
        vi.lose(); // 위너는 다시 계산해서 정한다
        em.persist(vi);
        em.flush();
        summaryService.refresh(Set.of(item.getProduct().getId()));
        return vi;
    }

    /** 판매자가 자기 오퍼의 가격·재고·상태를 바꾼다 */
    public void updateOfferAsSeller(Long sellerId, Long vendorItemId, long listPrice, long salePrice, int stock, ProductStatus status) {
        VendorItem vi = vendorItemRepository.findById(vendorItemId).orElseThrow(() -> new BusinessException("판매 오퍼가 없어요"));
        if (!vi.getSeller().getId().equals(sellerId)) throw new BusinessException("내 판매 상품이 아니에요");
        updateVendorItem(vendorItemId, listPrice, salePrice, stock, status);
    }

    private static DeliveryType deliveryOf(Seller seller, DeliveryType requested) {
        return switch (seller.getSellerType()) {
            case DIRECT -> requested == DeliveryType.FRESH ? DeliveryType.FRESH : DeliveryType.YANGPANG;
            case FULFILLMENT -> DeliveryType.FULFILLMENT;
            case MARKETPLACE -> DeliveryType.SELLER;
        };
    }

    private Seller activeSeller(Long sellerId) {
        Seller s = sellerRepository.findById(sellerId).orElseThrow(() -> new BusinessException("판매자가 없어요"));
        if (!s.isActive()) throw new BusinessException("입점 심사 중인 판매자예요");
        return s;
    }

    public void updateProduct(Long productId, String name, Long categoryId, ProductStatus status, String descriptionHtml) {
        Product p = productRepository.findById(productId).orElseThrow(() -> new BusinessException("상품이 없습니다"));
        Category category = categoryRepository.findById(categoryId).orElseThrow(() -> new BusinessException("카테고리가 없습니다"));
        if (blank(name)) throw new BusinessException("상품명을 입력해 주세요");
        p.update(name.trim(), category, status, descriptionHtml);
        summaryService.changed(Set.of(productId)); // 검색 색인 갱신
    }

    public void updateVendorItem(Long vendorItemId, long listPrice, long salePrice, int stock, ProductStatus status) {
        validatePrice(listPrice, salePrice, stock);
        VendorItem vi = vendorItemRepository.findById(vendorItemId).orElseThrow(() -> new BusinessException("판매 오퍼가 없습니다"));
        vi.update(listPrice, salePrice, stock, status);
        em.flush();
        summaryService.refresh(Set.of(vi.getItem().getProduct().getId()));
    }

    // ---------- 카테고리 ----------
    public Category addCategory(Long parentId, String name) {
        if (blank(name)) throw new BusinessException("카테고리 이름을 입력해 주세요");
        int depth = 1;
        if (parentId != null) {
            Category parent = categoryRepository.findById(parentId).orElseThrow(() -> new BusinessException("상위 카테고리가 없습니다"));
            if (parent.getDepth() >= 4) throw new BusinessException("카테고리는 4단계까지 만들 수 있습니다");
            if (productRepository.existsByCategoryId(parentId)) {
                throw new BusinessException("상품이 등록된 카테고리 아래에는 하위 카테고리를 만들 수 없습니다");
            }
            depth = parent.getDepth() + 1;
        }
        int sort = categoryRepository.countByParentId(parentId);
        return categoryRepository.save(Category.create(parentId, name.trim(), depth, sort));
    }

    public void renameCategory(Long id, String name) {
        if (blank(name)) throw new BusinessException("카테고리 이름을 입력해 주세요");
        categoryRepository.findById(id).orElseThrow(() -> new BusinessException("카테고리가 없습니다")).rename(name.trim());
    }

    // ---------- 도우미 ----------
    private Brand findOrCreateBrand(String name) {
        List<Brand> found = em.createQuery("select b from Brand b where b.name = :n", Brand.class)
                .setParameter("n", name).getResultList();
        if (!found.isEmpty()) return found.getFirst();
        Brand b = Brand.create(name);
        em.persist(b);
        return b;
    }

    private static void validatePrice(long listPrice, long salePrice, int stock) {
        if (salePrice <= 0) throw new BusinessException("판매가는 0원보다 커야 합니다");
        if (listPrice < salePrice) throw new BusinessException("정가는 판매가보다 작을 수 없습니다");
        if (stock < 0) throw new BusinessException("재고는 0 이상이어야 합니다");
    }

    /** "블랙, 화이트" → [블랙, 화이트]. 비어 있으면 [""] (옵션 없음) */
    private static List<String> split(String csv) {
        if (blank(csv)) return Collections.singletonList("");
        List<String> values = new ArrayList<>(Arrays.stream(csv.split(",")).map(String::trim)
                .filter(s -> !s.isEmpty()).distinct().toList());
        if (values.isEmpty()) return Collections.singletonList("");
        if (values.size() > 20) throw new BusinessException("옵션 값은 20개까지 입력할 수 있습니다");
        return values;
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }
}
