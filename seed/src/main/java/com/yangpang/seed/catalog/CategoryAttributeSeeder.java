package com.yangpang.seed.catalog;

import com.yangpang.seed.SeedContext;
import com.yangpang.seed.Seeder;
import lombok.RequiredArgsConstructor;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 카테고리 필수 속성 (M3, 기획서 시나리오 9). 대분류에 정의하면 하위 소분류 상품 모두에 적용된다.
 * 판매자가 상품을 등록할 때 필수 속성을 빠뜨리면 등록이 거부된다.
 */
@Component
@Order(15)
@RequiredArgsConstructor
public class CategoryAttributeSeeder implements Seeder {

    /** 이름, 필수 여부, 선택지(null이면 자유 입력) */
    private record Attr(String name, boolean required, String options) {
    }

    private static final Map<String, List<Attr>> ATTRS = Map.of(
            "패션의류/잡화", List.of(new Attr("성별", true, "남성,여성,공용"), new Attr("소재", true, null)),
            "뷰티", List.of(new Attr("피부타입", true, "모든피부,건성,지성,복합성,민감성"), new Attr("용량", true, null)),
            "출산/유아동", List.of(new Attr("사용연령", true, null), new Attr("KC인증번호", true, null)),
            "식품", List.of(new Attr("보관방법", true, "실온,냉장,냉동"), new Attr("원산지", true, null)),
            "신선식품", List.of(new Attr("보관방법", true, "실온,냉장,냉동"), new Attr("원산지", true, null)),
            "주방용품", List.of(new Attr("재질", true, null)),
            "가전디지털", List.of(new Attr("정격전압", true, "220V,110V,USB,배터리"), new Attr("KC인증번호", true, null),
                    new Attr("에너지효율등급", false, "1등급,2등급,3등급,4등급,5등급,해당없음")),
            "반려동물용품", List.of(new Attr("대상", true, "강아지,고양이,공용")),
            "완구/취미", List.of(new Attr("사용연령", true, null), new Attr("KC인증번호", false, null)));

    private final JdbcTemplate jdbc;

    @Override
    public String name() {
        return "카테고리 속성";
    }

    @Override
    public void seed(SeedContext ctx) {
        ATTRS.forEach((top, attrs) -> {
            List<Long> ids = jdbc.queryForList("select id from category where parent_id is null and name = ?", Long.class, top);
            if (ids.isEmpty()) return;
            int sort = 0;
            for (Attr a : attrs) {
                jdbc.update("insert into category_attribute(category_id, name, required, options, sort_order) values (?,?,?,?,?)",
                        ids.getFirst(), a.name(), a.required(), a.options(), sort++);
            }
        });
    }
}
