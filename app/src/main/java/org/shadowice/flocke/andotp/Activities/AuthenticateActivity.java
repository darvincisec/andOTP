/*
 * Copyright (C) 2017-2020 Jakob Nixdorf
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package org.shadowice.flocke.andotp.Activities;

import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;

import androidx.annotation.NonNull;
import androidx.appcompat.widget.Toolbar;
import android.text.InputType;
import android.text.method.PasswordTransformationMethod;
import android.util.Log;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewStub;
import android.view.WindowManager.LayoutParams;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import org.shadowice.flocke.andotp.R;
import org.shadowice.flocke.andotp.Tasks.AuthenticationTask;
import org.shadowice.flocke.andotp.Tasks.AuthenticationTask.Callback;
import org.shadowice.flocke.andotp.Tasks.AuthenticationTask.Result;
import org.shadowice.flocke.andotp.Utilities.Constants;

import java.util.concurrent.ExecutionException;

import static org.shadowice.flocke.andotp.Utilities.Constants.AuthMethod;

public class AuthenticateActivity extends ThemedActivity
        implements EditText.OnEditorActionListener, View.OnClickListener {

    private static final String KEY_WAS_TASK_ACTIVE = "AuthenticateActivity.WasTaskActive";

    private AuthMethod authMethod;
    private String newEncryption = "";
    private String existingAuthCredentials;
    private boolean isAuthUpgrade = false;

    private TextInputEditText passwordInput;
    private Button unlockButton;

    private AuthenticationTask activeTask;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (! settings.getScreenshotsEnabled())
            getWindow().setFlags(LayoutParams.FLAG_SECURE, LayoutParams.FLAG_SECURE);

        authMethod = settings.getAuthMethod();
        newEncryption = getIntent().getStringExtra(Constants.EXTRA_AUTH_NEW_ENCRYPTION);
        existingAuthCredentials = settings.getAuthCredentials();
        if (existingAuthCredentials.isEmpty()) {
            existingAuthCredentials = settings.getOldCredentials(authMethod);
            isAuthUpgrade = true;
        }

        // If our password is still empty at this point, we can't do anything.
        if (existingAuthCredentials.isEmpty()) {
            int missingPwResId = (authMethod == AuthMethod.PASSWORD)
                    ? R.string.auth_toast_password_missing : R.string.auth_toast_pin_missing;
            Toast.makeText(this, missingPwResId, Toast.LENGTH_LONG).show();
            finishWithResult(true, null);
        }
        // If we're not using password or pin for auth method, we have nothing to authenticate here.
        if (authMethod != AuthMethod.PASSWORD && authMethod != AuthMethod.PIN) {
            finishWithResult(true, null);
        }

        setTitle(R.string.auth_activity_title);
        setContentView(R.layout.activity_container);
        initToolbar();
        initPasswordViews();

        getWindow().setSoftInputMode(LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE);
    }

    private void initToolbar() {
        Toolbar toolbar = findViewById(R.id.container_toolbar);
        toolbar.setNavigationIcon(null);
        setSupportActionBar(toolbar);
    }

    private void initPasswordViews() {
        ViewStub stub = findViewById(R.id.container_stub);
        stub.setLayoutResource(R.layout.content_authenticate);
        View v = stub.inflate();

        initPasswordLabelView(v);
        initPasswordLayoutView(v);
        initPasswordInputView(v);
        initUnlockButtonView(v);
    }

    private void initPasswordLabelView(View v) {
        int labelMsg = getIntent().getIntExtra(Constants.EXTRA_AUTH_MESSAGE, R.string.auth_msg_authenticate);
        TextView passwordLabel = v.findViewById(R.id.passwordLabel);
        passwordLabel.setText(labelMsg);
    }

    private void initPasswordLayoutView(View v) {
        TextInputLayout passwordLayout = v.findViewById(R.id.passwordLayout);

        int hintResId = (authMethod == AuthMethod.PASSWORD) ? R.string.auth_hint_password : R.string.auth_hint_pin;
        passwordLayout.setHint(getString(hintResId));

        if (settings.getBlockAccessibility())
            passwordLayout.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && settings.getBlockAutofill())
            passwordLayout.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS);
    }

    private void initPasswordInputView(View v) {
        passwordInput = v.findViewById(R.id.passwordEdit);
        int inputType = (authMethod == AuthMethod.PASSWORD)
                ? (InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD)
                : (InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        passwordInput.setInputType(inputType);
        passwordInput.setTransformationMethod(new PasswordTransformationMethod());
        passwordInput.setOnEditorActionListener(this);
    }

    private void initUnlockButtonView(View v) {
        unlockButton = v.findViewById(R.id.buttonUnlock);
        unlockButton.setOnClickListener(this);
    }

    @Override
    public void onClick(View view) {
        startAuthTask(passwordInput.getText().toString());
    }

    @Override
    public boolean onEditorAction(TextView v, int actionId, KeyEvent event) {
        if (actionId == EditorInfo.IME_ACTION_DONE) {
            startAuthTask(v.getText().toString());
            return true;
        }
        return false;
    }

    private void startAuthTask(String plainPassword) {
        passwordInput.setEnabled(false);
        unlockButton.setEnabled(false);
        Callback callback = this::handleResult;
        activeTask = new AuthenticationTask(this, callback, isAuthUpgrade, existingAuthCredentials, plainPassword);
        activeTask.execute();
    }

    private void handleResult(Result result) {
        activeTask = null;
        if (result.authUpgradeFailed) {
            Toast.makeText(this, R.string.settings_toast_auth_upgrade_failed, Toast.LENGTH_LONG).show();
        }
        finishWithResult(result.encryptionKey != null, result.encryptionKey);
    }

    private void finishWithResult(boolean success, byte[] encryptionKey) {
        Intent data = new Intent();
        if (newEncryption != null && !newEncryption.isEmpty())
            data.putExtra(Constants.EXTRA_AUTH_NEW_ENCRYPTION, newEncryption);
        if (encryptionKey != null)
            data.putExtra(Constants.EXTRA_AUTH_PASSWORD_KEY, encryptionKey);
        if (success)
            setResult(RESULT_OK, data);
        finish();
    }

    @Override
    protected void onPause() {
        super.onPause();
        completeTaskIfActive();
    }

    /** @return true if the task was active and was completed, false otherwise. */
    private boolean completeTaskIfActive() {
        try {
            // This will cause the main thread to wait, but it'll ensure that the task completes.
            if (activeTask != null) {
                handleResult(activeTask.get());
                return true;
            }
        } catch (ExecutionException | InterruptedException e) {
            Log.e("AuthenticateActivity", "Could not finish active authentication task", e);
        }
        return false;
    }

    @Override
    public void onBackPressed() {
        if (!completeTaskIfActive()) {
            finishWithResult(false, null);
        }
        super.onBackPressed();
    }
}
