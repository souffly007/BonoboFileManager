package fr.bonobo.filemanager.saf;

/** Separate synthetic application used to exercise a real foreground switch. */
public class TestBackgroundActivity extends android.app.Activity {
    @Override public void onCreate(android.os.Bundle state) {
        super.onCreate(state);
        if (getIntent().getBooleanExtra("return", false)) { finish(); return; }
        android.widget.TextView text = new android.widget.TextView(this);
        text.setText("Application synthétique : changement de stockage");
        setContentView(text);
    }
    @Override public void onNewIntent(android.content.Intent intent) {
        super.onNewIntent(intent);
        if (intent.getBooleanExtra("return", false)) finish();
    }
}
