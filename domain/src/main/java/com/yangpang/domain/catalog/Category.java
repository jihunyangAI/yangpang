package com.yangpang.domain.catalog;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Entity
@Table(name = "category")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Category {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "parent_id")
    private Long parentId;

    @Column(nullable = false, length = 50)
    private String name;

    @Column(nullable = false)
    private int depth;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    @Column(length = 50)
    private String icon;

    static Category create(Long parentId, String name, int depth, int sortOrder) {
        Category c = new Category();
        c.parentId = parentId;
        c.name = name;
        c.depth = depth;
        c.sortOrder = sortOrder;
        return c;
    }

    void rename(String name) {
        this.name = name;
    }
}
