package com.yangpang.seed.catalog;

import com.yangpang.seed.SeedContext;
import com.yangpang.seed.Seeder;
import com.yangpang.seed.data.PeopleDictionary;
import lombok.RequiredArgsConstructor;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** 가상 브랜드 (음절 조합: 모아홈, 데일리핏 ...) */
@Component
@Order(20)
@RequiredArgsConstructor
public class BrandSeeder implements Seeder {

    private final JdbcTemplate jdbc;

    @Override
    public String name() {
        return "브랜드";
    }

    @Override
    public void seed(SeedContext ctx) {
        Set<String> names = new LinkedHashSet<>();
        while (names.size() < ctx.scale.brands) {
            names.add(ctx.pick(PeopleDictionary.BRAND_PREFIXES) + ctx.pick(PeopleDictionary.BRAND_SUFFIXES));
        }
        List<Object[]> rows = new ArrayList<>();
        long id = 0;
        for (String name : names) {
            id++;
            rows.add(new Object[]{id, name});
            ctx.brandIds.add(id);
            ctx.brandNames.put(id, name);
        }
        jdbc.batchUpdate("insert into brand(id, name) values (?,?)", rows);
    }
}
