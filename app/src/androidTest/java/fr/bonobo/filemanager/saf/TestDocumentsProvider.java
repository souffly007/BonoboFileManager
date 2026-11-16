package fr.bonobo.filemanager.saf;

import android.database.Cursor;
import android.database.MatrixCursor;
import android.os.CancellationSignal;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsProvider;
import android.provider.DocumentsContract.Document;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;

/** Synthetic provider in the test APK, never shipped with Bonobo. */
public class TestDocumentsProvider extends DocumentsProvider {
    private File root() {
        File f = new File(getContext().getCacheDir(), "saf-fixture"); f.mkdirs();
        try { return f.getCanonicalFile(); }
        catch (IOException e) { throw new IllegalStateException(e); }
    }
    @Override public boolean onCreate() { return true; }
    private File file(String id) throws FileNotFoundException {
        if (id.equals("denied")) throw new SecurityException("Permission révoquée synthétique");
        if (id.equals("missing")) throw new FileNotFoundException("Clé retirée");
        if (id.equals("root") || id.equals("opaque") || id.equals("readonly") || id.equals("revocable") || id.equals("unavailable")) return root();
        try {
            String relative = id.startsWith("@") ? new String(android.util.Base64.decode(id.substring(1), android.util.Base64.URL_SAFE | android.util.Base64.NO_WRAP), java.nio.charset.StandardCharsets.UTF_8) : id;
            File f = new File(root(), relative).getCanonicalFile();
            if (!f.toPath().startsWith(root().getCanonicalFile().toPath())) throw new FileNotFoundException("Identifiant invalide");
            return f;
        } catch (IOException e) { throw new FileNotFoundException(e.getMessage()); }
    }
    private String documentId(String parent, File f) {
        String relative = root().toPath().relativize(f.toPath()).toString();
        return parent.equals("opaque") || parent.startsWith("@") ? "@" + android.util.Base64.encodeToString(
            relative.getBytes(java.nio.charset.StandardCharsets.UTF_8), android.util.Base64.URL_SAFE | android.util.Base64.NO_WRAP) : relative;
    }
    private static final String[] COLUMNS = {Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME,
        Document.COLUMN_MIME_TYPE, Document.COLUMN_SIZE, Document.COLUMN_LAST_MODIFIED, Document.COLUMN_FLAGS};
    private void row(MatrixCursor c, String id, File f) {
        MatrixCursor.RowBuilder row = c.newRow();
        for (String column : c.getColumnNames()) {
            Object value = null;
            switch (column) {
                case Document.COLUMN_DOCUMENT_ID: value = id; break;
                case Document.COLUMN_DISPLAY_NAME: value = f.getName(); break;
                case Document.COLUMN_MIME_TYPE: value = f.isDirectory() ? Document.MIME_TYPE_DIR : "text/plain"; break;
                case Document.COLUMN_SIZE: value = f.length() + (f.getName().equals("truncated.txt") ? 5 : 0); break;
                case Document.COLUMN_LAST_MODIFIED: value = f.lastModified(); break;
                case Document.COLUMN_FLAGS: value = (id.equals("readonly") || f.getName().startsWith("nodelete")) ? 0 : Document.FLAG_SUPPORTS_WRITE |
                    Document.FLAG_SUPPORTS_DELETE | Document.FLAG_SUPPORTS_RENAME | Document.FLAG_DIR_SUPPORTS_CREATE; break;
            }
            row.add(value);
        }
    }
    @Override public Cursor queryRoots(String[] projection) { return new MatrixCursor(projection != null ? projection : new String[]{"root_id"}); }
    @Override public Cursor queryDocument(String id, String[] projection) throws FileNotFoundException {
        MatrixCursor c = new MatrixCursor(projection != null ? projection : COLUMNS);
        File f = file(id); if (!f.exists()) throw new FileNotFoundException(); row(c, id, f); return c;
    }
    @Override public Cursor queryChildDocuments(String id, String[] projection, String order) throws FileNotFoundException {
        if (id.equals("unavailable")) return null;
        MatrixCursor c = new MatrixCursor(projection != null ? projection : COLUMNS);
        File[] children = file(id).listFiles(); if (children == null) throw new FileNotFoundException();
        for (File f : children) row(c, documentId(id, f), f);
        return c;
    }
    @Override public ParcelFileDescriptor openDocument(String id, String mode, CancellationSignal signal) throws FileNotFoundException {
        if (!mode.contains("w") && file(id).getName().endsWith("unreadable.txt")) throw new FileNotFoundException("Document inaccessible après écriture");
        if (mode.contains("w") && file(id).getName().endsWith("broken-pipe.txt")) {
            try {
                final ParcelFileDescriptor[] pipe = ParcelFileDescriptor.createReliablePipe();
                new Thread(() -> {
                    try {
                        new ParcelFileDescriptor.AutoCloseInputStream(pipe[0]).read();
                        pipe[0].closeWithError("Écriture interrompue synthétique");
                    } catch (IOException ignored) { }
                }, "synthetic-write-error").start();
                return pipe[1];
            } catch (IOException e) { throw new FileNotFoundException(e.getMessage()); }
        }
        if (mode.contains("w") && file(id).getName().endsWith("discard.txt")) {
            // Accept all writes, but lose their content when the descriptor is closed.
            return ParcelFileDescriptor.open(new File(getContext().getCacheDir(), "discard-sink"),
                ParcelFileDescriptor.MODE_CREATE | ParcelFileDescriptor.MODE_WRITE_ONLY | ParcelFileDescriptor.MODE_TRUNCATE);
        }
        if (mode.contains("w") && file(id).getName().endsWith("fail.txt")) throw new FileNotFoundException("Écriture refusée");
        if (!mode.contains("w") && file(id).getName().equals("slow.txt")) {
            try {
                final ParcelFileDescriptor[] pipe = ParcelFileDescriptor.createReliablePipe();
                final File source = file(id);
                new Thread(() -> {
                    try (java.io.InputStream input = new java.io.FileInputStream(source);
                         java.io.OutputStream output = new ParcelFileDescriptor.AutoCloseOutputStream(pipe[1])) {
                        byte[] buffer = new byte[1024]; int count;
                        while ((count = input.read(buffer)) >= 0) {
                            output.write(buffer, 0, count); Thread.sleep(10);
                        }
                    } catch (Exception ignored) { }
                }, "synthetic-slow-read").start();
                return pipe[0];
            } catch (IOException e) { throw new FileNotFoundException(e.getMessage()); }
        }
        return ParcelFileDescriptor.open(file(id), ParcelFileDescriptor.parseMode(mode));
    }
    @Override public synchronized String createDocument(String parent, String mime, String name) throws FileNotFoundException {
        if (parent.equals("readonly")) throw new SecurityException("Lecture seule");
        if (name.equals("wrong-id.txt")) return parent + "/existing.txt";
        if (name.equals("phantom.txt")) return parent + "/phantom.txt";
        if (name.equals("publication-refused.txt")) throw new FileNotFoundException("Publication synthétique refusée");
        File f = new File(file(parent), name);
        try {
            if (!(mime.equals(Document.MIME_TYPE_DIR) ? f.mkdir() : f.createNewFile())) throw new IOException("Création refusée");
            return documentId(parent, f);
        } catch (IOException e) { throw new FileNotFoundException(e.getMessage()); }
    }
    private boolean deleteTree(File f) {
        File[] children = f.listFiles();
        if (children != null) for (File child : children) if (!deleteTree(child)) return false;
        return f.delete();
    }
    @Override public void deleteDocument(String id) throws FileNotFoundException {
        if (file(id).getName().startsWith("delete-noeffect")) return;
        if (file(id).getName().startsWith("delete-partial-folder")) {
            File[] children = file(id).listFiles();
            if (children != null && children.length > 0) deleteTree(children[0]);
            throw new FileNotFoundException("Suppression partielle synthétique");
        }
        if (file(id).getName().startsWith("delete-error")) throw new SecurityException("Suppression synthétique refusée");
        if (!deleteTree(file(id))) throw new FileNotFoundException("Suppression refusée");
    }
    @Override public synchronized String renameDocument(String id, String name) throws FileNotFoundException {
        File f = file(id), dest = new File(f.getParentFile(), name);
        if (name.equals("rename-refused.txt")) throw new FileNotFoundException("Renommage synthétique refusé");
        if (dest.exists() || !f.renameTo(dest)) throw new FileNotFoundException("Renommage refusé");
        return documentId(id, dest);
    }
    @Override public boolean isChildDocument(String parent, String id) {
        try { return !file(id).equals(file(parent)) && file(id).toPath().startsWith(file(parent).toPath()); }
        catch (FileNotFoundException e) { return false; }
    }
}
