package com.termux.terminal;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import org.junit.Test;

public class TerminalSessionTest {

    private static final int COLUMNS = 20;
    private static final int ROWS = 5;
    private static final int CELL_WIDTH_PX = 8;
    private static final int CELL_HEIGHT_PX = 16;
    private static final int EURO_SIGN_CODE_POINT = 0x20AC; // 3-byte UTF-8 sequence
    private static final byte ESC = 0x1b;

    private static final class NoOpClient implements TerminalSessionClient {
        int textChanged;

        @Override public void onTextChanged(TerminalSession changedSession) { textChanged++; }
        @Override public void onTitleChanged(TerminalSession changedSession) { }
        @Override public void onSessionFinished(TerminalSession finishedSession) { }
        @Override public void onCopyTextToClipboard(TerminalSession session, String text) { }
        @Override public void onPasteTextFromClipboard(TerminalSession session) { }
        @Override public void onBell(TerminalSession session) { }
        @Override public void onColorsChanged(TerminalSession session) { }
        @Override public void onTerminalCursorStateChange(boolean state) { }
        @Override public void setTerminalShellPid(TerminalSession session, int pid) { }
        @Override public Integer getTerminalCursorStyle() { return null; }
        @Override public void logError(String tag, String message) { }
        @Override public void logWarn(String tag, String message) { }
        @Override public void logInfo(String tag, String message) { }
        @Override public void logDebug(String tag, String message) { }
        @Override public void logVerbose(String tag, String message) { }
        @Override public void logStackTraceWithMessage(String tag, String message, Exception e) { }
        @Override public void logStackTrace(String tag, Exception e) { }
    }

    @Test
    public void appendOutputRendersToScreen() {
        NoOpClient client = new NoOpClient();
        ByteArrayOutputStream sunk = new ByteArrayOutputStream();
        TerminalSession session = new TerminalSession(client, (data, offset, count) -> sunk.write(data, offset, count));
        session.initializeEmulator(COLUMNS, ROWS, CELL_WIDTH_PX, CELL_HEIGHT_PX);

        byte[] bytes = "hello\r\n".getBytes(StandardCharsets.UTF_8);
        session.appendOutput(bytes, 0, bytes.length);

        String transcript = session.getEmulator().getScreen().getTranscriptText();
        assertTrue("transcript was: " + transcript, transcript.startsWith("hello"));
        assertEquals(1, client.textChanged);
        assertEquals(0, sunk.size());
    }

    @Test
    public void writeCodePointForwardsUtf8ToSink() {
        ByteArrayOutputStream sunk = new ByteArrayOutputStream();
        TerminalSession session = new TerminalSession(new NoOpClient(), (data, offset, count) -> sunk.write(data, offset, count));
        session.initializeEmulator(COLUMNS, ROWS, CELL_WIDTH_PX, CELL_HEIGHT_PX);

        session.writeCodePoint(false, 'a');
        session.writeCodePoint(true, EURO_SIGN_CODE_POINT);

        String expected = "a" + (char) ESC + new String(Character.toChars(EURO_SIGN_CODE_POINT));
        assertEquals(expected, new String(sunk.toByteArray(), StandardCharsets.UTF_8));
    }

}
