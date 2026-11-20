package com.yangpang.api.web.product;

import com.yangpang.domain.imagesearch.ColorHistogram;
import com.yangpang.domain.imagesearch.ColorNames;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.util.HtmlUtils;

import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 가짜 상품 이미지의 그림 정의 (기획서 7.1 플레이스홀더, M4 ⑦에서 색상 반영).
 * 같은 정의로 SVG(화면)와 래스터(이미지 검색 색 분포 계산)를 그려 둘이 어긋나지 않게 한다.
 * <ul>
 *   <li>색상 옵션이 있는 상품: n번째 이미지는 n번째 색상의 큰 사각형(상품 몸체) — 사진처럼 상품 색이 보이게</li>
 *   <li>그 밖의 상품: 상품 id로 고른 파스텔 배경 + 작은 아이콘 (이전과 같음)</li>
 * </ul>
 */
@Component
@RequiredArgsConstructor
public class ProductImageSpecs {

    static final String[][] PALETTE = {
            {"#E8F4F1", "#0E7C6B"}, {"#FFF1E8", "#C2531B"}, {"#EEF0FF", "#4A50C8"}, {"#FDF0F5", "#B23A6E"},
            {"#F1F6E6", "#5B7A1E"}, {"#EAF4FB", "#1F6B9A"}, {"#FFF8E1", "#A3730B"}, {"#F3EEFA", "#7445A8"}};

    /** @param swatchName 색상 옵션 이름 (없으면 null) */
    public record Spec(String name, int index, String bg, String fg, String swatchName, Color swatch) {
        boolean lightSwatch() {
            return swatch != null && (swatch.getRed() + swatch.getGreen() + swatch.getBlue()) > 660;
        }
    }

    private final JdbcTemplate jdbc;

    public Spec spec(long productId, int index) {
        List<Spec> found = jdbc.query("""
                select p.name, p.option1_name,
                       (select string_agg(x.v, '|' order by x.o) from (select i.option1_value as v, min(i.sort_order * 100000 + i.id) as o
                         from item i where i.product_id = p.id and i.option1_value is not null group by i.option1_value) x)
                from product p where p.id = ?""", (rs, n) -> {
            String[] colors = PALETTE[(int) ((productId * 7 + index) % PALETTE.length)];
            String swatchName = null;
            if ("색상".equals(rs.getString(2)) && rs.getString(3) != null) {
                List<String> options = Arrays.asList(rs.getString(3).split("\\|"));
                String candidate = options.get(Math.floorMod(index - 1, options.size()));
                if (ColorNames.of(candidate).isPresent()) swatchName = candidate;
            }
            return new Spec(rs.getString(1), index, colors[0], colors[1], swatchName,
                    swatchName == null ? null : ColorNames.of(swatchName).orElseThrow());
        }, productId);
        if (found.isEmpty()) {
            String[] colors = PALETTE[(int) ((productId * 7 + index) % PALETTE.length)];
            return new Spec("상품 이미지", index, colors[0], colors[1], null, null);
        }
        return found.getFirst();
    }

    // ---------------------------------------------------------------- SVG (화면)

    public String svg(Spec s) {
        List<String> lines = wrap(s.name(), 9, 3);
        boolean sw = s.swatch() != null;
        int startY = sw ? 268 - (lines.size() - 1) * 22 : 250 - (lines.size() - 1) * 22;
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < lines.size(); i++) {
            text.append("<text x=\"200\" y=\"").append(startY + i * 44)
                    .append("\" text-anchor=\"middle\" font-size=\"32\" font-weight=\"700\" fill=\"").append(s.fg()).append("\">")
                    .append(HtmlUtils.htmlEscape(lines.get(i))).append("</text>");
        }
        String figure = sw
                ? """
                  <rect x="110" y="34" width="180" height="160" rx="30" fill="%s"%s/>
                  <rect x="130" y="52" width="64" height="14" rx="7" fill="#fff" opacity="0.35"/>""".formatted(
                        ColorHistogram.hex(s.swatch()), s.lightSwatch() ? " stroke=\"#cfcfcf\" stroke-width=\"3\"" : "")
                : """
                  <circle cx="200" cy="120" r="46" fill="%s" opacity="0.15"/>
                  <rect x="176" y="96" width="48" height="48" rx="12" fill="%s" opacity="0.55"/>""".formatted(s.fg(), s.fg());
        String footer = sw ? "YANGPANG DEMO · " + HtmlUtils.htmlEscape(s.swatchName()) : "YANGPANG DEMO · " + s.index();
        return """
                <svg xmlns="http://www.w3.org/2000/svg" width="400" height="400" viewBox="0 0 400 400" font-family="'Pretendard','Noto Sans KR','Malgun Gothic',sans-serif">
                  <rect width="400" height="400" fill="%s"/>%s
                  %s
                  <text x="200" y="372" text-anchor="middle" font-size="16" fill="%s" opacity="0.6">%s</text>
                </svg>""".formatted(s.bg(), figure, text, s.fg(), footer);
    }

    // ---------------------------------------------------------------- 래스터 (이미지 검색용, SVG와 같은 배치)

    public BufferedImage raster(Spec s, int size) {
        BufferedImage img = new BufferedImage(size, size, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        double k = size / 400.0;
        g.scale(k, k);
        Color fg = Color.decode(s.fg());
        g.setColor(Color.decode(s.bg()));
        g.fillRect(0, 0, 400, 400);
        if (s.swatch() != null) {
            g.setColor(s.swatch());
            g.fillRoundRect(110, 34, 180, 160, 60, 60);
            if (s.lightSwatch()) {
                g.setColor(new Color(0xCF, 0xCF, 0xCF));
                g.setStroke(new BasicStroke(3));
                g.drawRoundRect(110, 34, 180, 160, 60, 60);
            }
            g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.35f));
            g.setColor(Color.WHITE);
            g.fillRoundRect(130, 52, 64, 14, 14, 14);
        } else {
            g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.15f));
            g.setColor(fg);
            g.fillOval(154, 74, 92, 92);
            g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.55f));
            g.fillRoundRect(176, 96, 48, 48, 24, 24);
        }
        g.setComposite(AlphaComposite.SrcOver);
        g.setColor(fg);
        g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 32));
        FontMetrics fm = g.getFontMetrics();
        List<String> lines = wrap(s.name(), 9, 3);
        int startY = s.swatch() != null ? 268 - (lines.size() - 1) * 22 : 250 - (lines.size() - 1) * 22;
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            g.drawString(line, 200 - fm.stringWidth(line) / 2, startY + i * 44);
        }
        g.dispose();
        return img;
    }

    /** 공백 기준으로 한 줄 최대 글자 수에 맞춰 나눈다 */
    static List<String> wrap(String text, int maxChars, int maxLines) {
        List<String> lines = new ArrayList<>();
        StringBuilder line = new StringBuilder();
        for (String word : text.split(" ")) {
            if (!line.isEmpty() && line.length() + 1 + word.length() > maxChars) {
                lines.add(line.toString());
                line = new StringBuilder();
            }
            if (!line.isEmpty()) line.append(' ');
            line.append(word);
        }
        if (!line.isEmpty()) lines.add(line.toString());
        return lines.size() > maxLines ? lines.subList(0, maxLines) : lines;
    }
}
