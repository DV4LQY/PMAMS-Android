package com.catsu.ictu.pmams;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.net.http.SslError;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.view.View;
import android.webkit.CookieManager;
import android.webkit.PermissionRequest;
import android.webkit.SslErrorHandler;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSession;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

import java.security.cert.X509Certificate;

public class MainActivity extends Activity {
    private static final int FILE_CHOOSER_REQUEST = 2101;
    private static final int MEDIA_PERMISSION_REQUEST = 2102;
    private static final long CONNECTION_DECISION_DELAY_MS = 60_000L;
    private static final long CONNECTION_MONITOR_INTERVAL_MS = 120_000L;

    private final Handler connectionHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService connectionHealthExecutor = Executors.newSingleThreadExecutor();
    private WebView webView;
    private AlertDialog connectionSwitchDialog;
    private ProgressBar progressBar;
    private LinearLayout connectionStatusPanel;
    private TextView connectionStatusMessage;
    private TextView connectionStatusUrl;
    private LinearLayout errorPanel;
    private TextView errorMessage;
    private ValueCallback<Uri[]> filePathCallback;
    private Uri cameraOutputUri;
    private PermissionRequest pendingPermissionRequest;
    private Runnable connectionDecisionRunnable;
    private Runnable connectionMonitorRunnable;
    private String connectionAttemptUrl;
    private boolean mainFrameConnectionFailed;
    private boolean connectionHealthCheckInProgress;
    private boolean pageLoaded;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(getColor(R.color.ictu_navy));
        getWindow().setNavigationBarColor(getColor(R.color.ictu_navy));
        setContentView(R.layout.activity_main);

        webView = findViewById(R.id.webView);
        progressBar = findViewById(R.id.progressBar);
        connectionStatusPanel = findViewById(R.id.connectionStatusPanel);
        connectionStatusMessage = findViewById(R.id.connectionStatusMessage);
        connectionStatusUrl = findViewById(R.id.connectionStatusUrl);
        errorPanel = findViewById(R.id.errorPanel);
        errorMessage = findViewById(R.id.errorMessage);
        TextView appVersion = findViewById(R.id.appVersion);
        appVersion.setText(getString(R.string.app_version_format, BuildConfig.VERSION_NAME));
        Button retryButton = findViewById(R.id.retryButton);
        retryButton.setOnClickListener(view -> loadPortal());

