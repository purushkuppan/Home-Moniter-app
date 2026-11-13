package com.homehub;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * Control feedback (#114): risky changes are asked about in Kudil's own sheet, normal ones happen at once with an
 * Undo, and every change shows Sending…, then Done once the device reports it, or that it didn't respond.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.url=jdbc:sqlite:target/test-data/control-feedback.db",
        "homehub.connectors.enabled=false"
})
class ControlFeedbackPageTests {

    @LocalServerPort int port;

    private final HttpClient http = HttpClient.newHttpClient();

    private String page() throws Exception {
        HttpResponse<String> response = http.send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/")).build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        return response.body().replace("\r\n", "\n");
    }

    /** The script that shows and changes devices, from the device controls to the next section. */
    private static String controlsCode(String page) {
        int start = page.indexOf("// ---------------------------------------------------------------- device controls");
        int end = page.indexOf("// ---------------------------------------------------------------- can't reach home");
        assertThat(start).isPositive();
        assertThat(end).isGreaterThan(start);
        return page.substring(start, end);
    }

    @Test
    void controlsNeverUseTheBrowsersConfirmBox() throws Exception {
        String code = controlsCode(page());
        assertThat(code).doesNotContain("confirm(");
        // Every control goes through one path: a question only when risky, then the PIN, then the command.
        assertThat(code).contains("""
                async function changeControl(c, value) {
                  if (isSending(c) || starting.has(c.id)) return;   // a second tap never sends twice
                  starting.add(c.id);
                  const go = (!isRisky(c, value) || await askRisky(c, value)) && await ensureUnlocked();
                  starting.delete(c.id);
                  if (go) sendCommand(c, value, undoValue(c));
                }""");
        assertThat(code).contains("() => changeControl(c, next)", "() => changeControl(c, option)",
                "changeControl(c, select.value);", "() => changeControl(c, '')");
    }

    @Test
    void riskyActionsAskInKudilsOwnSheetWithTheConsequence() throws Exception {
        String page = page();
        // A sheet on the design tokens: Cancel first (and focused), the action itself as the confirm button.
        assertThat(page).contains("<dialog id=\"confirmSheet\" class=\"sheet confirm-sheet\" aria-labelledby=\"confirmTitle\""
                + " aria-describedby=\"confirmText\">");
        String sheet = page.substring(page.indexOf("<dialog id=\"confirmSheet\""));
        sheet = sheet.substring(0, sheet.indexOf("</dialog>"));
        assertThat(sheet).containsSubsequence("<form method=\"dialog\">", "<h3 id=\"confirmTitle\">",
                "<p class=\"confirm-text\" id=\"confirmText\">",
                "<button class=\"btn\" type=\"submit\" value=\"cancel\" autofocus>Cancel</button>",
                "<button class=\"btn primary\" type=\"submit\" value=\"ok\" id=\"confirmOk\">");
        // Readable with large text, and every button a full touch target.
        assertThat(page).contains(".confirm-sheet .confirm-text { margin: 8px 0 0; color: var(--muted); font-size: 1rem;",
                ".confirm-sheet menu .btn { min-height: var(--tap);");

        String code = controlsCode(page);
        // One place decides what is risky: starting or stopping a washer or dryer, and pressing a button.
        assertThat(code).contains("""
                const RISKY = {
                  cycle: v => v === 'run' || v === 'stop',   // starting or stopping a washer or dryer
                  button: () => true                         // a press can't be taken back, e.g. Reset water filter
                };""", """
                function isRisky(c, value) {
                  const risky = RISKY[c.kind];
                  return !!risky && risky(value);
                }""");
        // "Start the washer?" with Start on the button, and only the action button goes ahead.
        assertThat(code).contains("action = CYCLE_VERBS[value];", "title = action + ' the ' + device + '?';",
                "$('confirmOk').textContent = action;", "resolve(sheet.returnValue === 'ok')");
    }

    @Test
    void normalChangesHappenAtOnceAndCanBeUndoneForFiveSeconds() throws Exception {
        String code = controlsCode(page());
        assertThat(code).contains("const UNDO_MS = 5000;",
                "toast(message, false, { label: 'Undo', ms: UNDO_MS, run: () => undoChange(c, undoTo) });");
        // Undo sends the previous value back, but never when going back would itself be risky.
        assertThat(code).contains("if (isRisky(c, v)) return null;", "sendCommand(now, value);");
        // The toast can carry the Undo button.
        assertThat(page()).contains("const b = el('button', 'toast-action', action.label);",
                "action ? action.ms : isError ? 6000 : 3500");
    }

    @Test
    void aChangeShowsSendingThenDoneOnlyWhenTheDeviceReportsIt() throws Exception {
        String code = controlsCode(page());
        // Sending…: the control is disabled, so nothing is sent twice.
        assertThat(code).contains("setStatus(c, { state: 'sending', value });",
                "const disabled = !controlsView.enabled || sending;", "busy ? 'Sending…' : text",
                "action.disabled = sending || !controlsView.enabled");
        // Then Kudil asks every 1.5 s for up to 15 s until the device itself reports the new value.
        assertThat(code).contains("const CONFIRM_POLL_MS = 1500;", "const CONFIRM_TIMEOUT_MS = 15000;",
                "if (c.kind !== 'button' && !await deviceReports(c, value)) {",
                "const res = await fetch('/api/controls', { cache: 'no-store' });");
        // Done with the time, e.g. "Done · On · 9:41 PM"; or it didn't respond, with the reason when the server gave one.
        assertThat(code).contains(
                "text: 'Done · ' + stateText(c, value) + ' · ' + shortClock.format(new Date())",
                "\"Didn't respond. Try again.\"", "'Didn\\'t work. ' + reason");
        // A wrong PIN or too many tries locks the controls again, as before.
        assertThat(code).contains("if (res.status === 401 || res.status === 429 || res.status === 403) {\n"
                + "    setStatus(c, null);\n    lockControls();");
        // The old "refresh after 1.5 s and hope" is gone.
        assertThat(code).doesNotContain("setTimeout(loadControls, 1500)");
    }
}
