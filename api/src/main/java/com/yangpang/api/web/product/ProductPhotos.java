package com.yangpang.api.web.product;

import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 카테고리별 상품 사진 (Pixabay 무료 사진, tools/catalog-photos로 받음).
 * 상품 이미지 주소(/images/products/{id}/{n}.svg)는 그대로 두고, 상품 카테고리에 사진이 있으면 그 사진을 쓴다.
 * 같은 카테고리 상품끼리 사진 몇 장을 나눠 쓰므로 상품 id로 시작 사진을 바꿔 이웃 상품과 덜 겹치게 한다.
 * 사진이 없는 카테고리(운영자가 새로 만든 카테고리 등)는 기존 그림(SVG)을 쓴다.
 */
@Slf4j
@Component
public class ProductPhotos {

    static final String BASE = "static/images/catalog/";

    private record Folder(String slug, int count) {
    }

    private final JdbcTemplate jdbc;
    private final Map<String, Folder> byCategory;
    private final Map<Long, Optional<Folder>> byProduct = new ConcurrentHashMap<>();

    public ProductPhotos(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        this.byCategory = loadManifest();
        log.info("[상품 사진] 카테고리 {}개에 사진 있음", byCategory.size());
    }

    private static Map<String, Folder> loadManifest() {
        Map<String, Folder> map = new HashMap<>();
        ClassPathResource manifest = new ClassPathResource(BASE + "manifest.csv");
        if (!manifest.exists()) return map;
        try (var in = new BufferedReader(new InputStreamReader(manifest.getInputStream(), StandardCharsets.UTF_8))) {
            in.lines().skip(1).map(line -> line.split(",")).filter(c -> c.length == 3)
                    .forEach(c -> map.put(c[0], new Folder(c[1], Integer.parseInt(c[2].trim()))));
        } catch (IOException e) {
            log.warn("[상품 사진] manifest.csv를 읽지 못함", e);
        }
        return map;
    }

    /** 상품의 n번째(1부터) 이미지 사진 주소. 사진이 없는 카테고리면 empty */
    public Optional<String> url(long productId, int index) {
        return folder(productId).map(f -> "/images/catalog/" + f.slug() + "/" + photoNo(productId, index, f.count()) + ".jpg");
    }

    /** 이미지 검색용으로 사진을 읽는다 */
    public Optional<BufferedImage> image(long productId, int index) {
        return folder(productId).flatMap(f -> {
            var res = new ClassPathResource(BASE + f.slug() + "/" + photoNo(productId, index, f.count()) + ".jpg");
            try (InputStream in = res.getInputStream()) {
                return Optional.ofNullable(ImageIO.read(in));
            } catch (IOException e) {
                return Optional.empty();
            }
        });
    }

    static int photoNo(long productId, int index, int count) {
        return (int) Math.floorMod(productId + index - 1, (long) count) + 1;
    }

    private Optional<Folder> folder(long productId) {
        if (byCategory.isEmpty()) return Optional.empty();
        Optional<Folder> cached = byProduct.get(productId);
        if (cached != null) return cached;
        List<String> names = jdbc.queryForList(
                "select c.name from product p join category c on c.id = p.category_id where p.id = ?", String.class, productId);
        if (names.isEmpty()) return Optional.empty();   // 아직 없는 상품은 기억하지 않는다
        Optional<Folder> found = Optional.ofNullable(byCategory.get(names.getFirst()));
        byProduct.put(productId, found);
        return found;
    }
}
