package com.dropbox.core.android;

import java.security.MessageDigest;
import java.security.SecureRandom;

import android.os.AsyncTask;
import android.os.SystemClock;
import android.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.concurrent.ExecutionException;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.DialogInterface;
import android.content.DialogInterface.OnClickListener;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.dropbox.core.DbxAppInfo;
import com.dropbox.core.DbxAuthFinish;
import com.dropbox.core.DbxException;
import com.dropbox.core.DbxRequestConfig;
import com.dropbox.core.DbxRequestUtil;
import com.dropbox.core.DbxWebAuth;

//Note: This class's code is duplicated between Core SDK and Sync SDK.  For now,
//it has to be manually copied, but the code is set up so that it can be used in both
//places, with only a few import changes above.  If you're making changes here, you
//should consider if the other side needs them.  Don't break compatibility if you
//don't have to.  This is a hack we should get away from eventually.

/**
 * This activity is used internally for authentication, but must be exposed both
 * so that Android can launch it and for backwards compatibility.
 */
public class AuthActivity extends Activity {
    private static final String TAG = AuthActivity.class.getName();

    /**
     * The extra that goes in an intent to provide your consumer key for
     * Dropbox authentication. You won't ever have to use this.
     */
    public static final String EXTRA_CONSUMER_KEY = "CONSUMER_KEY";

    /**
     * The extra that goes in an intent when returning from Dropbox auth to
     * provide the user's access token, if auth succeeded. You won't ever have
     * to use this.
     */
    public static final String EXTRA_ACCESS_TOKEN = "ACCESS_TOKEN";

    /**
     * The extra that goes in an intent when returning from Dropbox auth to
     * provide the user's access token secret, if auth succeeded. You won't
     * ever have to use this.
     */
    public static final String EXTRA_ACCESS_SECRET = "ACCESS_SECRET";

    /**
     * The extra that goes in an intent when returning from Dropbox auth to
     * provide the user's Dropbox UID, if auth succeeded. You won't ever have
     * to use this.
     */
    public static final String EXTRA_UID = "UID";

    /**
     * Used for internal authentication. You won't ever have to use this.
     */
    public static final String EXTRA_CONSUMER_SIG = "CONSUMER_SIG";

    /**
     * Used for internal authentication. You won't ever have to use this.
     */
    public static final String EXTRA_CALLING_PACKAGE = "CALLING_PACKAGE";

    /**
     * Used for internal authentication. You won't ever have to use this.
     */
    public static final String EXTRA_CALLING_CLASS = "CALLING_CLASS";

    /**
     * Used for internal authentication. You won't ever have to use this.
     */
    public static final String EXTRA_AUTH_STATE = "AUTH_STATE";

    /**
     * Used for internal authentication. You won't ever have to use this.
     */
    public static final String EXTRA_AUTH_QUERY_PARAMS = "AUTH_QUERY_PARAMS";

    /**
     * Used for internal authentication. You won't ever have to use this.
     */
    public static final String EXTRA_AUTH_QUERY_RESULTS = "AUTH_QUERY_RESULTS";

    /**
     * Used for internal authentication. Allows app to request a specific UID to auth against
     * You won't ever have to use this.
     */
    public static final String EXTRA_DESIRED_UID = "DESIRED_UID";

    /**
     * Used for internal authentication. Allows app to request array of UIDs that should not be auth'd
     * You won't ever have to use this.
     */
    public static final String EXTRA_ALREADY_AUTHED_UIDS = "ALREADY_AUTHED_UIDS";

    /**
     * Used for internal authentication. Allows app to transfer session info to/from DbApp
     * You won't ever have to use this.
     */
    public static final String EXTRA_SESSION_ID = "SESSION_ID";

    /**
     * The Android action which the official Dropbox app will accept to
     * authenticate a user. You won't ever have to use this.
     */
    public static final String ACTION_AUTHENTICATE_V1 = "com.dropbox.android.AUTHENTICATE_V1";

