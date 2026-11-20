package com.yangpang.domain.catalog;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface CategoryRepository extends JpaRepository<Category, Long> {
    List<Category> findAllByOrderByDepthAscSortOrderAscIdAsc();

    boolean existsByParentId(Long parentId);

    int countByParentId(Long parentId);
}
