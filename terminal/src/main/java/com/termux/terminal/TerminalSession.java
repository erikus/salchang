package com.termux.terminal;

import java.util.UUID;

/**
 * A terminal session backed by a remote pane instead of a local pty/process.
 * <p>
 * Compared to upstream termux-app, this class contains no JNI, pty, or subprocess code. Output from the
 * remote pane is fed in through {@link #appendOutput(byte[], int, int)}, and bytes the terminal wants to
 * send back (user input and emulator responses) are handed to a {@link TerminalSink}.
 * <p>
 * Terminal emulation begins when the size is made known by a call to {@link #updateSize(int, int, int, int)}
 * (or {@link #initializeEmulator(int, int, int, int)} directly).
 * <p>
 * Threading: upstream marshals all pane output through a {@link android.os.Handler} onto the main thread.
 * This class does no such marshalling. {@link #appendOutput(byte[], int, int)} and every other method that
 * touches the emulator MUST be called on the main thread; the application is responsible for that.
 * <p>
 * NOTE: The terminal session may outlive the EmulatorView, so be careful with callbacks!
 */
public final class TerminalSession extends TerminalOutput {

    /** Number of scrollback rows kept by the emulator's transcript. */
    public static final int DEFAULT_TRANSCRIPT_ROWS = 2000;

    /** Size of {@link #mUtf8InputBuffer}: one optional ESC byte plus at most four UTF-8 bytes. */
    private static final int UTF8_INPUT_BUFFER_SIZE = 5;

    /** Cell pixel sizes assumed until a view reports real ones; only used for sixel/image scaling. */
    private static final int DEFAULT_CELL_WIDTH_PIXELS = 8;
    private static final int DEFAULT_CELL_HEIGHT_PIXELS = 16;

    public final String mHandle = UUID.randomUUID().toString();

    TerminalEmulator mEmulator;

    /** Buffer to write translate code points into utf8 before writing to the sink. */
    private final byte[] mUtf8InputBuffer = new byte[UTF8_INPUT_BUFFER_SIZE];

    /** Callback which gets notified when a session finishes or changes title. */
    TerminalSessionClient mClient;

    /** Receives bytes that should be forwarded to the remote pane. */
    private final TerminalSink mSink;

    /** Whether the session is considered running. Set by the application via {@link #setRunning(boolean)}. */
    private boolean mRunning = true;

    /** Set by the application for user identification of session, not by terminal. */
    public String mSessionName;

    /**
     * When both are > 0, {@link #updateSize(int, int, int, int)} ignores the columns/rows it is given (which
     * {@link com.termux.view.TerminalView} derives from its own pixel size) and keeps the emulator at this size,
     * which the application takes from the remote pane. 0 means "follow the view" (upstream behaviour).
     */
    private int mFixedColumns = 0;
    private int mFixedRows = 0;

    /** Cell pixel sizes last passed to {@link #updateSize(int, int, int, int)}; reused by {@link #setFixedSize(int, int)}. */
    private int mCellWidthPixels = DEFAULT_CELL_WIDTH_PIXELS;
    private int mCellHeightPixels = DEFAULT_CELL_HEIGHT_PIXELS;

    public TerminalSession(TerminalSessionClient client, TerminalSink sink) {
        this.mClient = client;
        this.mSink = sink;
    }

    /**
     * @param client The {@link TerminalSessionClient} interface implementation to allow
     *               for communication between {@link TerminalSession} and its client.
     */
    public void updateTerminalSessionClient(TerminalSessionClient client) {
        mClient = client;

        if (mEmulator != null)
            mEmulator.updateTerminalSessionClient(client);
    }

    /**
     * Resize (reflow) the emulator, or initialize it if this is the first size report.
     * <p>
     * If a fixed size was set with {@link #setFixedSize(int, int)}, {@code columns} and {@code rows} are ignored
     * and the fixed size is used instead; the cell pixel sizes are always applied.
     */
    public void updateSize(int columns, int rows, int cellWidthPixels, int cellHeightPixels) {
        mCellWidthPixels = cellWidthPixels;
        mCellHeightPixels = cellHeightPixels;
        if (mFixedColumns > 0 && mFixedRows > 0) {
            columns = mFixedColumns;
            rows = mFixedRows;
        }
        if (mEmulator == null) {
            initializeEmulator(columns, rows, cellWidthPixels, cellHeightPixels);
        } else {
            mEmulator.resize(columns, rows, cellWidthPixels, cellHeightPixels);
            notifyScreenUpdate();
        }
    }

    /**
     * Pin the emulator grid to {@code columns} x {@code rows} (the size the remote pane has) regardless of the
     * size the view would derive from its pixel dimensions. Initializes or resizes the emulator right away using
     * the last known cell pixel sizes. Pass 0, 0 to go back to following the view.
     * <p>
     * Must be called on the main thread like every other method touching the emulator.
     */
    public void setFixedSize(int columns, int rows) {
        if (columns < 0 || rows < 0) throw new IllegalArgumentException("columns=" + columns + ", rows=" + rows);
        mFixedColumns = columns;
        mFixedRows = rows;
        if (columns > 0 && rows > 0) {
            updateSize(columns, rows, mCellWidthPixels, mCellHeightPixels);
        }
    }

    /** Fixed column count set with {@link #setFixedSize(int, int)}, or 0 when following the view. */
    public int getFixedColumns() {
        return mFixedColumns;
    }

    /** Fixed row count set with {@link #setFixedSize(int, int)}, or 0 when following the view. */
    public int getFixedRows() {
        return mFixedRows;
    }

    /** The terminal title as set through escape sequences or null if none set. */
    public String getTitle() {
        return (mEmulator == null) ? null : mEmulator.getTitle();
    }

