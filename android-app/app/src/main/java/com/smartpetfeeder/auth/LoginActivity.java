package com.smartpetfeeder.auth;

import android.content.Intent;
import android.os.Bundle;
import android.util.Patterns;
import android.text.TextUtils;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.button.MaterialButton;
import com.google.firebase.FirebaseNetworkException;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseAuthInvalidCredentialsException;
import com.smartpetfeeder.MainActivity;
import com.smartpetfeeder.R;

public class LoginActivity extends AppCompatActivity {
    private EditText etEmail;
    private EditText etPassword;
    private MaterialButton btnLogin;
    private TextView tvRegister;
    private ProgressBar progressLogin;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_login);

        etEmail = findViewById(R.id.etLoginEmail);
        etPassword = findViewById(R.id.etLoginPassword);
        btnLogin = findViewById(R.id.btnLogin);
        tvRegister = findViewById(R.id.tvGoRegister);
        progressLogin = findViewById(R.id.progressLogin);

        btnLogin.setOnClickListener(v -> login());
        tvRegister.setOnClickListener(v ->
                startActivity(new Intent(this, RegisterActivity.class)));
    }

    private void login() {
        String email = etEmail.getText().toString().trim();
        String password = etPassword.getText().toString();
        if (TextUtils.isEmpty(email) || TextUtils.isEmpty(password)) {
            Toast.makeText(this, R.string.auth_fill_fields, Toast.LENGTH_SHORT).show();
            return;
        }
        if (!Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
            Toast.makeText(this, R.string.auth_invalid_email, Toast.LENGTH_SHORT).show();
            return;
        }

        setLoading(true);
        FirebaseAuth.getInstance().signInWithEmailAndPassword(email, password)
                .addOnCompleteListener(task -> {
                    setLoading(false);
                    if (task.isSuccessful()) {
                        startActivity(new Intent(this, MainActivity.class));
                        finish();
                    } else {
                        Exception error = task.getException();
                        if (error instanceof FirebaseAuthInvalidCredentialsException) {
                            Toast.makeText(this, R.string.auth_login_failed, Toast.LENGTH_SHORT).show();
                        } else if (error instanceof FirebaseNetworkException) {
                            Toast.makeText(this, R.string.auth_network_error, Toast.LENGTH_SHORT).show();
                        } else {
                            Toast.makeText(this, R.string.auth_login_failed, Toast.LENGTH_SHORT).show();
                        }
                    }
                });
    }

    private void setLoading(boolean loading) {
        btnLogin.setEnabled(!loading);
        tvRegister.setEnabled(!loading);
        progressLogin.setVisibility(loading ? android.view.View.VISIBLE : android.view.View.GONE);
    }
}