    /**
     * The Android action which the official Dropbox app will accept to
     * authenticate a user. You won't ever have to use this.
     */
    public static final String ACTION_AUTHENTICATE_V2 = "com.dropbox.android.AUTHENTICATE_V2";

    /**
     * The version of the API for the web-auth callback with token (not the initial auth request).
     */
    public static final int AUTH_VERSION = 1;

    /**
     * The path for a successful callback with token (not the initial auth request).
     */
    public static final String AUTH_PATH_CONNECT = "/connect";

    private static final String DEFAULT_WEB_HOST = "www.dropbox.com";

    /**
     * saved instance state keys
     */
    private static final String SIS_KEY_AUTH_STATE_PARAMS = "SIS_KEY_AUTH_STATE_PARAMS";

    /**
     * The minimum length of random bytes for create code verifier in
     * {@link #createCodeVerifier()} (SecureRandom,int)}.
     */
    private static final int MIN_CODE_VERIFIER_RANDOM_BYTES = 32;

    /**
     * The maximum length of random bytes for create code verifier in
     * {@link #createCodeVerifier()} (SecureRandom,int)}.
     */
    private static final int MAX_CODE_VERIFIER_RANDOM_BYTES = 96;


    /**
     * Base64 encoding settings used for generated code verifiers.
     */
    private static final int PKCE_BASE64_ENCODE_SETTINGS =
            Base64.NO_WRAP | Base64.NO_PADDING | Base64.URL_SAFE;

    /**
     * Provider of the local security needs of an AuthActivity.
     *
     * <p>
     * You shouldn't need to use this class directly in your app.  Instead,
     * simply configure {@code java.security}'s providers to match your preferences.
     * </p>
     */
    public interface SecurityProvider {
        /**
         * Gets a SecureRandom implementation for use during authentication.
         */
        SecureRandom getSecureRandom();
    }

    // Class-level state used to replace the default SecureRandom implementation
    // if desired.
    private static SecurityProvider sSecurityProvider = new SecurityProvider() {
        @Override
        public SecureRandom getSecureRandom() {
            return FixedSecureRandom.get();
        }
    };
    private static final Object sSecurityProviderLock = new Object();

    /** Used internally. */
    public static Intent result = null;

    // Temporary storage for parameters before Activity is created
    private static String sAppKey;
    private static String sWebHost = DEFAULT_WEB_HOST;
    private static String sApiType;
    private static String sDesiredUid;
    private static String[] sAlreadyAuthedUids;
    private static String sSessionId;
    private static String sTokenAccessType;
    private static String sScope;

    // These instance variables need not be stored in savedInstanceState as onNewIntent()
    // does not read them.
    private String mAppKey;
    private String mWebHost;
    private String mApiType;
    private String mDesiredUid;
    private String[] mAlreadyAuthedUids;
    private String mSessionId;
    private String mTokenAccessType;
    private String mScope;

    private class AuthUrlQueryParams {
        private final String state;
        private final String codeVerifier;
        private String codeChallenge;
        private String codeChallengeMethod;
        private String tokenAccessType;
        private String scope;

        private AuthUrlQueryParams(String state,
                /*Nullable*/ String codeVerifier,
                /*Nullable*/ String codeChallenge,
                /*Nullable*/ String codeChallengeMethod,
                /*Nullable*/ String tokenAccessTypes,
                /*Nullable*/ String scope) {
            this.state = state;
            this.codeVerifier = codeVerifier;
            this.codeChallenge = codeChallenge;
            this.codeChallengeMethod = codeChallengeMethod;
            this.tokenAccessType = tokenAccessTypes;
            this.scope = scope;
        }

