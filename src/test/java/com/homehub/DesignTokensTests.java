package com.homehub;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

/** The dashboard's design tokens (#58): every color comes from them, and text stays readable in both themes. */
class DesignTokensTests {

    private static final Pattern TOKEN = Pattern.compile("--([a-z0-9-]+):\\s*([^;]+);");
    private static final Pattern COLOR_LITERAL = Pattern.compile("#[0-9a-fA-F]{3,8}\\b|rgba?\\(|hsla?\\(");

    private static String page() throws Exception {
        return new ClassPathResource("static/index.html").getContentAsString(StandardCharsets.UTF_8);
    }

    private static String style(String page) {
        return page.substring(page.indexOf("<style>"), page.indexOf("</style>"));
    }

    /** The body of the first rule that starts with {@code selector}. */
    private static String block(String css, String selector) {
        int start = css.indexOf(selector + " {");
        assertThat(start).as(selector).isNotNegative();
        return css.substring(start, css.indexOf('}', start) + 1);
    }

    private static Map<String, String> tokens(String block) {
        Map<String, String> tokens = new LinkedHashMap<>();
        Matcher m = TOKEN.matcher(block);
        while (m.find()) tokens.put(m.group(1), m.group(2).trim());
        return tokens;
    }

    private static Map<String, String> light() throws Exception {
        return tokens(block(style(page()), ":root"));
    }

    private static Map<String, String> dark() throws Exception {
        Map<String, String> dark = new LinkedHashMap<>(light());   // dark only overrides colors
        dark.putAll(tokens(block(style(page()), ":root[data-theme=\"dark\"]")));
        return dark;
    }

    @Test
    void bothThemesUseTheAgreedPalette() throws Exception {
        assertThat(light()).containsEntry("bg", "#F5F6F3").containsEntry("surface", "#FFFFFF")
                .containsEntry("text", "#16201C").containsEntry("muted", "#5B6661")
                .containsEntry("ok", "#1F6E53").containsEntry("warn", "#A35A06").containsEntry("info", "#2F5FA8");
        assertThat(dark()).containsEntry("bg", "#0F1412").containsEntry("surface", "#18201C")
                .containsEntry("text", "#E8EDEA").containsEntry("muted", "#9AA59F")
                .containsEntry("ok", "#4FBF94").containsEntry("warn", "#E2A04A").containsEntry("info", "#8AAEE8");
    }

    @Test
    void darkThemeDefinesEveryColorTheLightThemeHas() throws Exception {
        Map<String, String> darkOnly = tokens(block(style(page()), ":root[data-theme=\"dark\"]"));
        List<String> colors = light().entrySet().stream()
                .filter(e -> COLOR_LITERAL.matcher(e.getValue()).find())
                .filter(e -> !e.getKey().startsWith("preview-"))   // Settings previews show each theme as it is
                .map(Map.Entry::getKey).toList();
        assertThat(darkOnly.keySet()).containsAll(colors);
    }

    /** Text against the backgrounds it is shown on: at least 4.5:1 (WCAG AA), in both themes. */
    @Test
    void textIsReadableInBothThemes() throws Exception {
        String[][] pairs = {
                {"text", "bg"}, {"text", "surface"}, {"text", "surface-2"}, {"text", "new"},
                {"muted", "bg"}, {"muted", "surface"}, {"muted", "surface-2"},
                {"accent", "bg"}, {"accent", "surface"}, {"on-accent", "accent"},
                {"ok", "ok-soft"}, {"ok", "surface"}, {"warn", "warn-soft"}, {"warn", "surface"},
                {"info", "info-soft"}, {"info", "surface"}, {"idle", "idle-soft"},
                {"bad", "bad-soft"}, {"bad", "surface"}, {"on-bad", "bad"},
        };
        List<String> failures = new ArrayList<>();
        for (Map.Entry<String, Map<String, String>> theme : Map.of("light", light(), "dark", dark()).entrySet()) {
            for (String[] pair : pairs) {
                double ratio = contrast(theme.getValue().get(pair[0]), theme.getValue().get(pair[1]));
                if (ratio < 4.5) failures.add("%s: %s on %s is %.2f:1".formatted(theme.getKey(), pair[0], pair[1], ratio));
            }
        }
        assertThat(failures).isEmpty();
    }

    @Test
    void everyColorComesFromAToken() throws Exception {
        String css = style(page());
        String outsideTokens = css.replace(block(css, ":root"), "").replace(block(css, ":root[data-theme=\"dark\"]"), "");
        assertThat(outsideTokens.lines().filter(l -> COLOR_LITERAL.matcher(l).find())).isEmpty();
    }

    @Test
    void themeIsSetBeforeTheFirstPaintAndFontsComeFromKudil() throws Exception {
        String page = page();
        String head = page.substring(0, page.indexOf("</head>"));
        assertThat(head.indexOf("document.documentElement.dataset.theme")).isPositive().isLessThan(head.indexOf("<style>"));
        assertThat(head).contains("url(\"fonts/inter-latin-wght-normal.woff2\")",
                "url(\"fonts/space-grotesk-latin-wght-normal.woff2\")");
        // Nothing is loaded from the internet (the SVG namespace in the icon is a name, not a download).
        assertThat(page).doesNotContain("fonts.googleapis", "fonts.gstatic")
                .doesNotContainPattern("(href|src)=[\"']?https?://")
                .doesNotContainPattern("url\\([\"']?https?://");
    }

    private static double contrast(String a, String b) {
        double la = luminance(a), lb = luminance(b);
        return (Math.max(la, lb) + 0.05) / (Math.min(la, lb) + 0.05);
    }

    private static double luminance(String hex) {
        assertThat(hex).matches("#[0-9a-fA-F]{6}");
        double[] c = new double[3];
        for (int i = 0; i < 3; i++) {
            double v = Integer.parseInt(hex.substring(1 + 2 * i, 3 + 2 * i), 16) / 255.0;
            c[i] = v <= 0.04045 ? v / 12.92 : Math.pow((v + 0.055) / 1.055, 2.4);
        }
        return 0.2126 * c[0] + 0.7152 * c[1] + 0.0722 * c[2];
    }
}
