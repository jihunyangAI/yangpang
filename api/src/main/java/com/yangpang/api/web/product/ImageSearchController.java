package com.yangpang.api.web.product;

import com.yangpang.api.search.ImageSearchService;
import com.yangpang.domain.common.BusinessException;
import com.yangpang.domain.imagesearch.ColorHistogram;
import com.yangpang.domain.imagesearch.ColorNames;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * 이미지 검색 (M4 ⑦). 사진을 올리거나 색을 고르면 색 분포가 비슷한 상품을 보여 준다.
 * 외부 모델 없이 색만 보므로 "무엇인지"가 아니라 "어떤 색인지"로 찾는다. 올린 사진은 메모리에서만 쓰고 저장하지 않는다.
 */
@Controller
@RequiredArgsConstructor
public class ImageSearchController {

    static final long MAX_BYTES = 5L * 1024 * 1024;
    static final int MAX_PIXELS = 6000;
    static final double MIN_SCORE = 0.15;   // 고른 색이 상품 사진의 15% 이상 (실제 사진은 바탕·다른 색이 섞여 있음)

    public record QueryColor(String hex, String name, int percent) {
    }

    public record Result(ProductCard card, int percent) {
    }

    private final ImageSearchService imageSearchService;
    private final ProductCardService productCardService;

    @GetMapping("/search/image")
    public String page(@RequestParam(required = false) String color, Model model) {
        model.addAttribute("colors", ColorNames.COLORS.entrySet().stream().map(e -> Map.entry(e.getKey(), ColorHistogram.hex(e.getValue()))).toList());
        if (color != null) {
            Color c = ColorNames.of(color).orElseThrow(() -> new BusinessException("고를 수 있는 색이 아니에요"));
            model.addAttribute("chosen", color);
            search(ColorHistogram.ofPhoto(ColorHistogram.swatch(c)), model);   // 흰 바탕은 덜 세고 고른 색 위주로
        }
        model.addAttribute("pageTitle", "이미지로 찾기");
        return "product/image-search";
    }

    @PostMapping("/search/image")
    public String upload(@RequestParam("photo") MultipartFile photo, Model model) throws IOException {
        if (photo == null || photo.isEmpty()) throw new BusinessException("사진을 골라 주세요");
        if (photo.getSize() > MAX_BYTES) throw new BusinessException("사진은 5MB까지 올릴 수 있어요");
        BufferedImage img = read(photo);
        model.addAttribute("colors", ColorNames.COLORS.entrySet().stream().map(e -> Map.entry(e.getKey(), ColorHistogram.hex(e.getValue()))).toList());
        model.addAttribute("uploaded", true);
        search(ColorHistogram.ofPhoto(img), model);   // 올린 사진의 바탕(가장자리) 색은 덜 센다
        model.addAttribute("pageTitle", "이미지로 찾기");
        return "product/image-search";
    }

    private void search(float[] query, Model model) {
        List<ImageSearchService.Hit> hits = imageSearchService.search(query, 40, MIN_SCORE);
        Map<Long, ProductCard> cards = new java.util.HashMap<>();
        productCardService.cardsByIds(hits.stream().map(ImageSearchService.Hit::productId).toList()).forEach(c -> cards.put(c.productId(), c));
        model.addAttribute("results", hits.stream().filter(h -> cards.containsKey(h.productId()))
                .map(h -> new Result(cards.get(h.productId()), (int) Math.round(h.score() * 100))).toList());
        model.addAttribute("queryColors", ColorHistogram.topBins(query, 4, 0.04).stream().map(b -> {
            Color c = ColorHistogram.binColor(b);
            return new QueryColor(ColorHistogram.hex(c), ColorNames.nearest(c), Math.round(query[b] * 100));
        }).toList());
    }

    /** PNG·JPEG·GIF·BMP만, 가로세로 6000px까지 (먼저 크기만 읽어 큰 이미지 폭탄을 막는다) */
    static BufferedImage read(MultipartFile photo) throws IOException {
        try (ImageInputStream in = ImageIO.createImageInputStream(photo.getInputStream())) {
            if (in == null) throw new BusinessException("이미지를 읽을 수 없어요");
            Iterator<ImageReader> readers = ImageIO.getImageReaders(in);
            if (!readers.hasNext()) throw new BusinessException("PNG·JPG·GIF 사진만 올릴 수 있어요");
            ImageReader reader = readers.next();
            try {
                reader.setInput(in, true, true);
                if (reader.getWidth(0) > MAX_PIXELS || reader.getHeight(0) > MAX_PIXELS) {
                    throw new BusinessException("사진이 너무 커요 (가로·세로 6000px까지)");
                }
                return reader.read(0);
            } finally {
                reader.dispose();
            }
        } catch (javax.imageio.IIOException e) {
            throw new BusinessException("이미지를 읽을 수 없어요");
        }
    }
}
