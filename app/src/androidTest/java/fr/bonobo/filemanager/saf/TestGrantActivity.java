package fr.bonobo.filemanager.saf;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.provider.DocumentsContract;

/** Runs independently of the target APK, including its Kotlin runtime. */
public class TestGrantActivity extends Activity {
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        if (getIntent().getBooleanExtra("revoke", false)) {
            revokeUriPermission(DocumentsContract.buildTreeDocumentUri("fr.bonobo.filemanager.test.documents", "revocable"),
                Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            finish(); return;
        }
        for (String id : new String[]{"root", "opaque", "readonly", "missing", "denied", "revocable", "unavailable"}) {
            grantUriPermission("fr.bonobo.filemanager.adaptive",
                DocumentsContract.buildTreeDocumentUri("fr.bonobo.filemanager.test.documents", id),
                Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION |
                    Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
        }
        finish();
    }
}