        configureWebView();
        loadPortal();
    }

    private void configureWebView() {
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setAllowFileAccess(true);
        settings.setAllowContentAccess(true);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setBuiltInZoomControls(false);
        settings.setDisplayZoomControls(false);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        }

        CookieManager.getInstance().setAcceptCookie(true);
        webView.setWebViewClient(new PortalWebViewClient());
        webView.setWebChromeClient(new PortalChromeClient());
        webView.setDownloadListener((url, userAgent, contentDisposition, mimetype, contentLength) -> {
            try {
                startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
            } catch (ActivityNotFoundException ignored) {
                showError("No app is available to open this download.");
            }
        });
    }

    private void loadPortal() {
        loadPortalUrl(BuildConfig.BASE_URL, R.string.connecting_to_pmams);
    }

    private void loadPortalUrl(String url, int statusMessageResId) {
        String httpsUrl = forceHttpsUrl(url);
        cancelConnectionDecision();
        dismissConnectionDecisionDialog();
        connectionAttemptUrl = httpsUrl;
        mainFrameConnectionFailed = false;
        pageLoaded = false;
        connectionStatusMessage.setText(statusMessageResId);
        connectionStatusUrl.setText(httpsUrl);
        connectionStatusPanel.setVisibility(View.VISIBLE);
        errorPanel.setVisibility(View.GONE);
        webView.setVisibility(View.VISIBLE);
        progressBar.setVisibility(View.VISIBLE);
        connectionDecisionRunnable = () -> {
            connectionDecisionRunnable = null;
            if (httpsUrl.equals(connectionAttemptUrl) && !pageLoaded) {
                showConnectionDecisionDialog(httpsUrl);
            }
        };
        connectionHandler.postDelayed(connectionDecisionRunnable, CONNECTION_DECISION_DELAY_MS);
        webView.stopLoading();
        webView.loadUrl(httpsUrl);
    }

    private void showError(String message) {
        cancelConnectionDecision();
        pageLoaded = false;
        connectionStatusPanel.setVisibility(View.GONE);
        progressBar.setVisibility(View.GONE);
        webView.setVisibility(View.GONE);
        errorMessage.setText(message);
        errorPanel.setVisibility(View.VISIBLE);
    }

    private void cancelConnectionDecision() {
        if (connectionDecisionRunnable != null) {
            connectionHandler.removeCallbacks(connectionDecisionRunnable);
            connectionDecisionRunnable = null;
        }
    }

    private void dismissConnectionDecisionDialog() {
        if (connectionSwitchDialog != null && connectionSwitchDialog.isShowing()) {
            connectionSwitchDialog.dismiss();
        }
        connectionSwitchDialog = null;
    }

    private void markConnectionLost(String failedUrl) {
        mainFrameConnectionFailed = true;
        String displayUrl = connectionAttemptUrl == null
                ? forceHttpsUrl(failedUrl)
                : connectionAttemptUrl;
        connectionStatusMessage.setText(R.string.connection_lost_waiting);
        connectionStatusUrl.setText(displayUrl);
        connectionStatusPanel.setVisibility(View.VISIBLE);

        // A later navigation can fail after the original page has already
        // loaded, so start a fresh decision window when no attempt is active.
        if (connectionDecisionRunnable == null
                && (connectionSwitchDialog == null || !connectionSwitchDialog.isShowing())) {
            connectionDecisionRunnable = () -> {
                connectionDecisionRunnable = null;
                if (connectionAttemptUrl != null
                        && connectionAttemptUrl.equals(displayUrl)) {
                    showConnectionDecisionDialog(displayUrl);
                }
            };
            connectionHandler.postDelayed(connectionDecisionRunnable, CONNECTION_DECISION_DELAY_MS);
        }
    }

    private void showConnectionDecisionDialog(String failedUrl) {
        String sourceUrl = connectionAttemptUrl == null
                ? forceHttpsUrl(failedUrl)
                : connectionAttemptUrl;
        String targetUrl = getAlternateConnectionUrl(sourceUrl);
        if (targetUrl == null) {
            showError(getString(R.string.connection_error));
            return;
        }

        connectionStatusMessage.setText(R.string.connection_lost_waiting);
        connectionStatusUrl.setText(sourceUrl);
        connectionStatusPanel.setVisibility(View.VISIBLE);

        String message = getString(R.string.connection_switch_prompt, sourceUrl, targetUrl);
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(R.string.connection_switch_title)
                .setMessage(message)
                .setNegativeButton(R.string.continue_connection,
                        (ignored, which) -> loadPortalUrl(sourceUrl, R.string.continuing_connection))
                .setPositiveButton(R.string.switch_connection,
                        (ignored, which) -> loadPortalUrl(targetUrl, R.string.switching_connection))
                .setCancelable(false)
                .create();
        connectionSwitchDialog = dialog;
        dialog.setOnDismissListener(ignored -> {
            if (connectionSwitchDialog == dialog) {
                connectionSwitchDialog = null;
            }
        });
        dialog.show();
    }

    private void showDetectedConnectionDialog(String currentUrl, String alternateUrl, int statusMessageResId) {
        if (connectionSwitchDialog != null && connectionSwitchDialog.isShowing()) {
            return;
        }

        connectionStatusMessage.setText(statusMessageResId);
        connectionStatusUrl.setText(currentUrl);
        connectionStatusPanel.setVisibility(View.VISIBLE);

        String message = getString(R.string.connection_detected_prompt, currentUrl, alternateUrl);
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(R.string.network_switch_detected_title)
                .setMessage(message)
                .setNegativeButton(R.string.continue_connection,
                        (ignored, which) -> {
                            connectionStatusPanel.setVisibility(View.GONE);
                            progressBar.setVisibility(View.GONE);
                        })
                .setPositiveButton(R.string.switch_connection,
                        (ignored, which) -> loadPortalUrl(alternateUrl, R.string.switching_connection))
                .setCancelable(false)
                .create();
        connectionSwitchDialog = dialog;
        dialog.setOnDismissListener(ignored -> {
            if (connectionSwitchDialog == dialog) {
                connectionSwitchDialog = null;
            }
        });
        dialog.show();
    }

    private void startConnectionMonitoring() {
        if (connectionMonitorRunnable != null) {
            return;
        }

        connectionMonitorRunnable = new Runnable() {
            @Override
            public void run() {
                if (pageLoaded && connectionAttemptUrl != null && !mainFrameConnectionFailed) {
                    checkConnectionHealth();
                }
                if (connectionMonitorRunnable != null) {
                    connectionHandler.postDelayed(this, CONNECTION_MONITOR_INTERVAL_MS);
                }
            }
        };
        connectionHandler.postDelayed(connectionMonitorRunnable, CONNECTION_MONITOR_INTERVAL_MS);
    }

    private void checkConnectionHealth() {
        if (connectionHealthCheckInProgress || connectionAttemptUrl == null) {
            return;
        }

        String activeUrl = connectionAttemptUrl;
        String localUrl = forceHttpsUrl(BuildConfig.BASE_URL);
        connectionHealthCheckInProgress = true;
        connectionHealthExecutor.execute(() -> {
            boolean localReachable = isUrlReachable(localUrl);
            boolean activeReachable = isLocalConnectionUrl(activeUrl)
                    ? localReachable
                    : isUrlReachable(activeUrl);

            connectionHandler.post(() -> {
                connectionHealthCheckInProgress = false;
                if (!pageLoaded || mainFrameConnectionFailed
                        || !activeUrl.equals(connectionAttemptUrl)) {
                    return;
                }

                // Local is always preferred, but the app must ask before
                // changing networks so the user remains in control.
                if (!isLocalConnectionUrl(activeUrl) && localReachable) {
                    showDetectedConnectionDialog(
                            activeUrl,
                            localUrl,
                            R.string.local_connection_detected
                    );
                    return;
                }

                if (!activeReachable) {
                    String alternateUrl = getAlternateConnectionUrl(activeUrl);
                    if (alternateUrl == null) {
                        markConnectionLost(activeUrl);
                        return;
                    }

                    showDetectedConnectionDialog(
                            activeUrl,
                            alternateUrl,
                            R.string.alternate_connection_detected
                    );
                }
            });
        });
    }

    private boolean isLocalConnectionUrl(String url) {
        if (url == null) {
            return false;
        }

        Uri active = Uri.parse(forceHttpsUrl(url));
        Uri local = Uri.parse(forceHttpsUrl(BuildConfig.BASE_URL));
        return active.getHost() != null && local.getHost() != null
                && active.getHost().equalsIgnoreCase(local.getHost());
    }

    private boolean isUrlReachable(String url) {
        HttpURLConnection connection = null;
        try {
            URL target = new URL(url);
            connection = (HttpURLConnection) target.openConnection();
            connection.setConnectTimeout(5_000);
            connection.setReadTimeout(5_000);
            connection.setInstanceFollowRedirects(true);
            connection.setRequestMethod("GET");
            connection.setRequestProperty("Range", "bytes=0-0");

            if (connection instanceof HttpsURLConnection) {
                HttpsURLConnection secureConnection = (HttpsURLConnection) connection;
                SSLContext sslContext = SSLContext.getInstance("TLS");
                sslContext.init(null, new TrustManager[]{new X509TrustManager() {
                    @Override
                    public void checkClientTrusted(X509Certificate[] chain, String authType) {
                    }

                    @Override
                    public void checkServerTrusted(X509Certificate[] chain, String authType) {
                    }

                    @Override
                    public X509Certificate[] getAcceptedIssuers() {
                        return new X509Certificate[0];
                    }
                }}, new java.security.SecureRandom());
                secureConnection.setSSLSocketFactory(sslContext.getSocketFactory());
                secureConnection.setHostnameVerifier((String hostname, SSLSession session) -> true);
            }

            int responseCode = connection.getResponseCode();
            return responseCode >= 200 && responseCode < 400;
        } catch (Exception ignored) {
            return false;
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private String getAlternateConnectionUrl(String failedUrl) {
        Uri failed = Uri.parse(forceHttpsUrl(failedUrl));
        Uri local = Uri.parse(forceHttpsUrl(BuildConfig.BASE_URL));
        Uri hosted = Uri.parse(forceHttpsUrl(BuildConfig.FALLBACK_URL));
        String failedHost = failed.getHost();

        if (failedHost != null && local.getHost() != null
                && local.getHost().equalsIgnoreCase(failedHost)) {
            return hosted.toString();
        }
        if (failedHost != null && hosted.getHost() != null
                && hosted.getHost().equalsIgnoreCase(failedHost)) {
            return local.toString();
        }
        return null;
    }

    private boolean isSameConnectionHost(String firstUrl, String secondUrl) {
        Uri first = Uri.parse(forceHttpsUrl(firstUrl));
        Uri second = Uri.parse(forceHttpsUrl(secondUrl));
        return first.getHost() != null && first.getHost().equalsIgnoreCase(second.getHost());
    }

    private boolean isPortalUrl(Uri uri) {
        String scheme = uri.getScheme();
        return ("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))
                && isAllowedPortalHost(uri.getHost());
    }

    private String forceHttpsUrl(String url) {
        String trimmedUrl = url.trim();
        Uri uri = Uri.parse(trimmedUrl);
        if ("http".equalsIgnoreCase(uri.getScheme())) {
            return uri.buildUpon()
                    .scheme("https")
                    .build()
                    .toString();
        }
        return trimmedUrl;
    }

    private boolean loadHttpsVersionIfNeeded(WebView view, Uri uri) {
        if (!"http".equalsIgnoreCase(uri.getScheme())) {
            return false;
        }

        view.loadUrl(forceHttpsUrl(uri.toString()));
        return true;
    }

    private boolean isAllowedPortalHost(String host) {
        if (host == null) {
            return false;
        }

        Uri base = Uri.parse(BuildConfig.BASE_URL);
        Uri fallback = Uri.parse(forceHttpsUrl(BuildConfig.FALLBACK_URL));
        return (base.getHost() != null && base.getHost().equalsIgnoreCase(host))
                || (fallback.getHost() != null && fallback.getHost().equalsIgnoreCase(host));
    }

    private void openExternal(Uri uri) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, uri));
        } catch (ActivityNotFoundException ignored) {
            showError("No app is available to open this link.");
        }
    }

    private Intent createCameraIntent() {
        Intent cameraIntent = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
        if (cameraIntent.resolveActivity(getPackageManager()) == null) {
            return null;
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            android.content.ContentValues values = new android.content.ContentValues();
            values.put(MediaStore.Images.Media.DISPLAY_NAME, "pmams-photo-" + System.currentTimeMillis() + ".jpg");
            values.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
            cameraOutputUri = getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
            if (cameraOutputUri != null) {
                cameraIntent.putExtra(MediaStore.EXTRA_OUTPUT, cameraOutputUri);
            }
        }
        return cameraIntent;
    }

    private void handlePermissionRequest(PermissionRequest request) {
        List<String> needed = new ArrayList<>();
        for (String resource : request.getResources()) {
            if (PermissionRequest.RESOURCE_VIDEO_CAPTURE.equals(resource)
                    && checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                needed.add(Manifest.permission.CAMERA);
            }
            if (PermissionRequest.RESOURCE_AUDIO_CAPTURE.equals(resource)
                    && checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                needed.add(Manifest.permission.RECORD_AUDIO);
            }
        }

        if (needed.isEmpty()) {
            request.grant(request.getResources());
            return;
        }

        pendingPermissionRequest = request;
        requestPermissions(needed.toArray(new String[0]), MEDIA_PERMISSION_REQUEST);
    }

    private boolean permissionsGranted(PermissionRequest request) {
        for (String resource : request.getResources()) {
            if (PermissionRequest.RESOURCE_VIDEO_CAPTURE.equals(resource)
                    && checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                return false;
            }
            if (PermissionRequest.RESOURCE_AUDIO_CAPTURE.equals(resource)
                    && checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                return false;
            }
        }
        return true;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == MEDIA_PERMISSION_REQUEST && pendingPermissionRequest != null) {
            if (permissionsGranted(pendingPermissionRequest)) {
                pendingPermissionRequest.grant(pendingPermissionRequest.getResources());
            } else {
                pendingPermissionRequest.deny();
            }
            pendingPermissionRequest = null;
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != FILE_CHOOSER_REQUEST || filePathCallback == null) {
            return;
        }

        Uri[] result = null;
        if (resultCode == RESULT_OK) {
            if (data != null && data.getClipData() != null) {
                int count = data.getClipData().getItemCount();
                result = new Uri[count];
                for (int i = 0; i < count; i++) {
                    result[i] = data.getClipData().getItemAt(i).getUri();
                }
            } else if (data != null && data.getData() != null) {
                result = new Uri[]{data.getData()};
            } else if (cameraOutputUri != null) {
                result = new Uri[]{cameraOutputUri};
            }
        }

        filePathCallback.onReceiveValue(result);
        filePathCallback = null;
        cameraOutputUri = null;
    }

    @Override
    public void onBackPressed() {
        if (webView != null && webView.canGoBack()) {
            webView.goBack();
        } else {
            super.onBackPressed();
        }
    }

    @Override
    protected void onDestroy() {
        cancelConnectionDecision();
        if (connectionMonitorRunnable != null) {
            connectionHandler.removeCallbacks(connectionMonitorRunnable);
            connectionMonitorRunnable = null;
        }
        connectionHealthExecutor.shutdownNow();
        dismissConnectionDecisionDialog();
        if (webView != null) {
            webView.stopLoading();
            webView.setWebChromeClient(null);
            webView.setWebViewClient(null);
            webView.destroy();
        }
        super.onDestroy();
    }

    private final class PortalWebViewClient extends WebViewClient {
        @Override
        public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
            Uri uri = request.getUrl();
            if (isPortalUrl(uri)) {
                return loadHttpsVersionIfNeeded(view, uri);
            }
            openExternal(uri);
            return true;
        }

        @Override
        public boolean shouldOverrideUrlLoading(WebView view, String url) {
            Uri uri = Uri.parse(url);
            if (isPortalUrl(uri)) {
                return loadHttpsVersionIfNeeded(view, uri);
            }
            openExternal(uri);
            return true;
        }

        @Override
        public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) {
            progressBar.setVisibility(View.VISIBLE);
            errorPanel.setVisibility(View.GONE);
            webView.setVisibility(View.VISIBLE);
            connectionStatusUrl.setText(forceHttpsUrl(url));
        }

        @Override
        public void onPageFinished(WebView view, String url) {
            if (!mainFrameConnectionFailed && isCurrentConnectionHost(url)) {
                pageLoaded = true;
                startConnectionMonitoring();
                cancelConnectionDecision();
                connectionStatusPanel.setVisibility(View.GONE);
                progressBar.setVisibility(View.GONE);
            }
        }

        private boolean isCurrentConnectionHost(String url) {
            return connectionAttemptUrl != null && url != null
                    && isSameConnectionHost(connectionAttemptUrl, url);
        }

        @Override
        public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
            if (request.isForMainFrame()) {
                markConnectionLost(request.getUrl().toString());
            }
        }

        @Override
        public void onReceivedHttpError(WebView view, WebResourceRequest request, android.webkit.WebResourceResponse errorResponse) {
            if (request.isForMainFrame() && errorResponse.getStatusCode() >= 400) {
                markConnectionLost(request.getUrl().toString());
            }
        }

        @SuppressLint("WebViewClientOnReceivedSslError")
        @Override
        public void onReceivedSslError(WebView view, SslErrorHandler handler, SslError error) {
            String errorUrl = error == null ? null : error.getUrl();
            if (errorUrl != null && isPortalUrl(Uri.parse(errorUrl))) {
                handler.proceed();
                return;
            }

            handler.cancel();
        }
    }

    private final class PortalChromeClient extends WebChromeClient {
        @Override
        public void onProgressChanged(WebView view, int newProgress) {
            progressBar.setProgress(newProgress);
            progressBar.setVisibility(newProgress >= 100 ? View.GONE : View.VISIBLE);
        }

        @Override
        public void onPermissionRequest(PermissionRequest request) {
            runOnUiThread(() -> handlePermissionRequest(request));
        }

        @Override
        public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback,
                                         FileChooserParams params) {
            if (filePathCallback != null) {
                filePathCallback.onReceiveValue(null);
            }
            filePathCallback = callback;

            // A capture-enabled input (for example, the gallery's Take Photo
            // control) should open the device camera immediately. Keeping the
            // camera intent separate from the document chooser avoids making
            // users pick Camera again on Android WebView.
            if (params.isCaptureEnabled()) {
                Intent cameraIntent = createCameraIntent();
                if (cameraIntent != null) {
                    try {
                        startActivityForResult(cameraIntent, FILE_CHOOSER_REQUEST);
                        return true;
                    } catch (ActivityNotFoundException ignored) {
                        // Fall through to the regular document chooser.
                    }
                }
            }

            String mimeType = "*/*";
            String[] acceptTypes = params.getAcceptTypes();
            if (acceptTypes != null) {
                for (String acceptType : acceptTypes) {
                    if (acceptType != null && !acceptType.trim().isEmpty()) {
                        mimeType = acceptType;
                        break;
                    }
                }
            }

            Intent contentIntent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            contentIntent.addCategory(Intent.CATEGORY_OPENABLE);
            contentIntent.setType(mimeType);
            contentIntent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE,
                    params.getMode() == FileChooserParams.MODE_OPEN_MULTIPLE);

            Intent chooser = Intent.createChooser(contentIntent, getString(R.string.file_chooser_title));

            try {
                startActivityForResult(chooser, FILE_CHOOSER_REQUEST);
                return true;
            } catch (ActivityNotFoundException ignored) {
                filePathCallback.onReceiveValue(null);
                filePathCallback = null;
                return false;
            }
        }
    }
}
