package com.translator.vsl.view;

import android.os.Bundle;
import android.webkit.WebChromeClient;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.translator.vsl.R;

public class HelpUploadActivity extends AppCompatActivity {

    private WebView mWebView;
    private ProgressBar mProgressBar;
    private TextView tvHelpTitle;

    // Bạn điền link Youtube hoặc link hướng dẫn ở đây
    private final String helpUrl = "https://youtube.com/shorts/uHqUbqyK0po?si=TZSJI4JJolHm9I8E";  // <--- Để trống hoặc thay link

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_help_upload);

        mProgressBar = findViewById(R.id.loading_progressbar);
        mWebView = findViewById(R.id.webviewHelp);
        tvHelpTitle = findViewById(R.id.tvHelpTitle);

        mWebView.loadUrl(helpUrl);

        mWebView.setWebViewClient(new WebViewClient());
        mWebView.getSettings().setJavaScriptEnabled(true);

        mWebView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView view, int newProgress) {
                mProgressBar.setProgress(newProgress);
                if (newProgress == 100) {
                    mProgressBar.setVisibility(ProgressBar.GONE);
                } else {
                    mProgressBar.setVisibility(ProgressBar.VISIBLE);
                }
            }
        });
    }

    @Override
    public void onBackPressed() {
        if (mWebView.canGoBack()) {
            mWebView.goBack();
        } else {
            super.onBackPressed();
        }
    }
}
