package com.yangpang.domain.catalog;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/** 카테고리 id → 자기 자신과 모든 상위 카테고리 id (쿠폰 대상 카테고리 판정용) */
@Component
@RequiredArgsConstructor
public class CategoryPaths {

    private final CategoryRepository categoryRepository;

    @Transactional(readOnly = true)
    public Map<Long, Set<Long>> all() {
        Map<Long, Long> parent = new HashMap<>();
        categoryRepository.findAll().forEach(c -> parent.put(c.getId(), c.getParentId()));
        Map<Long, Set<Long>> result = new HashMap<>();
        for (Long id : parent.keySet()) {
            Set<Long> path = new LinkedHashSet<>();
            Long cur = id;
            while (cur != null && path.add(cur)) cur = parent.get(cur);
            result.put(id, path);
        }
        return result;
    }
}