    /**
     * Set the terminal emulator's window size and start terminal emulation.
     *
     * @param columns The number of columns in the terminal window.
     * @param rows    The number of rows in the terminal window.
     */
    public void initializeEmulator(int columns, int rows, int cellWidthPixels, int cellHeightPixels) {
        mEmulator = new TerminalEmulator(this, columns, rows, cellWidthPixels, cellHeightPixels, DEFAULT_TRANSCRIPT_ROWS, mClient);
    }

    /**
     * Feed bytes received from the remote pane into the emulator and notify the client that the screen changed.
     * <p>
     * Must be called on the main thread, after the emulator has been initialized.
     */
    public void appendOutput(byte[] data, int offset, int count) {
        if (mEmulator == null || count <= 0) return;
        if (offset == 0) {
            mEmulator.append(data, count);
        } else {
            byte[] slice = new byte[count];
            System.arraycopy(data, offset, slice, 0, count);
            mEmulator.append(slice, count);
        }
        notifyScreenUpdate();
    }

    /** Write data destined for the remote pane to the sink. */
    @Override
    public void write(byte[] data, int offset, int count) {
        if (isRunning()) mSink.write(data, offset, count);
    }

    /** Write the Unicode code point to the terminal encoded in UTF-8. */
    public void writeCodePoint(boolean prependEscape, int codePoint) {
        if (codePoint > 1114111 || (codePoint >= 0xD800 && codePoint <= 0xDFFF)) {
            // 1114111 (= 2**16 + 1024**2 - 1) is the highest code point, [0xD800,0xDFFF] is the surrogate range.
            throw new IllegalArgumentException("Invalid code point: " + codePoint);
        }

        int bufferPosition = 0;
        if (prependEscape) mUtf8InputBuffer[bufferPosition++] = 27;

        if (codePoint <= /* 7 bits */0b1111111) {
            mUtf8InputBuffer[bufferPosition++] = (byte) codePoint;
        } else if (codePoint <= /* 11 bits */0b11111111111) {
            /* 110xxxxx leading byte with leading 5 bits */
            mUtf8InputBuffer[bufferPosition++] = (byte) (0b11000000 | (codePoint >> 6));
            /* 10xxxxxx continuation byte with following 6 bits */
            mUtf8InputBuffer[bufferPosition++] = (byte) (0b10000000 | (codePoint & 0b111111));
        } else if (codePoint <= /* 16 bits */0b1111111111111111) {
            /* 1110xxxx leading byte with leading 4 bits */
            mUtf8InputBuffer[bufferPosition++] = (byte) (0b11100000 | (codePoint >> 12));
            /* 10xxxxxx continuation byte with following 6 bits */
            mUtf8InputBuffer[bufferPosition++] = (byte) (0b10000000 | ((codePoint >> 6) & 0b111111));
            /* 10xxxxxx continuation byte with following 6 bits */
            mUtf8InputBuffer[bufferPosition++] = (byte) (0b10000000 | (codePoint & 0b111111));
        } else { /* We have checked codePoint <= 1114111 above, so we have max 21 bits = 0b111111111111111111111 */
            /* 11110xxx leading byte with leading 3 bits */
            mUtf8InputBuffer[bufferPosition++] = (byte) (0b11110000 | (codePoint >> 18));
            /* 10xxxxxx continuation byte with following 6 bits */
            mUtf8InputBuffer[bufferPosition++] = (byte) (0b10000000 | ((codePoint >> 12) & 0b111111));
            /* 10xxxxxx continuation byte with following 6 bits */
            mUtf8InputBuffer[bufferPosition++] = (byte) (0b10000000 | ((codePoint >> 6) & 0b111111));
            /* 10xxxxxx continuation byte with following 6 bits */
            mUtf8InputBuffer[bufferPosition++] = (byte) (0b10000000 | (codePoint & 0b111111));
        }
        write(mUtf8InputBuffer, 0, bufferPosition);
    }

    public TerminalEmulator getEmulator() {
        return mEmulator;
    }

    /** Notify the {@link #mClient} that the screen has changed. */
    protected void notifyScreenUpdate() {
        mClient.onTextChanged(this);
    }

    /** Reset state for terminal emulator state. */
    public void reset() {
        mEmulator.reset();
        notifyScreenUpdate();
    }

    /** Mark the session as running or not. The application sets this from the remote pane's lifecycle. */
    public synchronized void setRunning(boolean running) {
        mRunning = running;
    }

    /** Finish this terminal session: mark it as not running and notify the client. */
    public void finishIfRunning() {
        boolean wasRunning;
        synchronized (this) {
            wasRunning = mRunning;
            mRunning = false;
        }
        if (wasRunning) mClient.onSessionFinished(this);
    }

    @Override
    public void titleChanged(String oldTitle, String newTitle) {
        mClient.onTitleChanged(this);
    }

    public synchronized boolean isRunning() {
        return mRunning;
    }

    /** There is no local process; always 0. */
    public int getExitStatus() {
        return 0;
    }

    @Override
    public void onCopyTextToClipboard(String text) {
        mClient.onCopyTextToClipboard(this, text);
    }

    @Override
    public void onPasteTextFromClipboard() {
        mClient.onPasteTextFromClipboard(this);
    }

    @Override
    public void onBell() {
        mClient.onBell(this);
    }

    @Override
    public void onColorsChanged() {
        mClient.onColorsChanged(this);
    }

    /** There is no local process; always -1. */
    public int getPid() {
        return -1;
    }

    /** There is no local process; always null. */
    public String getCwd() {
        return null;
    }

}
