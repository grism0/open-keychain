/*
 * Copyright (C) 2012 Dominik Schürmann <dominik@dominikschuermann.de>
 * Copyright (C) 2010 Thialfihar <thi@thialfihar.org>
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.sufficientlysecure.keychain.ui;

import org.spongycastle.openpgp.PGPPublicKeyRing;
import org.sufficientlysecure.keychain.Constants;
import org.sufficientlysecure.keychain.Id;
import org.sufficientlysecure.keychain.compatibility.ClipboardReflection;
import org.sufficientlysecure.keychain.helper.ActionBarHelper;
import org.sufficientlysecure.keychain.helper.FileHelper;
import org.sufficientlysecure.keychain.helper.OtherHelper;
import org.sufficientlysecure.keychain.helper.PgpHelper;
import org.sufficientlysecure.keychain.helper.PgpMain;
import org.sufficientlysecure.keychain.provider.ProviderHelper;
import org.sufficientlysecure.keychain.service.KeychainIntentService;
import org.sufficientlysecure.keychain.service.KeychainIntentServiceHandler;
import org.sufficientlysecure.keychain.service.PassphraseCacheService;
import org.sufficientlysecure.keychain.ui.dialog.DeleteFileDialogFragment;
import org.sufficientlysecure.keychain.ui.dialog.FileDialogFragment;
import org.sufficientlysecure.keychain.ui.dialog.LookupUnknownKeyDialogFragment;
import org.sufficientlysecure.keychain.ui.dialog.PassphraseDialogFragment;
import org.sufficientlysecure.keychain.util.Log;
import org.sufficientlysecure.keychain.R;

import com.actionbarsherlock.app.SherlockFragmentActivity;
import com.actionbarsherlock.view.Menu;
import com.actionbarsherlock.view.MenuItem;

import android.app.ProgressDialog;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.os.Messenger;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.animation.AnimationUtils;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.ViewFlipper;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.BufferedInputStream;
import java.io.FileNotFoundException;
import java.io.InputStream;
import java.util.regex.Matcher;

public class DecryptActivity extends SherlockFragmentActivity {

    /* Intents */
    // without permission
    public static final String ACTION_DECRYPT = Constants.INTENT_PREFIX + "DECRYPT";
    public static final String ACTION_DECRYPT_FILE = Constants.INTENT_PREFIX + "DECRYPT_FILE";

    // with permission
    public static final String ACTION_DECRYPT_AND_RETURN = Constants.INTENT_PREFIX
            + "DECRYPT_AND_RETURN";
    public static final String ACTION_DECRYPT_STREAM_AND_RETURN = Constants.INTENT_PREFIX
            + "DECRYPT_STREAM_AND_RETURN";

    /* EXTRA keys for input */
    public static final String EXTRA_TEXT = "text";
    public static final String EXTRA_DATA = "data";
    public static final String EXTRA_REPLY_TO = "replyTo";
    public static final String EXTRA_SUBJECT = "subject";
    public static final String EXTRA_BINARY = "binary";

    private long mSignatureKeyId = 0;

    private boolean mReturnResult = false;
    private String mReplyTo = null;
    private String mSubject = null;
    private boolean mSignedOnly = false;
    private boolean mAssumeSymmetricEncryption = false;

    private EditText mMessage = null;
    private LinearLayout mSignatureLayout = null;
    private ImageView mSignatureStatusImage = null;
    private TextView mUserId = null;
    private TextView mUserIdRest = null;

    private ViewFlipper mSource = null;
    private TextView mSourceLabel = null;
    private ImageView mSourcePrevious = null;
    private ImageView mSourceNext = null;

    private boolean mDecryptEnabled = true;
    private String mDecryptString = "";
    private boolean mReplyEnabled = true;
    private String mReplyString = "";

    private int mDecryptTarget;

    private EditText mFilename = null;
    private CheckBox mDeleteAfter = null;
    private ImageButton mBrowse = null;

    private String mInputFilename = null;
    private String mOutputFilename = null;

    private Uri mContentUri = null;
    private byte[] mDataBytes = null;
    private boolean mReturnBinary = false;

    private long mUnknownSignatureKeyId = 0;

    private long mSecretKeyId = Id.key.none;

    private FileDialogFragment mFileDialog;

    private boolean mLookupUnknownKey = true;

    private boolean mDecryptImmediately = false;

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {

        if (mDecryptEnabled) {
            menu.add(1, Id.menu.option.decrypt, 0, mDecryptString).setShowAsAction(
                    MenuItem.SHOW_AS_ACTION_ALWAYS | MenuItem.SHOW_AS_ACTION_WITH_TEXT);
        }
        if (mReplyEnabled) {
            menu.add(1, Id.menu.option.reply, 1, mReplyString).setShowAsAction(
                    MenuItem.SHOW_AS_ACTION_ALWAYS | MenuItem.SHOW_AS_ACTION_WITH_TEXT);
        }

        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {

        case android.R.id.home:
            // app icon in Action Bar clicked; go home
            Intent intent = new Intent(this, MainActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
            startActivity(intent);
            return true;

        case Id.menu.option.decrypt: {
            decryptClicked();

            return true;
        }
        case Id.menu.option.reply: {
            replyClicked();

            return true;
        }

        default: {
            return super.onOptionsItemSelected(item);
        }
        }
    }

    private void initView() {
        mSource = (ViewFlipper) findViewById(R.id.source);
        mSourceLabel = (TextView) findViewById(R.id.sourceLabel);
        mSourcePrevious = (ImageView) findViewById(R.id.sourcePrevious);
        mSourceNext = (ImageView) findViewById(R.id.sourceNext);

        mSourcePrevious.setClickable(true);
        mSourcePrevious.setOnClickListener(new OnClickListener() {
            public void onClick(View v) {
                mSource.setInAnimation(AnimationUtils.loadAnimation(DecryptActivity.this,
                        R.anim.push_right_in));
                mSource.setOutAnimation(AnimationUtils.loadAnimation(DecryptActivity.this,
                        R.anim.push_right_out));
                mSource.showPrevious();
                updateSource();
            }
        });

        mSourceNext.setClickable(true);
        OnClickListener nextSourceClickListener = new OnClickListener() {
            public void onClick(View v) {
                mSource.setInAnimation(AnimationUtils.loadAnimation(DecryptActivity.this,
                        R.anim.push_left_in));
                mSource.setOutAnimation(AnimationUtils.loadAnimation(DecryptActivity.this,
                        R.anim.push_left_out));
                mSource.showNext();
                updateSource();
            }
        };
        mSourceNext.setOnClickListener(nextSourceClickListener);

        mSourceLabel.setClickable(true);
        mSourceLabel.setOnClickListener(nextSourceClickListener);

        mMessage = (EditText) findViewById(R.id.message);
        mSignatureLayout = (LinearLayout) findViewById(R.id.signature);
        mSignatureStatusImage = (ImageView) findViewById(R.id.ic_signature_status);
        mUserId = (TextView) findViewById(R.id.mainUserId);
        mUserIdRest = (TextView) findViewById(R.id.mainUserIdRest);

        // measure the height of the source_file view and set the message view's min height to that,
        // so it fills mSource fully... bit of a hack.
        View tmp = findViewById(R.id.sourceFile);
        tmp.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);
        int height = tmp.getMeasuredHeight();
        mMessage.setMinimumHeight(height);

        mFilename = (EditText) findViewById(R.id.filename);
        mBrowse = (ImageButton) findViewById(R.id.btn_browse);
        mBrowse.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                FileHelper.openFile(DecryptActivity.this, mFilename.getText().toString(), "*/*",
                        Id.request.filename);
            }
        });

        mDeleteAfter = (CheckBox) findViewById(R.id.deleteAfterDecryption);

        // default: message source
        mSource.setInAnimation(null);
        mSource.setOutAnimation(null);
        while (mSource.getCurrentView().getId() != R.id.sourceMessage) {
            mSource.showNext();
        }
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // check permissions for intent actions without user interaction
        String[] restrictedActions = new String[] { ACTION_DECRYPT_AND_RETURN };
        OtherHelper.checkPackagePermissionForActions(this, this.getCallingPackage(),
                Constants.PERMISSION_ACCESS_API, getIntent().getAction(), restrictedActions);

        setContentView(R.layout.decrypt);

        // set actionbar without home button if called from another app
        ActionBarHelper.setBackButton(this);

        initView();

        // Handle intent actions
        handleActions(getIntent());

        if (mSource.getCurrentView().getId() == R.id.sourceMessage
                && mMessage.getText().length() == 0) {

            CharSequence clipboardText = ClipboardReflection.getClipboardText(this);

            String data = "";
            if (clipboardText != null) {
                Matcher matcher = PgpMain.PGP_MESSAGE.matcher(clipboardText);
                if (!matcher.matches()) {
                    matcher = PgpMain.PGP_SIGNED_MESSAGE.matcher(clipboardText);
                }
                if (matcher.matches()) {
                    data = matcher.group(1);
                    mMessage.setText(data);
                    Toast.makeText(this, R.string.usingClipboardContent, Toast.LENGTH_SHORT).show();
                }
            }
        }

        mSignatureLayout.setVisibility(View.GONE);
        mSignatureLayout.setOnClickListener(new OnClickListener() {
            public void onClick(View v) {
                if (mSignatureKeyId == 0) {
                    return;
                }
                PGPPublicKeyRing key = ProviderHelper.getPGPPublicKeyRingByKeyId(
                        DecryptActivity.this, mSignatureKeyId);
                if (key != null) {
                    Intent intent = new Intent(DecryptActivity.this, KeyServerQueryActivity.class);
                    intent.setAction(KeyServerQueryActivity.ACTION_LOOK_UP_KEY_ID);
                    intent.putExtra(KeyServerQueryActivity.EXTRA_KEY_ID, mSignatureKeyId);
                    startActivity(intent);
                }
            }
        });

        mReplyEnabled = false;

        // build new actionbar
        invalidateOptionsMenu();

        if (mReturnResult) {
            mSourcePrevious.setClickable(false);
            mSourcePrevious.setEnabled(false);
            mSourcePrevious.setVisibility(View.INVISIBLE);

            mSourceNext.setClickable(false);
            mSourceNext.setEnabled(false);
            mSourceNext.setVisibility(View.INVISIBLE);

            mSourceLabel.setClickable(false);
            mSourceLabel.setEnabled(false);
        }

        updateSource();

        if (mDecryptImmediately
                || (mSource.getCurrentView().getId() == R.id.sourceMessage && (mMessage.getText()
                        .length() > 0 || mDataBytes != null || mContentUri != null))) {
            decryptClicked();
        }
    }

    /**
     * Handles all actions with this intent
     * 
     * @param intent
     */
    private void handleActions(Intent intent) {
        String action = intent.getAction();
        Bundle extras = intent.getExtras();
        String type = intent.getType();
        Uri uri = intent.getData();

        if (extras == null) {
            extras = new Bundle();
        }

        /*
         * Android's Action
         */
        if (Intent.ACTION_SEND.equals(action) && type != null) {
            // When sending to APG Encrypt via share menu
            if ("text/plain".equals(type)) {
                // Plain text
                String sharedText = intent.getStringExtra(Intent.EXTRA_TEXT);
                if (sharedText != null) {
                    // handle like normal text decryption, override action and extras to later
                    // execute ACTION_DECRYPT in main actions
                    extras.putString(EXTRA_TEXT, sharedText);
                    action = ACTION_DECRYPT;
                }
            } else {
                // Binary via content provider (could also be files)
                // override uri to get stream from send
                uri = (Uri) intent.getParcelableExtra(Intent.EXTRA_STREAM);
                action = ACTION_DECRYPT_FILE;
            }
        } else if (Intent.ACTION_VIEW.equals(action)) {
            // Android's Action when opening file associated to APG (see AndroidManifest.xml)

            // override action
            action = ACTION_DECRYPT_FILE;

            // EVERYTHING ELSE IS OLD CODE
            // This gets the Uri, where an inputStream can be opened from
            // mContentUri = intent.getData();

            // TODO: old implementation of ACTION_VIEW. Is this used in K9?
            // Uri uri = mIntent.getData();
            // try {
            // InputStream attachment = getContentResolver().openInputStream(uri);
            // ByteArrayOutputStream byteOut = new ByteArrayOutputStream();
            // byte bytes[] = new byte[1 << 16];
            // int length;
            // while ((length = attachment.read(bytes)) > 0) {
            // byteOut.write(bytes, 0, length);
            // }
            // byteOut.close();
            // String data = new String(byteOut.toByteArray());
            // mMessage.setText(data);
            // } catch (FileNotFoundException e) {
            // // ignore, then
            // } catch (IOException e) {
            // // ignore, then
            // }

            // same as ACTION_DECRYPT_FILE but decrypt it immediately
            // handleActionDecryptFile(intent);
            // mDecryptImmediately = true;
        }

        /**
         * Main Actions
         */
        if (ACTION_DECRYPT.equals(action)) {
            mDataBytes = extras.getByteArray(EXTRA_DATA);
            String textData = null;
            if (mDataBytes == null) {
                Log.d(Constants.TAG, "EXTRA_DATA was null");
                textData = extras.getString(EXTRA_TEXT);
            } else {
                Log.d(Constants.TAG, "Got data from EXTRA_DATA");
            }
            if (textData != null) {
                Log.d(Constants.TAG, "textData null, matching text ...");
                Matcher matcher = PgpMain.PGP_MESSAGE.matcher(textData);
                if (matcher.matches()) {
                    Log.d(Constants.TAG, "PGP_MESSAGE matched");
                    textData = matcher.group(1);
                    // replace non breakable spaces
                    textData = textData.replaceAll("\\xa0", " ");
                    mMessage.setText(textData);
                } else {
                    matcher = PgpMain.PGP_SIGNED_MESSAGE.matcher(textData);
                    if (matcher.matches()) {
                        Log.d(Constants.TAG, "PGP_SIGNED_MESSAGE matched");
                        textData = matcher.group(1);
                        // replace non breakable spaces
                        textData = textData.replaceAll("\\xa0", " ");
                        mMessage.setText(textData);

                        mDecryptString = getString(R.string.btn_verify);
                        // build new action bar
                        invalidateOptionsMenu();
                    } else {
                        Log.d(Constants.TAG, "Nothing matched!");
                    }
                }
            }
            mReplyTo = extras.getString(EXTRA_REPLY_TO);
            mSubject = extras.getString(EXTRA_SUBJECT);
        } else if (ACTION_DECRYPT_FILE.equals(action)) {
            // get file path from uri
            String path = FileHelper.getPath(this, uri);

            if (path != null) {
                mInputFilename = path;
                mFilename.setText(mInputFilename);
                guessOutputFilename();
                mSource.setInAnimation(null);
                mSource.setOutAnimation(null);
                while (mSource.getCurrentView().getId() != R.id.sourceFile) {
                    mSource.showNext();
                }
            } else {
                Log.e(Constants.TAG,
                        "Direct binary data without actual file in filesystem is not supported. This is only supported by ACTION_DECRYPT_STREAM_AND_RETURN.");
                Toast.makeText(this, R.string.error_onlyFilesAreSupported, Toast.LENGTH_LONG)
                        .show();
                // end activity
                finish();
            }
        } else if (ACTION_DECRYPT_AND_RETURN.equals(action)) {
            mReturnBinary = extras.getBoolean(EXTRA_BINARY, false);

            if (mContentUri == null) {
                mDataBytes = extras.getByteArray(EXTRA_DATA);
                String data = extras.getString(EXTRA_TEXT);
                if (data != null) {
                    Matcher matcher = PgpMain.PGP_MESSAGE.matcher(data);
                    if (matcher.matches()) {
                        data = matcher.group(1);
                        // replace non breakable spaces
                        data = data.replaceAll("\\xa0", " ");
                        mMessage.setText(data);
                    } else {
                        matcher = PgpMain.PGP_SIGNED_MESSAGE.matcher(data);
                        if (matcher.matches()) {
                            data = matcher.group(1);
                            // replace non breakable spaces
                            data = data.replaceAll("\\xa0", " ");
                            mMessage.setText(data);
                            mDecryptString = getString(R.string.btn_verify);

                            // build new action bar
                            invalidateOptionsMenu();
                        }
                    }
                }
            }
            mReturnResult = true;
        } else if (ACTION_DECRYPT_STREAM_AND_RETURN.equals(action)) {
            // TODO: Implement decrypt stream
        }
    }

    private void guessOutputFilename() {
        mInputFilename = mFilename.getText().toString();
        File file = new File(mInputFilename);
        String filename = file.getName();
        if (filename.endsWith(".asc") || filename.endsWith(".gpg") || filename.endsWith(".pgp")) {
            filename = filename.substring(0, filename.length() - 4);
        }
        mOutputFilename = Constants.path.APP_DIR + "/" + filename;
    }

    private void updateSource() {
        switch (mSource.getCurrentView().getId()) {
        case R.id.sourceFile: {
            mSourceLabel.setText(R.string.label_file);
            mDecryptString = getString(R.string.btn_decrypt);

            // build new action bar
            invalidateOptionsMenu();
            break;
        }

        case R.id.sourceMessage: {
            mSourceLabel.setText(R.string.label_message);
            mDecryptString = getString(R.string.btn_decrypt);

            // build new action bar
            invalidateOptionsMenu();
            break;
        }

        default: {
            break;
        }
        }
    }

    private void decryptClicked() {
        if (mSource.getCurrentView().getId() == R.id.sourceFile) {
            mDecryptTarget = Id.target.file;
        } else {
            mDecryptTarget = Id.target.message;
        }
        initiateDecryption();
    }

    private void initiateDecryption() {
        if (mDecryptTarget == Id.target.file) {
            String currentFilename = mFilename.getText().toString();
            if (mInputFilename == null || !mInputFilename.equals(currentFilename)) {
                guessOutputFilename();
            }

            if (mInputFilename.equals("")) {
                Toast.makeText(this, R.string.noFileSelected, Toast.LENGTH_SHORT).show();
                return;
            }

            if (mInputFilename.startsWith("file")) {
                File file = new File(mInputFilename);
                if (!file.exists() || !file.isFile()) {
                    Toast.makeText(
                            this,
                            getString(R.string.errorMessage, getString(R.string.error_fileNotFound)),
                            Toast.LENGTH_SHORT).show();
                    return;
                }
            }
        }

        if (mDecryptTarget == Id.target.message) {
            String messageData = mMessage.getText().toString();
            Matcher matcher = PgpMain.PGP_SIGNED_MESSAGE.matcher(messageData);
            if (matcher.matches()) {
                mSignedOnly = true;
                decryptStart();
                return;
            }
        }

        // else treat it as an decrypted message/file
        mSignedOnly = false;

        getDecryptionKeyFromInputStream();

        // if we need a symmetric passphrase or a passphrase to use a secret key ask for it
        if (mSecretKeyId == Id.key.symmetric
                || PassphraseCacheService.getCachedPassphrase(this, mSecretKeyId) == null) {
            showPassphraseDialog();
        } else {
            if (mDecryptTarget == Id.target.file) {
                askForOutputFilename();
            } else {
                decryptStart();
            }
        }
    }

    /**
     * Shows passphrase dialog to cache a new passphrase the user enters for using it later for
     * encryption. Based on mSecretKeyId it asks for a passphrase to open a private key or it asks
     * for a symmetric passphrase
     */
    private void showPassphraseDialog() {
        // Message is received after passphrase is cached
        Handler returnHandler = new Handler() {
            @Override
            public void handleMessage(Message message) {
                if (message.what == PassphraseDialogFragment.MESSAGE_OKAY) {
                    if (mDecryptTarget == Id.target.file) {
                        askForOutputFilename();
                    } else {
                        decryptStart();
                    }
                }
            }
        };

        // Create a new Messenger for the communication back
        Messenger messenger = new Messenger(returnHandler);

        try {
            PassphraseDialogFragment passphraseDialog = PassphraseDialogFragment.newInstance(this,
                    messenger, mSecretKeyId);

            passphraseDialog.show(getSupportFragmentManager(), "passphraseDialog");
        } catch (PgpMain.PgpGeneralException e) {
            Log.d(Constants.TAG, "No passphrase for this secret key, encrypt directly!");
            // send message to handler to start encryption directly
            returnHandler.sendEmptyMessage(PassphraseDialogFragment.MESSAGE_OKAY);
        }
    }

    /**
     * TODO: Rework function, remove global variables
     */
    private void getDecryptionKeyFromInputStream() {
        InputStream inStream = null;
        if (mContentUri != null) {
            try {
                inStream = getContentResolver().openInputStream(mContentUri);
            } catch (FileNotFoundException e) {
                Log.e(Constants.TAG, "File not found!", e);
                Toast.makeText(this, getString(R.string.error_fileNotFound, e.getMessage()),
                        Toast.LENGTH_SHORT).show();
            }
        } else if (mDecryptTarget == Id.target.file) {
            // check if storage is ready
            if (!FileHelper.isStorageMounted(mInputFilename)) {
                Toast.makeText(this, getString(R.string.error_externalStorageNotReady),
                        Toast.LENGTH_SHORT).show();
                return;
            }

            try {
                inStream = new BufferedInputStream(new FileInputStream(mInputFilename));
            } catch (FileNotFoundException e) {
                Log.e(Constants.TAG, "File not found!", e);
                Toast.makeText(this, getString(R.string.error_fileNotFound, e.getMessage()),
                        Toast.LENGTH_SHORT).show();
            }
        } else {
            if (mDataBytes != null) {
                inStream = new ByteArrayInputStream(mDataBytes);
            } else {
                inStream = new ByteArrayInputStream(mMessage.getText().toString().getBytes());
            }
        }

        // get decryption key for this inStream
        try {
            try {
                if (inStream.markSupported()) {
                    inStream.mark(200); //should probably set this to the max size of two pgpF objects, if it even needs to be anything other than 0.
                }
                mSecretKeyId = PgpMain.getDecryptionKeyId(this, inStream);
                if (mSecretKeyId == Id.key.none) {
                    throw new PgpMain.PgpGeneralException(
                            getString(R.string.error_noSecretKeyFound));
                }
                mAssumeSymmetricEncryption = false;
            } catch (PgpMain.NoAsymmetricEncryptionException e) {
                if (inStream.markSupported()) {
                    inStream.reset();
                }
                mSecretKeyId = Id.key.symmetric;
                if (!PgpMain.hasSymmetricEncryption(this, inStream)) {
                    throw new PgpMain.PgpGeneralException(
                            getString(R.string.error_noKnownEncryptionFound));
                }
                mAssumeSymmetricEncryption = true;
            }
        } catch (Exception e) {
            Toast.makeText(this, getString(R.string.errorMessage, e.getMessage()),
                    Toast.LENGTH_SHORT).show();
        }
    }

    private void replyClicked() {
        Intent intent = new Intent(this, EncryptActivity.class);
        intent.setAction(EncryptActivity.ACTION_ENCRYPT);
        String data = mMessage.getText().toString();
        data = data.replaceAll("(?m)^", "> ");
        data = "\n\n" + data;
        intent.putExtra(EncryptActivity.EXTRA_TEXT, data);
        intent.putExtra(EncryptActivity.EXTRA_SUBJECT, "Re: " + mSubject);
        intent.putExtra(EncryptActivity.EXTRA_SEND_TO, mReplyTo);
        intent.putExtra(EncryptActivity.EXTRA_SIGNATURE_KEY_ID, mSecretKeyId);
        intent.putExtra(EncryptActivity.EXTRA_ENCRYPTION_KEY_IDS, new long[] { mSignatureKeyId });
        startActivity(intent);
    }

    private void askForOutputFilename() {
        // Message is received after passphrase is cached
        Handler returnHandler = new Handler() {
            @Override
            public void handleMessage(Message message) {
                if (message.what == FileDialogFragment.MESSAGE_OKAY) {
                    Bundle data = message.getData();
                    mOutputFilename = data.getString(FileDialogFragment.MESSAGE_DATA_FILENAME);
                    decryptStart();
                }
            }
        };

        // Create a new Messenger for the communication back
        Messenger messenger = new Messenger(returnHandler);

        mFileDialog = FileDialogFragment.newInstance(messenger,
                getString(R.string.title_decryptToFile),
                getString(R.string.specifyFileToDecryptTo), mOutputFilename, null,
                Id.request.output_filename);

        mFileDialog.show(getSupportFragmentManager(), "fileDialog");
    }

    private void lookupUnknownKey(long unknownKeyId) {
        // Message is received after passphrase is cached
        Handler returnHandler = new Handler() {
            @Override
            public void handleMessage(Message message) {
                if (message.what == LookupUnknownKeyDialogFragment.MESSAGE_OKAY) {
                    // the result is handled by onActivityResult() as LookupUnknownKeyDialogFragment
                    // starts a new Intent which then returns data
                } else if (message.what == LookupUnknownKeyDialogFragment.MESSAGE_CANCEL) {
                    // decrypt again, but don't lookup unknown keys!
                    mLookupUnknownKey = false;
                    decryptStart();
                }
            }
        };

        // Create a new Messenger for the communication back
        Messenger messenger = new Messenger(returnHandler);

        LookupUnknownKeyDialogFragment lookupKeyDialog = LookupUnknownKeyDialogFragment
                .newInstance(messenger, unknownKeyId);

        lookupKeyDialog.show(getSupportFragmentManager(), "unknownKeyDialog");
    }

    private void decryptStart() {
        Log.d(Constants.TAG, "decryptStart");

        // Send all information needed to service to decrypt in other thread
        Intent intent = new Intent(this, KeychainIntentService.class);

        // fill values for this action
        Bundle data = new Bundle();

        intent.setAction(KeychainIntentService.ACTION_DECRYPT_VERIFY);

        // choose action based on input: decrypt stream, file or bytes
        if (mContentUri != null) {
            data.putInt(KeychainIntentService.TARGET, KeychainIntentService.TARGET_STREAM);

            data.putParcelable(KeychainIntentService.ENCRYPT_PROVIDER_URI, mContentUri);
        } else if (mDecryptTarget == Id.target.file) {
            data.putInt(KeychainIntentService.TARGET, KeychainIntentService.TARGET_FILE);

            Log.d(Constants.TAG, "mInputFilename=" + mInputFilename + ", mOutputFilename="
                    + mOutputFilename);

            data.putString(KeychainIntentService.ENCRYPT_INPUT_FILE, mInputFilename);
            data.putString(KeychainIntentService.ENCRYPT_OUTPUT_FILE, mOutputFilename);
        } else {
            data.putInt(KeychainIntentService.TARGET, KeychainIntentService.TARGET_BYTES);

            if (mDataBytes != null) {
                data.putByteArray(KeychainIntentService.DECRYPT_CIPHERTEXT_BYTES, mDataBytes);
            } else {
                String message = mMessage.getText().toString();
                data.putByteArray(KeychainIntentService.DECRYPT_CIPHERTEXT_BYTES, message.getBytes());
            }
        }

        data.putLong(KeychainIntentService.ENCRYPT_SECRET_KEY_ID, mSecretKeyId);

        data.putBoolean(KeychainIntentService.DECRYPT_SIGNED_ONLY, mSignedOnly);
        data.putBoolean(KeychainIntentService.DECRYPT_LOOKUP_UNKNOWN_KEY, mLookupUnknownKey);
        data.putBoolean(KeychainIntentService.DECRYPT_RETURN_BYTES, mReturnBinary);
        data.putBoolean(KeychainIntentService.DECRYPT_ASSUME_SYMMETRIC, mAssumeSymmetricEncryption);

        intent.putExtra(KeychainIntentService.EXTRA_DATA, data);

        // Message is received after encrypting is done in ApgService
        KeychainIntentServiceHandler saveHandler = new KeychainIntentServiceHandler(this,
                R.string.progress_decrypting, ProgressDialog.STYLE_HORIZONTAL) {
            public void handleMessage(Message message) {
                // handle messages by standard ApgHandler first
                super.handleMessage(message);

                if (message.arg1 == KeychainIntentServiceHandler.MESSAGE_OKAY) {
                    // get returned data bundle
                    Bundle returnData = message.getData();

                    // if key is unknown show lookup dialog
                    if (returnData.getBoolean(KeychainIntentService.RESULT_SIGNATURE_LOOKUP_KEY)
                            && mLookupUnknownKey) {
                        mUnknownSignatureKeyId = returnData
                                .getLong(KeychainIntentService.RESULT_SIGNATURE_KEY_ID);
                        lookupUnknownKey(mUnknownSignatureKeyId);
                        return;
                    }

                    mSignatureKeyId = 0;
                    mSignatureLayout.setVisibility(View.GONE);
                    mReplyEnabled = false;

                    // build new action bar
                    invalidateOptionsMenu();

                    Toast.makeText(DecryptActivity.this, R.string.decryptionSuccessful,
                            Toast.LENGTH_SHORT).show();
                    if (mReturnResult) {
                        Intent intent = new Intent();
                        intent.putExtras(returnData);
                        setResult(RESULT_OK, intent);
                        finish();
                        return;
                    }

                    switch (mDecryptTarget) {
                    case Id.target.message:
                        String decryptedMessage = returnData
                                .getString(KeychainIntentService.RESULT_DECRYPTED_STRING);
                        mMessage.setText(decryptedMessage);
                        mMessage.setHorizontallyScrolling(false);
                        mReplyEnabled = false;

                        // build new action bar
                        invalidateOptionsMenu();
                        break;

                    case Id.target.file:
                        if (mDeleteAfter.isChecked()) {
                            // Create and show dialog to delete original file
                            DeleteFileDialogFragment deleteFileDialog = DeleteFileDialogFragment
                                    .newInstance(mInputFilename);
                            deleteFileDialog.show(getSupportFragmentManager(), "deleteDialog");
                        }
                        break;

                    default:
                        // shouldn't happen
                        break;

                    }

                    if (returnData.getBoolean(KeychainIntentService.RESULT_SIGNATURE)) {
                        String userId = returnData
                                .getString(KeychainIntentService.RESULT_SIGNATURE_USER_ID);
                        mSignatureKeyId = returnData
                                .getLong(KeychainIntentService.RESULT_SIGNATURE_KEY_ID);
                        mUserIdRest
                                .setText("id: " + PgpHelper.getSmallFingerPrint(mSignatureKeyId));
                        if (userId == null) {
                            userId = getResources().getString(R.string.unknownUserId);
                        }
                        String chunks[] = userId.split(" <", 2);
                        userId = chunks[0];
                        if (chunks.length > 1) {
                            mUserIdRest.setText("<" + chunks[1]);
                        }
                        mUserId.setText(userId);

                        if (returnData.getBoolean(KeychainIntentService.RESULT_SIGNATURE_SUCCESS)) {
                            mSignatureStatusImage.setImageResource(R.drawable.overlay_ok);
                        } else if (returnData.getBoolean(KeychainIntentService.RESULT_SIGNATURE_UNKNOWN)) {
                            mSignatureStatusImage.setImageResource(R.drawable.overlay_error);
                            Toast.makeText(DecryptActivity.this,
                                    R.string.unknownSignatureKeyTouchToLookUp, Toast.LENGTH_LONG)
                                    .show();
                        } else {
                            mSignatureStatusImage.setImageResource(R.drawable.overlay_error);
                        }
                        mSignatureLayout.setVisibility(View.VISIBLE);
                    }
                }
            };
        };

        // Create a new Messenger for the communication back
        Messenger messenger = new Messenger(saveHandler);
        intent.putExtra(KeychainIntentService.EXTRA_MESSENGER, messenger);

        // show progress dialog
        saveHandler.showProgressDialog(this);

        // start service with intent
        startService(intent);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        switch (requestCode) {
        case Id.request.filename: {
            if (resultCode == RESULT_OK && data != null) {
                try {
                    String path = FileHelper.getPath(this, data.getData());
                    Log.d(Constants.TAG, "path=" + path);

                    mFilename.setText(path);
                } catch (NullPointerException e) {
                    Log.e(Constants.TAG, "Nullpointer while retrieving path!");
                }
            }
            return;
        }

        case Id.request.output_filename: {
            if (resultCode == RESULT_OK && data != null) {
                try {
                    String path = FileHelper.getPath(this, data.getData());
                    Log.d(Constants.TAG, "path=" + path);

                    mFileDialog.setFilename(path);
                } catch (NullPointerException e) {
                    Log.e(Constants.TAG, "Nullpointer while retrieving path!");
                }
            }
            return;
        }

        // this request is returned after LookupUnknownKeyDialogFragment started
        // KeyServerQueryActivity and user looked uo key
        case Id.request.look_up_key_id: {
            Log.d(Constants.TAG, "Returning from Lookup Key...");
            // decrypt again without lookup
            mLookupUnknownKey = false;
            decryptStart();
            return;
        }

        default: {
            break;
        }
        }

        super.onActivityResult(requestCode, resultCode, data);
    }

}

