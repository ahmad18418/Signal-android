package com.signalalert.app

import android.os.Bundle
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.switchmaterial.SwitchMaterial

class SettingsActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        val prefs = Prefs(this)
        val url = findViewById<EditText>(R.id.serverUrl)
        val bio = findViewById<SwitchMaterial>(R.id.switchBiometric)
        url.setText(prefs.serverUrl)
        bio.isChecked = prefs.biometricEnabled
        findViewById<TextView>(R.id.appVersion).text =
            "نسخه اپ: v${BuildConfig.VERSION_NAME} (کد ${BuildConfig.VERSION_CODE})"
        findViewById<MaterialButton>(R.id.btnSave).setOnClickListener {
            val v = url.text?.toString()?.trim().orEmpty()
            if (v.isEmpty() || !(v.startsWith("http://") || v.startsWith("https://"))) {
                Toast.makeText(this, "آدرس باید با http:// یا https:// شروع شود", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            prefs.serverUrl = v
            prefs.biometricEnabled = bio.isChecked
            Toast.makeText(this, "ذخیره شد", Toast.LENGTH_SHORT).show()
            finish()
        }
    }
}
