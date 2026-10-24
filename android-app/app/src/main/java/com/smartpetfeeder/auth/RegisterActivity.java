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
import com.google.firebase.FirebaseTooManyRequestsException;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseAuthInvalidCredentialsException;
import com.google.firebase.auth.FirebaseAuthUserCollisionException;
import com.google.firebase.auth.FirebaseAuthWeakPasswordException;
import com.smartpetfeeder.MainActivity;
import com.smartpetfeeder.R;

public class RegisterActivity extends AppCompatActivity {
    private EditText etEmail;
    private EditText etPassword;
    private EditText etConfirmPassword;
    private MaterialButton btnRegister;
    private TextView tvLogin;
    private ProgressBar progressRegister;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_register);

        etEmail = findViewById(R.id.etRegisterEmail);
        etPassword = findViewById(R.id.etRegisterPassword);
        etConfirmPassword = findViewById(R.id.etRegisterConfirmPassword);
        btnRegister = findViewById(R.id.btnRegister);
        tvLogin = findViewById(R.id.tvGoLogin);
        progressRegister = findViewById(R.id.progressRegister);

        btnRegister.setOnClickListener(v -> register());
        tvLogin.setOnClickListener(v -> finish());
    }

    private void register() {
        String email = etEmail.getText().toString().trim();
        String password = etPassword.getText().toString();
        String confirm = etConfirmPassword.getText().toString();
        if (TextUtils.isEmpty(email) || TextUtils.isEmpty(password) || TextUtils.isEmpty(confirm)) {
            Toast.makeText(this, R.string.auth_fill_fields, Toast.LENGTH_SHORT).show();
            return;
        }
        if (!Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
            Toast.makeText(this, R.string.auth_invalid_email, Toast.LENGTH_SHORT).show();
            return;
        }
        if (password.length() < 6) {
            Toast.makeText(this, R.string.auth_password_min_length, Toast.LENGTH_SHORT).show();
            return;
        }
        if (!password.equals(confirm)) {
            Toast.makeText(this, R.string.auth_password_mismatch, Toast.LENGTH_SHORT).show();
            return;
        }

        setLoading(true);
        FirebaseAuth.getInstance().createUserWithEmailAndPassword(email, password)
                .addOnCompleteListener(task -> {
                    setLoading(false);
                    if (task.isSuccessful()) {
                        startActivity(new Intent(this, MainActivity.class));
                        finishAffinity();
                    } else {
                        Exception error = task.getException();
                        if (error instanceof FirebaseAuthWeakPasswordException) {
                            Toast.makeText(this, R.string.auth_password_min_length, Toast.LENGTH_SHORT).show();
                        } else if (error instanceof FirebaseAuthUserCollisionException) {
                            Toast.makeText(this, R.string.auth_email_in_use, Toast.LENGTH_SHORT).show();
                        } else if (error instanceof FirebaseAuthInvalidCredentialsException) {
                            Toast.makeText(this, R.string.auth_invalid_email, Toast.LENGTH_SHORT).show();
                        } else if (error instanceof FirebaseNetworkException) {
                            Toast.makeText(this, R.string.auth_network_error, Toast.LENGTH_SHORT).show();
                        } else if (error instanceof FirebaseTooManyRequestsException) {
                            Toast.makeText(this, R.string.auth_too_many_requests, Toast.LENGTH_SHORT).show();
                        } else {
                            Toast.makeText(this, R.string.auth_register_failed, Toast.LENGTH_SHORT).show();
                        }
                    }
                });
    }

    private void setLoading(boolean loading) {
        btnRegister.setEnabled(!loading);
        tvLogin.setEnabled(!loading);
        progressRegister.setVisibility(loading ? android.view.View.VISIBLE : android.view.View.GONE);
    }
}
