package com.homehub;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

/** Mistakes in the dashboard's script that a browser doesn't report. */
class PageScriptTests {

    private static final Pattern FUNCTION = Pattern.compile("(?m)^\\s*(?:async\\s+)?function\\s+([A-Za-z_$][\\w$]*)\\s*\\(");

    /**
     * Two functions with the same name: the later one silently replaces the earlier everywhere. Since #171 the
     * controls' setStatus(c, status) replaced the header's setStatus(text, state, clock), and the header said
     * "Connecting…" for ever (#182).
     */
    @Test
    void everyFunctionNameIsUsedOnce() throws Exception {
        String page = new ClassPathResource("static/index.html").getContentAsString(StandardCharsets.UTF_8);
        Map<String, Integer> count = new LinkedHashMap<>();
        Matcher m = FUNCTION.matcher(page);
        while (m.find()) count.merge(m.group(1), 1, Integer::sum);

        assertThat(count).as("functions in index.html").hasSizeGreaterThan(50);
        assertThat(count.entrySet().stream().filter(e -> e.getValue() > 1).map(Map.Entry::getKey).toList())
                .as("declared more than once").isEmpty();
    }

    @Test
    void theHeaderSaysLiveOnceTheStreamIsOpen() throws Exception {
        String page = new ClassPathResource("static/index.html").getContentAsString(StandardCharsets.UTF_8);
        assertThat(page).contains("function setStatus(text, state, clock) {",
                "if (!paused && stream && stream.readyState === EventSource.OPEN) setStatus('Live', ''");
    }
}
