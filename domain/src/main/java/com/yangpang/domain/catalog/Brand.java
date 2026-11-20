package com.yangpang.domain.catalog;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Entity
@Table(name = "brand")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Brand {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 100)
    private String name;

    static Brand create(String name) {
        Brand b = new Brand();
        b.name = name;
        return b;
    }
}
