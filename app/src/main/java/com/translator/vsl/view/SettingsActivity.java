package com.translator.vsl.view;

import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.translator.vsl.R;

public class SettingsActivity extends AppCompatActivity {

    private EditText inputIp;
    private Button btnSaveIp, btnGoToUpload;
    private Switch switchQuality;
    private TextView txtQuality;
    private SharedPreferences sharedPreferences;
    private static final String KEY_VIDEO_QUALITY = "video_quality"; // "SD" or "HD"

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        sharedPreferences = getSharedPreferences("AppPrefs", MODE_PRIVATE);

        inputIp = findViewById(R.id.inputIp);
        btnSaveIp = findViewById(R.id.btnSaveIp);
        btnGoToUpload = findViewById(R.id.btnGoToUpload);

        switchQuality = findViewById(R.id.switchQuality);
        txtQuality = findViewById(R.id.txtQuality);

        // Load IP hiện tại
        String currentIp = sharedPreferences.getString("api_ip", "14.224.194.242");
        inputIp.setText(currentIp);

        btnSaveIp.setOnClickListener(v -> {
            String newIp = inputIp.getText().toString().trim();
            if (!newIp.isEmpty()) {
                sharedPreferences.edit().putString("api_ip", newIp).apply();
                Toast.makeText(this, "Đã cập nhật IP: " + newIp, Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(this, "Vui lòng nhập địa chỉ IP!", Toast.LENGTH_SHORT).show();
            }
        });

        // Load video quality
        boolean isHD = sharedPreferences.getString(KEY_VIDEO_QUALITY, "HD").equals("HD");
        switchQuality.setChecked(isHD);
        txtQuality.setText(isHD ? "HD" : "SD");

        switchQuality.setOnCheckedChangeListener((buttonView, isChecked) -> {
            sharedPreferences.edit().putString(KEY_VIDEO_QUALITY, isChecked ? "HD" : "SD").apply();
            txtQuality.setText(isChecked ? "HD" : "SD");
        });

        // Nút chuyển sang UploadActivity
        btnGoToUpload.setOnClickListener(v -> {
            new androidx.appcompat.app.AlertDialog.Builder(SettingsActivity.this)
                    .setTitle("Bạn đã biết cách quay dữ liệu để đóng góp cho ứng dụng chưa?")
                    .setCancelable(false)
                    .setPositiveButton("Đã biết", (dialog, which) -> {
                        // Nếu đã biết thì đi UploadActivity
                        startActivity(new Intent(SettingsActivity.this, UploadActivity.class));
                        overridePendingTransition(R.anim.slide_in_right, R.anim.slide_out_left);
                    })
                    .setNegativeButton("Không biết", (dialog, which) -> {
                        // Nếu chưa biết thì mở HelpUploadActivity
                        startActivity(new Intent(SettingsActivity.this, HelpUploadActivity.class));
                        overridePendingTransition(R.anim.slide_in_right, R.anim.slide_out_left);
                    })
                    .show();
        });
    }
}
