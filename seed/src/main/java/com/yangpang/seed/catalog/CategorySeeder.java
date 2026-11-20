package com.yangpang.seed.catalog;

import com.yangpang.seed.SeedContext;
import com.yangpang.seed.Seeder;
import com.yangpang.seed.data.CatalogDictionary;
import lombok.RequiredArgsConstructor;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/** 카테고리 3단계 트리 (대 16 / 중 48 / 소 96) */
@Component
@Order(10)
@RequiredArgsConstructor
public class CategorySeeder implements Seeder {

    private final JdbcTemplate jdbc;

    @Override
    public String name() {
        return "카테고리";
    }

    @Override
    public void seed(SeedContext ctx) {
        List<Object[]> rows = new ArrayList<>();
        long id = 0;
        int topOrder = 0;
        for (CatalogDictionary.Top top : CatalogDictionary.TREE) {
            long topId = ++id;
            rows.add(new Object[]{topId, null, top.name(), 1, topOrder++, top.icon()});
            int midOrder = 0;
            for (CatalogDictionary.Mid mid : top.mids()) {
                long midId = ++id;
                rows.add(new Object[]{midId, topId, mid.name(), 2, midOrder++, null});
                int smallOrder = 0;
                for (String small : mid.smalls()) {
                    long smallId = ++id;
                    rows.add(new Object[]{smallId, midId, small, 3, smallOrder++, null});
                    ctx.leaves.add(new SeedContext.Leaf(smallId, top, small));
                }
            }
        }
        jdbc.batchUpdate("insert into category(id, parent_id, name, depth, sort_order, icon) values (?,?,?,?,?,?)", rows);
    }
}