        private void loadUrlQueryParams(Intent intent) {
            intent.putExtra(EXTRA_AUTH_STATE, this.state);
            String extraAuthQueryParams = String.format(
                    "response_type=code&token_access_type=%s&code_challenge=%s&code_challenge_method=%s&scope=%s",
                    this.tokenAccessType,
                    this.codeChallenge,
                    this.codeChallengeMethod,
                    this.scope);
            intent.putExtra(EXTRA_AUTH_QUERY_PARAMS, extraAuthQueryParams);
        }

        private AuthUrlQueryParams(Bundle bundle) {
            String[] stringArray = bundle.getStringArray(SIS_KEY_AUTH_STATE_PARAMS);
            this.state = stringArray[0];
            this.codeVerifier = stringArray[1];
        }

        private void storeStatus(Bundle bundle) {
            bundle.putStringArray(SIS_KEY_AUTH_STATE_PARAMS,
                    new String[]{this.state, this.codeVerifier});
        }

    }

    // Stored in savedInstanceState to track an ongoing auth attempt, which
    // must include a locally-generated nonce in the response.
    private AuthUrlQueryParams mAuthUrlQueryParams = null;


    private boolean mActivityDispatchHandlerPosted = false;

    /**
     * Set static authentication parameters
     */
    static void setAuthParams(String appKey, String desiredUid,
                              String[] alreadyAuthedUids) {
        setAuthParams(appKey, desiredUid, alreadyAuthedUids, null);
    }



    /**
     * Set static authentication parameters
     */
    static void setAuthParams(String appKey, String desiredUid,
                              String[] alreadyAuthedUids, String webHost, String apiType) {
        setAuthParams(appKey, desiredUid, alreadyAuthedUids, null, null, null, null, null);
    }

    /**
     * Set static authentication parameters
     */
    static void setAuthParams(String appKey, String desiredUid,
                              String[] alreadyAuthedUids, String sessionId) {
        setAuthParams(appKey, desiredUid, alreadyAuthedUids, sessionId,
                null, null, null, null);
    }

    /**
     * Set static authentication parameters
     */
    static void setAuthParams(String appKey, String desiredUid,
                              String[] alreadyAuthedUids, String sessionId,
                              String webHost, String apiType,
                              String tokenAccessType, String scope) {
        sAppKey = appKey;
        sDesiredUid = desiredUid;
        sAlreadyAuthedUids = (alreadyAuthedUids != null) ? alreadyAuthedUids : new String[0];
        sSessionId = sessionId;
        sWebHost = (webHost != null) ? webHost : DEFAULT_WEB_HOST;
        sApiType = apiType;
        sTokenAccessType = tokenAccessType;
        sScope = scope;
    }

    /**
     * Create an intent which can be sent to this activity to start OAuth 2 authentication.
     *
     * @param context the source context
     * @param appKey the consumer key for the app
     * @param webHost the host to use for web authentication, or null for the default
     * @param apiType an identifier for the type of API being supported, or null for
     *  the default
     *
     * @return a newly created intent.
     */
    public static Intent makeIntent(Context context, String appKey, String webHost,
                                    String apiType) {
        return makeIntent(context, appKey, null, null, null, webHost, apiType);
    }

    /**
     * Create an intent which can be sent to this activity to start OAuth 2 authentication.
     *
     * @param context the source context
     * @param appKey the consumer key for the app
     * @param desiredUid    Encourage user to authenticate account defined by this uid.
     *                      (note that user still can authenticate other accounts).
     *                      May be null if no uid desired.
     * @param alreadyAuthedUids Array of any other uids currently authenticated with this app.
     *                          May be null if no uids previously authenticated.
     *                          Authentication screen will encourage user to not authorize these
     *                          user accounts. (note that user may still authorize the accounts).
     * @param sessionId     The SESSION_ID Extra on an OpenWith intent. null if dAuth
     *                      is being launched outside of OpenWith flow
     * @param webHost the host to use for web authentication, or null for the default
     * @param apiType an identifier for the type of API being supported, or null for
     *  the default
     *
     * @return a newly created intent.
     */
    public static Intent makeIntent(Context context, String appKey, String desiredUid, String[] alreadyAuthedUids,
                                    String sessionId, String webHost, String apiType) {
        if (appKey == null) throw new IllegalArgumentException("'appKey' can't be null");
        // Hard coded token access type with online access and dummy scope parameter
        setAuthParams(appKey, desiredUid, alreadyAuthedUids,
                sessionId, webHost, apiType, "online", "test_scope");
        return new Intent(context, AuthActivity.class);
    }

