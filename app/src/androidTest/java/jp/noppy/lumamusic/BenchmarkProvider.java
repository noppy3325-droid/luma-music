package jp.noppy.lumamusic;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Intent;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

/** Test-only silent WAV provider. Its standalone test-APK process needs no Kotlin runtime. */
public final class BenchmarkProvider extends ContentProvider {
    private final AtomicInteger queries = new AtomicInteger();
    private final AtomicInteger opens = new AtomicInteger();
    private volatile int count = 200;
    private volatile long modified = 1700000000000L;
    private volatile long openDelay = 0L;
    private volatile long sizeExtra = 0L;
    private volatile boolean deny = false;
    private volatile boolean sidecar = false;
    private File wav;

    @Override
    public boolean onCreate() {
        int rate = 44100;
        int data = rate * 4;
        ByteBuffer bytes = ByteBuffer.allocate(44 + data).order(ByteOrder.LITTLE_ENDIAN);
        bytes.put("RIFF".getBytes(StandardCharsets.US_ASCII)).putInt(36 + data)
                .put("WAVEfmt ".getBytes(StandardCharsets.US_ASCII)).putInt(16)
                .putShort((short) 1).putShort((short) 2).putInt(rate).putInt(rate * 4)
                .putShort((short) 4).putShort((short) 16)
                .put("data".getBytes(StandardCharsets.US_ASCII)).putInt(data);
        if (getContext() == null) throw new IllegalStateException("Provider context is missing");
        wav = new File(getContext().getCacheDir(), "benchmark.wav");
        try (FileOutputStream output = new FileOutputStream(wav)) {
            output.write(bytes.array());
        } catch (IOException failure) {
            throw new IllegalStateException("Cannot create synthetic WAV fixture", failure);
        }
        Uri tree = DocumentsContract.buildTreeDocumentUri("jp.noppy.lumamusic.test.bench", "root");
        getContext().grantUriPermission("jp.noppy.lumamusic", tree,
                Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
        return true;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection,
                        String[] selectionArgs, String sortOrder) {
        queries.incrementAndGet();
        if (deny) throw new SecurityException("Synthetic permission loss");
        String[] columns = projection != null ? projection : new String[] {
                "document_id", "_display_name", "mime_type", "flags", "_size", "last_modified"
        };
        MatrixCursor cursor = new MatrixCursor(columns);
        if ("children".equals(uri.getLastPathSegment())) {
            int fileCount = count;
            for (int i = 0; i < fileCount; i++) addDocument(cursor, columns, "f" + i);
            if (sidecar) addDocument(cursor, columns, "sidecar");
        } else {
            String id = uri.getLastPathSegment();
            if (id == null) id = "root";
            if ("root".equals(id) || (sidecar && "sidecar".equals(id)) || isExistingFile(id)) addDocument(cursor, columns, id);
        }
        return cursor;
    }

    private boolean isExistingFile(String id) {
        if (!id.startsWith("f")) return false;
        try {
            int index = Integer.parseInt(id.substring(1));
            return index >= 0 && index < count;
        } catch (NumberFormatException ignored) {
            return false;
        }
    }

    private void addDocument(MatrixCursor cursor, String[] columns, String id) {
        boolean root = "root".equals(id);
        Object[] row = new Object[columns.length];
        for (int i = 0; i < columns.length; i++) {
            switch (columns[i]) {
                case "document_id": row[i] = id; break;
                case "_display_name": row[i] = root ? "Synthetic 200 WAV" : "sidecar".equals(id) ? "._backup.wav" : id + ".wav"; break;
                case "mime_type": row[i] = root ? DocumentsContract.Document.MIME_TYPE_DIR : "audio/wav"; break;
                case "flags": row[i] = 0; break;
                case "_size": row[i] = root ? 0L : wav.length() + sizeExtra; break;
                case "last_modified": row[i] = modified; break;
                default: row[i] = null;
            }
        }
        cursor.addRow(row);
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        opens.incrementAndGet();
        long delay = openDelay;
        if (delay > 0) {
            try {
                Thread.sleep(delay);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                FileNotFoundException failure = new FileNotFoundException("Fixture open interrupted");
                failure.initCause(interrupted);
                throw failure;
            }
        }
        return ParcelFileDescriptor.open(wav, ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override
    public Bundle call(String method, String arg, Bundle extras) {
        Bundle result = new Bundle();
        result.putInt("queries", queries.get());
        result.putInt("opens", opens.get());
        if ("reset".equals(method)) {
            queries.set(0);
            opens.set(0);
        }
        if ("configure".equals(method)) {
            count = extras != null ? extras.getInt("count", 200) : 200;
            modified = extras != null ? extras.getLong("modified", 1700000000000L) : 1700000000000L;
            openDelay = extras != null ? extras.getLong("delay", 0L) : 0L;
            sizeExtra = extras != null ? extras.getLong("sizeExtra", 0L) : 0L;
            deny = extras != null && extras.getBoolean("deny", false);
            sidecar = extras != null && extras.getBoolean("sidecar", false);
        }
        return result;
    }

    @Override public String getType(Uri uri) { return "audio/wav"; }
    @Override public Uri insert(Uri uri, ContentValues values) { return null; }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] args) { return 0; }
    @Override public int delete(Uri uri, String selection, String[] args) { return 0; }
}
