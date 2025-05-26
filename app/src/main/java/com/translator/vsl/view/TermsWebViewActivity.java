package com.translator.vsl.view;

import android.os.Bundle;
import android.webkit.WebChromeClient;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.translator.vsl.R;

public class TermsWebViewActivity extends AppCompatActivity {

    private WebView mWebView;
    private ProgressBar mProgressBar;
    private TextView tvHelpTitle;

    // Đường dẫn tới trang điều khoản sử dụng (thay thế bằng link của bạn)
    private final String termsUrl = "https://drive.google.com/file/d/1DsZXTBHUcvy1bIdsQNh8jCIulFXqk8Px/view?usp=sharing";  // <-- Đổi thành link Terms thực tế của bạn

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_terms_webview);

        mProgressBar = findViewById(R.id.loading_progressbar);
        mWebView = findViewById(R.id.webviewHelp);
        tvHelpTitle = findViewById(R.id.tvHelpTitle);

        tvHelpTitle.setText(R.string.terms); // Hoặc để text cứng "Terms of Service"

        mWebView.loadUrl(termsUrl);

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