    /**
     * Check's the current app's manifest setup for authentication.
     * If the manifest is incorrect, an exception will be thrown.
     * If another app on the device is conflicting with this one,
     * the user will (optionally) be alerted and false will be returned.
     *
     * @param context the app context
     * @param appKey the consumer key for the app
     * @param alertUser whether to alert the user for the case where
     *  multiple apps are conflicting.
     *
     * @return {@code true} if this app is properly set up for authentication.
     */
    public static boolean checkAppBeforeAuth(Context context, String appKey, boolean alertUser) {
        // Check if the app has set up its manifest properly.
        Intent testIntent = new Intent(Intent.ACTION_VIEW);
        String scheme = "db-" +appKey;
        String uri = scheme + "://" + AUTH_VERSION + AUTH_PATH_CONNECT;
        testIntent.setData(Uri.parse(uri));
        PackageManager pm = context.getPackageManager();
        List<ResolveInfo> activities = pm.queryIntentActivities(testIntent, 0);

        if (null == activities || 0 == activities.size()) {
            throw new IllegalStateException("URI scheme in your app's " +
                    "manifest is not set up correctly. You should have a " +
                    AuthActivity.class.getName() + " with the " +
                    "scheme: " + scheme);
        } else if (activities.size() > 1) {
            if (alertUser) {
                AlertDialog.Builder builder = new AlertDialog.Builder(context);
                builder.setTitle("Security alert");
                builder.setMessage("Another app on your phone may be trying to " +
                        "pose as the app you are currently using. The malicious " +
                        "app can't access your account, but linking to Dropbox " +
                        "has been disabled as a precaution. Please contact " +
                        "support@dropbox.com.");
                builder.setPositiveButton("OK", new OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        dialog.dismiss();
                    }
                });
                builder.show();
            } else {
                Log.w(TAG, "There are multiple apps registered for the AuthActivity " +
                        "URI scheme (" + scheme + ").  Another app may be trying to " +
                        " impersonate this app, so authentication will be disabled.");
            }
            return false;
        } else {
            // Just one activity registered for the URI scheme. Now make sure
            // it's within the same package so when we return from web auth
            // we're going back to this app and not some other app.
            ResolveInfo resolveInfo = activities.get(0);
            if (null == resolveInfo || null == resolveInfo.activityInfo
                    || !context.getPackageName().equals(resolveInfo.activityInfo.packageName)) {
                throw new IllegalStateException("There must be a " +
                        AuthActivity.class.getName() + " within your app's package " +
                        "registered for your URI scheme (" + scheme + "). However, " +
                        "it appears that an activity in a different package is " +
                        "registered for that scheme instead. If you have " +
                        "multiple apps that all want to use the same access" +
                        "token pair, designate one of them to do " +
                        "authentication and have the other apps launch it " +
                        "and then retrieve the token pair from it.");
            }
        }

        return true;
    }

    /**
     * Sets the SecurityProvider interface to use for all AuthActivity instances.
     * If set to null (or never set at all), default {@code java.security} providers
     * will be used instead.
     *
     * <p>
     * You shouldn't need to use this method directly in your app.  Instead,
     * simply configure {@code java.security}'s providers to match your preferences.
     * </p>
     *
     * @param prov the new {@code SecurityProvider} interface.
     */
    public static void setSecurityProvider(SecurityProvider prov) {
        synchronized (sSecurityProviderLock) {
            sSecurityProvider = prov;
        }
    }

    private static SecurityProvider getSecurityProvider() {
        synchronized (sSecurityProviderLock) {
            return sSecurityProvider;
        }
    }

    private static SecureRandom getSecureRandom() {
        SecurityProvider prov = getSecurityProvider();
        if (null != prov) {
            return prov.getSecureRandom();
        }
        return new SecureRandom();
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        mAppKey = sAppKey;
        mWebHost = sWebHost;
        mApiType = sApiType;
        mDesiredUid = sDesiredUid;
        mAlreadyAuthedUids = sAlreadyAuthedUids;
        mSessionId = sSessionId;
        mTokenAccessType = sTokenAccessType;
        mScope = sScope;

        if (savedInstanceState == null) {
            result = null;
            mAuthUrlQueryParams = null;
        } else {
            mAuthUrlQueryParams = new AuthUrlQueryParams(savedInstanceState);
        }

        setTheme(android.R.style.Theme_Translucent_NoTitleBar);

        super.onCreate(savedInstanceState);
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        mAuthUrlQueryParams.storeStatus(outState);
    }

    /**
     * @return Intent to auth with official app
     * Extras should be filled in by callee
     */
    static Intent getOfficialAuthIntent() {
        Intent authIntent = new Intent(ACTION_AUTHENTICATE_V2);
        authIntent.setPackage("com.dropbox.android");
        return authIntent;
    }


    @Override
    protected void onResume() {
        super.onResume();

        if (isFinishing()) {
            return;
        }

        if (mAuthUrlQueryParams != null || mAppKey == null) {
            // We somehow returned to this activity without being forwarded
            // here by the official app.
            // Most commonly caused by user hitting "back" from the auth screen
            // or (if doing browser auth) task switching from auth task back to
            // this one.
            Log.v(TAG,"on resume fails");
            authFinished(null);
            return;
        }

        result = null;

        if (mActivityDispatchHandlerPosted) {
            Log.w(TAG, "onResume called again before Handler run");
            return;
        }

        final AuthUrlQueryParams stateParams = createAuthUrlQueryParams();

        // Create intent to auth with official app.
        final Intent officialAuthIntent = getOfficialAuthIntent();
        officialAuthIntent.putExtra(EXTRA_CONSUMER_KEY, mAppKey);
        officialAuthIntent.putExtra(EXTRA_CONSUMER_SIG, "");
        officialAuthIntent.putExtra(EXTRA_DESIRED_UID, mDesiredUid);
        officialAuthIntent.putExtra(EXTRA_ALREADY_AUTHED_UIDS, mAlreadyAuthedUids);
        officialAuthIntent.putExtra(EXTRA_SESSION_ID, mSessionId);
        officialAuthIntent.putExtra(EXTRA_CALLING_PACKAGE, getPackageName());
        officialAuthIntent.putExtra(EXTRA_CALLING_CLASS, getClass().getName());
        stateParams.loadUrlQueryParams(officialAuthIntent);

        /*
         * An Android bug exists where onResume may be called twice in rapid succession.
         * As mAuthNonceState would already be set at start of the second onResume, auth would fail.
         * Empirical research has found that posting the remainder of the auth logic to a handler
         * mitigates the issue by delaying remainder of auth logic to after the
         * previously posted onResume.
         */
        new Handler(Looper.getMainLooper()).post(new Runnable() {
            public void run() {

                Log.d(TAG, "running startActivity in handler");
                mAuthUrlQueryParams = null;
                // Random entropy passed through auth makes sure we don't accept a
                // response which didn't come from our request.  Each random
                // value is only ever used once.
                try {
                    // Auth with official app, or fall back to web.
                    if (DbxOfficialAppConnector.getDropboxAppPackage(AuthActivity.this, officialAuthIntent) != null) {
                        startActivity(officialAuthIntent);
                    } else {
                        startWebAuth(stateParams.state);
                    }
                } catch (ActivityNotFoundException e) {
                    Log.e(TAG, "Could not launch intent. User may have restricted profile", e);
                    finish();
                    return;
                }
                // Save state that indicates we started a request, only after
                // we started one successfully.
                mAuthUrlQueryParams = stateParams;
                setAuthParams(null, null, null);
            }
        });

        mActivityDispatchHandlerPosted = true;
    }


    @Override
    protected void onNewIntent(Intent intent) {
        // Reject attempt to finish authentication if we never started (nonce=null)
        if (null == mAuthUrlQueryParams) {
            authFinished(null);
            return;
        }

        String token = null, secret = null, uid = null, state = null;

        if (intent.hasExtra(EXTRA_ACCESS_TOKEN)) {
            // Dropbox app auth.
            token = intent.getStringExtra(EXTRA_ACCESS_TOKEN);
            secret = intent.getStringExtra(EXTRA_ACCESS_SECRET);
            uid = intent.getStringExtra(EXTRA_UID);
            state = intent.getStringExtra(EXTRA_AUTH_STATE);
            String extraAuthQueryResults = intent.getStringExtra(EXTRA_AUTH_QUERY_RESULTS);
        } else {
            // Web auth.
            Uri uri = intent.getData();
            if (uri != null) {
                String path = uri.getPath();
                if (AUTH_PATH_CONNECT.equals(path)) {
                    try {
                        token = uri.getQueryParameter("oauth_token");
                        secret = uri.getQueryParameter("oauth_token_secret");
                        uid = uri.getQueryParameter("uid");
                        state = uri.getQueryParameter("state");
                    } catch (UnsupportedOperationException e) {}
                }
            }
        }

        Intent newResult;
        if (token != null && !token.equals("") &&
                (secret != null && !secret.equals("")) &&
                uid != null && !uid.equals("") &&
                state != null && !state.equals("")) {
            // Reject attempt to link if the nonce in the auth state doesn't match,
            // or if we never asked for auth at all.
            if (!mAuthUrlQueryParams.state.equals(state)) {
                authFinished(null);
                return;
            }

            // Successful auth.
            newResult = new Intent();
            Log.v(TAG, "token: "+ token+" secret: "+ secret + " uid: "+uid+" state "+ state);
            CodeAsyncTask codeAsyncTask = new CodeAsyncTask(
                    secret, state, mAuthUrlQueryParams.codeVerifier, newResult);
            try {
                codeAsyncTask.execute().get();
            } catch (Exception e) {
                newResult = null;
            }
        } else {
            // Unsuccessful auth, or missing required parameters.
            newResult = null;

        }
        authFinished(newResult);
    }


    private class CodeAsyncTask
        extends AsyncTask<Void, Void, DbxAuthFinish> {

        private final String code;
        private final String state;
        private final String codeVerifier;
        private final Intent newResult;
        private final DbxWebAuth webAuth;

        private CodeAsyncTask(String code, String state,
                                    String codeVerifier, Intent newResult) {
            this.code = code;
            this.state = state;
            this.codeVerifier = codeVerifier;
            this.newResult = newResult;
            DbxMobileAppInfo appInfo = new DbxMobileAppInfo(mAppKey);
            DbxRequestConfig requestConfig = new DbxRequestConfig("android-code");
            webAuth = new DbxWebAuth(requestConfig, appInfo);
        }

        @Override
        protected DbxAuthFinish doInBackground(Void... params) {
            DbxAuthFinish authFinish;
            try {
                authFinish = this.webAuth.finishFromCodeWithPKCE(
                        this.code, this.state, this.codeVerifier);
                newResult.putExtra(EXTRA_ACCESS_TOKEN, "oauth2code");
                newResult.putExtra(EXTRA_ACCESS_SECRET, authFinish.getAccessToken());
                newResult.putExtra(EXTRA_UID, authFinish.getUserId());
                Log.v(TAG, "Redeem token success " + authFinish.getAccessToken());
            } catch (DbxException e) {
                Log.v(TAG, "Redeem token failures with " + e.getMessage());
                authFinish = null;
            }
            return authFinish;
        }
    }

    private void authFinished(Intent authResult) {
        result = authResult;
        mAuthUrlQueryParams = null;
        setAuthParams(null, null, null);
        finish();
    }

    private void startWebAuth(String state) {
        String path = "1/connect";
        Locale locale = Locale.getDefault();
        locale = new Locale(locale.getLanguage(), locale.getCountry());

        // Web Auth currently does not support desiredUid and only one alreadyAuthUid (param n).
        // We use first alreadyAuthUid arbitrarily.
        // Note that the API treats alreadyAuthUid of 0 and not present equivalently.
        String alreadyAuthedUid = (mAlreadyAuthedUids.length > 0) ? mAlreadyAuthedUids[0] : "0";

        String[] params = {
                "k", mAppKey,
                "n", alreadyAuthedUid,
                "api", mApiType,
                "state", state};

        String url = DbxRequestUtil.buildUrlWithParams(locale.toString(), mWebHost, path, params);

        Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
        startActivity(intent);
    }

    private String createCodeVerifier() {
        final int RANDOM_BYTES = new Random().nextInt(MAX_CODE_VERIFIER_RANDOM_BYTES-
                MIN_CODE_VERIFIER_RANDOM_BYTES + 1) + MIN_CODE_VERIFIER_RANDOM_BYTES;
        Log.w(TAG, "code verifier length " + RANDOM_BYTES);
        byte randomBytes[] = new byte[RANDOM_BYTES];
        getSecureRandom().nextBytes(randomBytes);
        return Base64.encodeToString(randomBytes, PKCE_BASE64_ENCODE_SETTINGS);
    }

    private AuthUrlQueryParams createStateNonce() {
        final int NONCE_BYTES = 16; // 128 bits of randomness.
        byte randomBytes[] = new byte[NONCE_BYTES];
        getSecureRandom().nextBytes(randomBytes);
        StringBuilder sb = new StringBuilder();
        sb.append("oauth2:");
        for (int i = 0; i < NONCE_BYTES; ++i) {
            sb.append(String.format("%02x", (randomBytes[i]&0xff)));
        }
        return new AuthUrlQueryParams(sb.toString(), null, null, null, null, null);
    }

    private String createStateForPKCE(String codeChallenge,
                                      String codeChallengeMethod,
                                      String tokenAccessType) {
        StringBuilder sb = new StringBuilder();
        sb.append("oauth2code:");
        sb.append(codeChallenge);
        sb.append(':');
        sb.append(codeChallengeMethod);
        sb.append(':');
        sb.append(tokenAccessType);
        Log.w(TAG, "status created " + sb.toString());
        return sb.toString();
    }

    private AuthUrlQueryParams createAuthUrlQueryParams() {
        String codeVerifier = createCodeVerifier();
        Log.w(TAG, "code verifier created " + codeVerifier);
        String codeChallenge;
        String codeChallengeMethod;
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(codeVerifier.getBytes("UTF-8"));
            codeChallenge = Base64.encodeToString(hash, PKCE_BASE64_ENCODE_SETTINGS);
            codeChallengeMethod = "S256"; // S256
            Log.w(TAG, "code challenge created with method S256 " + codeChallenge);

        } catch (Exception e) {
            codeChallenge = codeVerifier;
            codeChallengeMethod = "plain";  // plain
            Log.w(TAG, "code challenge created with method plain " + codeChallenge);
        }

        String state = createStateForPKCE(codeChallenge, codeChallengeMethod, mTokenAccessType);
        return new AuthUrlQueryParams(
                state,
                codeVerifier,
                codeChallenge,
                codeChallengeMethod,
                mTokenAccessType,
                mScope);
    }
}
