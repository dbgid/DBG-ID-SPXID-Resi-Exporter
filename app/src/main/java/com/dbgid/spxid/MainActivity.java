package com.dbgid.spxid;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.DownloadManager;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.media.MediaPlayer;
import android.util.Base64;
import android.view.View;
import android.webkit.DownloadListener;
import android.webkit.JavascriptInterface;
import android.webkit.MimeTypeMap;
import android.webkit.URLUtil;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;
import androidx.core.app.NotificationCompat;
import androidx.core.content.FileProvider;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;

public class MainActivity extends Activity {

    private static final int FILE_CHOOSER_REQUEST_CODE = 1001;
    private static final int STORAGE_PERMISSION_REQUEST_CODE = 1002;
    private static final String DOWNLOAD_CHANNEL_ID = "download_channel";

    private SwipeRefreshLayout swipeRefreshLayout;
    private WebView webView;
    private ProgressBar progressBar;
    private View offlineView;
    private Button offlineRetry;
    private View splashContainer;
    private TextView splashText;
    private MediaPlayer splashPlayer;
    private View menuContainer;
    private View webContainer;
    private View menuOpen;
    private View menuClear;
    private View menuShare;
    private boolean hasLoadedHome;
    private boolean pendingClearRequest;

    private ValueCallback<Uri[]> filePathCallback;
    private ValueCallback<Uri> filePathCallbackLegacy;

    private DownloadManager downloadManager;
    private BroadcastReceiver downloadReceiver;
    private long lastDownloadId = -1L;
    private String pendingDownloadUrl;
    private String pendingDownloadUserAgent;
    private String pendingDownloadContentDisposition;
    private String pendingDownloadMimeType;

    private String pendingBlobUrl;
    private String pendingBlobFileName;
    private String pendingBlobMimeType;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        swipeRefreshLayout = findViewById(R.id.swipe_refresh);
        webView = findViewById(R.id.web_view);
        progressBar = findViewById(R.id.progress_bar);
        offlineView = findViewById(R.id.offline_view);
        offlineRetry = findViewById(R.id.offline_retry);
        splashContainer = findViewById(R.id.splash_container);
        splashText = findViewById(R.id.splash_text);
        menuContainer = findViewById(R.id.menu_container);
        webContainer = findViewById(R.id.web_container);
        menuOpen = findViewById(R.id.menu_open);
        menuClear = findViewById(R.id.menu_clear);
        menuShare = findViewById(R.id.menu_share);

        swipeRefreshLayout.setColorSchemeResources(
                android.R.color.holo_blue_bright,
                android.R.color.holo_green_light,
                android.R.color.holo_orange_light,
                android.R.color.holo_red_light
        );
        swipeRefreshLayout.setOnRefreshListener(new SwipeRefreshLayout.OnRefreshListener() {
            @Override
            public void onRefresh() {
                if (isOnline()) {
                    webView.reload();
                } else {
                    swipeRefreshLayout.setRefreshing(false);
                    showOffline();
                }
            }
        });

        offlineRetry.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (isOnline()) {
                    offlineView.setVisibility(View.GONE);
                    if (webView.getUrl() == null) {
                        loadHome();
                    } else {
                        webView.reload();
                    }
                } else {
                    Toast.makeText(MainActivity.this, R.string.offline_message, Toast.LENGTH_SHORT).show();
                }
            }
        });
        menuOpen.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openWebView();
            }
        });
        menuClear.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                confirmClearExcel();
            }
        });
        menuShare.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                shareApp();
            }
        });

        setupWebView();
        setupDownloadReceiver();
        startSplashAnimation();
        playSplashSound();
        showMenu();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (webContainer.getVisibility() == View.VISIBLE && !isOnline()) {
            showOffline();
        }
    }

    @Override
    protected void onDestroy() {
        if (downloadReceiver != null) {
            unregisterReceiver(downloadReceiver);
            downloadReceiver = null;
        }
        releaseSplashPlayer();
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        if (webContainer.getVisibility() == View.VISIBLE && webView.canGoBack()) {
            webView.goBack();
        } else if (webContainer.getVisibility() == View.VISIBLE) {
            showMenu();
        } else {
            super.onBackPressed();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == FILE_CHOOSER_REQUEST_CODE) {
            if (filePathCallback != null) {
                Uri[] result = WebChromeClient.FileChooserParams.parseResult(resultCode, data);
                filePathCallback.onReceiveValue(result);
                filePathCallback = null;
            }
            if (filePathCallbackLegacy != null) {
                Uri result = (resultCode == RESULT_OK && data != null) ? data.getData() : null;
                filePathCallbackLegacy.onReceiveValue(result);
                filePathCallbackLegacy = null;
            }
            return;
        }
        super.onActivityResult(requestCode, resultCode, data);
    }

    private void setupWebView() {
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(true);
        settings.setAllowContentAccess(true);
        settings.setSupportZoom(true);
        settings.setBuiltInZoomControls(true);
        settings.setDisplayZoomControls(false);
        settings.setUseWideViewPort(true);
        settings.setLoadWithOverviewMode(true);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            settings.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        }
        webView.addJavascriptInterface(new BlobDownloadInterface(), "AndroidBlobDownloader");

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                if (isBlobUrl(url)) {
                    requestBlobDownload(url, null, null);
                    return true;
                }
                return false;
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri uri = request != null ? request.getUrl() : null;
                String url = uri != null ? uri.toString() : null;
                if (isBlobUrl(url)) {
                    requestBlobDownload(url, null, null);
                    return true;
                }
                return false;
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                swipeRefreshLayout.setRefreshing(false);
                progressBar.setVisibility(View.GONE);
                if (isOnline()) {
                    offlineView.setVisibility(View.GONE);
                }
                injectBlobDownloadSupport();
            }

            @Override
            public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) {
                progressBar.setVisibility(View.VISIBLE);
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                swipeRefreshLayout.setRefreshing(false);
                if (!isOnline()) {
                    showOffline();
                }
            }

            @Override
            public void onReceivedError(WebView view, int errorCode, String description, String failingUrl) {
                swipeRefreshLayout.setRefreshing(false);
                if (!isOnline()) {
                    showOffline();
                }
            }
        });

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView view, int newProgress) {
                if (newProgress >= 100) {
                    progressBar.setVisibility(View.GONE);
                } else {
                    progressBar.setVisibility(View.VISIBLE);
                    progressBar.setProgress(newProgress);
                }
            }

            @Override
            public boolean onShowFileChooser(WebView webView, ValueCallback<Uri[]> filePathCallback,
                                             FileChooserParams fileChooserParams) {
                if (MainActivity.this.filePathCallback != null) {
                    MainActivity.this.filePathCallback.onReceiveValue(null);
                }
                MainActivity.this.filePathCallback = filePathCallback;
                Intent intent = fileChooserParams.createIntent();
                try {
                    startActivityForResult(intent, FILE_CHOOSER_REQUEST_CODE);
                } catch (ActivityNotFoundException e) {
                    MainActivity.this.filePathCallback = null;
                    Toast.makeText(MainActivity.this, R.string.file_chooser_missing, Toast.LENGTH_SHORT).show();
                    return false;
                }
                return true;
            }

            public void openFileChooser(ValueCallback<Uri> uploadMsg) {
                openFileChooserLegacy(uploadMsg, "*/*");
            }

            public void openFileChooser(ValueCallback<Uri> uploadMsg, String acceptType) {
                openFileChooserLegacy(uploadMsg, acceptType);
            }

            public void openFileChooser(ValueCallback<Uri> uploadMsg, String acceptType, String capture) {
                openFileChooserLegacy(uploadMsg, acceptType);
            }
        });

        webView.setDownloadListener(new DownloadListener() {
            @Override
            public void onDownloadStart(String url, String userAgent, String contentDisposition,
                                        String mimeType, long contentLength) {
                startDownload(url, userAgent, contentDisposition, mimeType);
            }
        });
    }

    private void showMenu() {
        menuContainer.setVisibility(View.VISIBLE);
        webContainer.setVisibility(View.GONE);
        offlineView.setVisibility(View.GONE);
    }

    private void openWebView() {
        menuContainer.setVisibility(View.GONE);
        webContainer.setVisibility(View.VISIBLE);
        if (!hasLoadedHome) {
            hasLoadedHome = true;
            loadHome();
        } else if (!isOnline()) {
            showOffline();
        }
    }

    private void startSplashAnimation() {
        if (splashContainer == null || splashText == null) {
            return;
        }
        splashContainer.setAlpha(1f);
        splashText.setAlpha(0f);
        splashText.setScaleX(0.9f);
        splashText.setScaleY(0.9f);
        splashText.animate()
                .alpha(1f)
                .scaleX(1f)
                .scaleY(1f)
                .setDuration(500)
                .start();
        splashContainer.postDelayed(new Runnable() {
            @Override
            public void run() {
                splashContainer.animate()
                        .alpha(0f)
                        .setDuration(300)
                        .withEndAction(new Runnable() {
                            @Override
                            public void run() {
                                splashContainer.setVisibility(View.GONE);
                            }
                        })
                        .start();
            }
        }, 1400);
    }

    private void playSplashSound() {
        releaseSplashPlayer();
        try {
            splashPlayer = MediaPlayer.create(this, R.raw.splash_cinematic);
            if (splashPlayer == null) {
                return;
            }
            splashPlayer.setOnCompletionListener(new MediaPlayer.OnCompletionListener() {
                @Override
                public void onCompletion(MediaPlayer mp) {
                    releaseSplashPlayer();
                }
            });
            splashPlayer.setOnErrorListener(new MediaPlayer.OnErrorListener() {
                @Override
                public boolean onError(MediaPlayer mp, int what, int extra) {
                    releaseSplashPlayer();
                    return true;
                }
            });
            splashPlayer.start();
        } catch (Exception ignored) {
            releaseSplashPlayer();
        }
    }

    private void releaseSplashPlayer() {
        if (splashPlayer != null) {
            try {
                splashPlayer.release();
            } catch (Exception ignored) {
            }
            splashPlayer = null;
        }
    }

    private void setupDownloadReceiver() {
        downloadManager = (DownloadManager) getSystemService(DOWNLOAD_SERVICE);
        createDownloadChannel();
        downloadReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                long id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1);
                if (id != lastDownloadId || downloadManager == null) {
                    return;
                }
                DownloadManager.Query query = new DownloadManager.Query();
                query.setFilterById(id);
                Cursor cursor = null;
                try {
                    cursor = downloadManager.query(query);
                    if (cursor != null && cursor.moveToFirst()) {
                        int status = cursor.getInt(cursor.getColumnIndex(DownloadManager.COLUMN_STATUS));
                        String mimeType = cursor.getString(cursor.getColumnIndex(DownloadManager.COLUMN_MEDIA_TYPE));
                        String title = cursor.getString(cursor.getColumnIndex(DownloadManager.COLUMN_TITLE));
                        if (status == DownloadManager.STATUS_SUCCESSFUL) {
                            Uri fileUri = downloadManager.getUriForDownloadedFile(id);
                            Toast.makeText(MainActivity.this, R.string.download_complete_title, Toast.LENGTH_SHORT).show();
                            showDownloadCompleteDialog(fileUri, mimeType);
                            showDownloadNotification(fileUri, mimeType, title);
                        } else if (status == DownloadManager.STATUS_FAILED) {
                            Toast.makeText(MainActivity.this, R.string.download_failed, Toast.LENGTH_SHORT).show();
                        }
                    }
                } finally {
                    if (cursor != null) {
                        cursor.close();
                    }
                }
            }
        };
        registerReceiver(downloadReceiver, new IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE));
    }

    private void createDownloadChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (manager == null) {
                return;
            }
            NotificationChannel channel = new NotificationChannel(
                    DOWNLOAD_CHANNEL_ID,
                    getString(R.string.download_channel_name),
                    NotificationManager.IMPORTANCE_DEFAULT
            );
            channel.setDescription(getString(R.string.download_channel_desc));
            manager.createNotificationChannel(channel);
        }
    }

    private void startDownload(String url, String userAgent, String contentDisposition, String mimeType) {
        if (!isOnline()) {
            showOffline();
            return;
        }
        if (isBlobUrl(url)) {
            requestBlobDownload(url, contentDisposition, mimeType);
            return;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                && checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            pendingDownloadUrl = url;
            pendingDownloadUserAgent = userAgent;
            pendingDownloadContentDisposition = contentDisposition;
            pendingDownloadMimeType = mimeType;
            requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE},
                    STORAGE_PERMISSION_REQUEST_CODE);
            return;
        }
        enqueueDownload(url, userAgent, contentDisposition, mimeType);
    }

    private void confirmClearExcel() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.clear_excel_title)
                .setMessage(R.string.clear_excel_message)
                .setPositiveButton(R.string.clear_excel_action, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        requestClearExcel();
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void requestClearExcel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                && checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            pendingClearRequest = true;
            requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE},
                    STORAGE_PERMISSION_REQUEST_CODE);
            return;
        }
        performClearExcel();
    }

    private void performClearExcel() {
        File dir = getDownloadDir();
        if (!dir.exists()) {
            Toast.makeText(this, R.string.clear_excel_empty, Toast.LENGTH_SHORT).show();
            return;
        }
        int deleted = deleteDirectoryContents(dir);
        if (deleted == 0) {
            Toast.makeText(this, R.string.clear_excel_empty, Toast.LENGTH_SHORT).show();
        } else {
            Toast.makeText(this, getString(R.string.clear_excel_done, deleted), Toast.LENGTH_SHORT).show();
        }
    }

    private int deleteDirectoryContents(File dir) {
        File[] files = dir.listFiles();
        if (files == null || files.length == 0) {
            return 0;
        }
        int count = 0;
        for (File file : files) {
            if (file.isDirectory()) {
                count += deleteDirectoryContents(file);
                if (file.delete()) {
                    count++;
                }
            } else if (file.delete()) {
                count++;
            }
        }
        return count;
    }

    private void shareApp() {
        try {
            ApplicationInfo info = getPackageManager().getApplicationInfo(getPackageName(), 0);
            File sourceApk = new File(info.sourceDir);
            File shareDir = new File(getCacheDir(), "share");
            if (!shareDir.exists()) {
                shareDir.mkdirs();
            }
            File outApk = new File(shareDir, "DBGID-Resi-Exporter.apk");
            copyFile(sourceApk, outApk);
            Uri uri = FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", outApk);
            Intent intent = new Intent(Intent.ACTION_SEND);
            intent.setType("application/vnd.android.package-archive");
            intent.putExtra(Intent.EXTRA_STREAM, uri);
            intent.putExtra(Intent.EXTRA_TEXT, getString(R.string.share_app_text));
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(intent, getString(R.string.share_app_title)));
        } catch (Exception e) {
            Toast.makeText(this, R.string.share_app_failed, Toast.LENGTH_SHORT).show();
        }
    }

    private void copyFile(File source, File target) throws IOException {
        java.io.InputStream input = null;
        java.io.OutputStream output = null;
        try {
            input = new java.io.FileInputStream(source);
            output = new java.io.FileOutputStream(target);
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) != -1) {
                output.write(buffer, 0, read);
            }
        } finally {
            if (input != null) {
                try {
                    input.close();
                } catch (IOException ignored) {
                }
            }
            if (output != null) {
                try {
                    output.close();
                } catch (IOException ignored) {
                }
            }
        }
    }

    private void enqueueDownload(String url, String userAgent, String contentDisposition, String mimeType) {
        if (downloadManager == null) {
            Toast.makeText(this, R.string.download_failed, Toast.LENGTH_SHORT).show();
            return;
        }
        String fileName = URLUtil.guessFileName(url, contentDisposition, mimeType);
        java.io.File dir = new java.io.File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                "DBGID-XLXS"
        );
        if (!dir.exists()) {
            dir.mkdirs();
        }
        DownloadManager.Request request = new DownloadManager.Request(Uri.parse(url));
        request.setMimeType(mimeType);
        request.addRequestHeader("User-Agent", userAgent);
        request.setTitle(fileName);
        request.setDescription(getString(R.string.download_start));
        request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
        request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, "DBGID-XLXS/" + fileName);
        lastDownloadId = downloadManager.enqueue(request);
        Toast.makeText(this, R.string.download_enqueued, Toast.LENGTH_SHORT).show();
    }

    private void showDownloadCompleteDialog(final Uri fileUri, final String mimeType) {
        if (fileUri == null) {
            Toast.makeText(this, R.string.download_failed, Toast.LENGTH_SHORT).show();
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.download_complete_title)
                .setMessage(R.string.download_complete_message)
                .setPositiveButton(R.string.open_file, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        openDownloadedFile(fileUri, mimeType);
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void showDownloadNotification(Uri fileUri, String mimeType, String title) {
        if (fileUri == null) {
            return;
        }
        Intent openIntent = new Intent(Intent.ACTION_VIEW);
        openIntent.setDataAndType(fileUri, mimeType != null ? mimeType : "*/*");
        openIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }
        PendingIntent openPendingIntent = PendingIntent.getActivity(this, 0, openIntent, flags);

        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, DOWNLOAD_CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentTitle(getString(R.string.download_complete_title))
                .setContentText(title != null ? title : getString(R.string.download_complete_text))
                .setContentIntent(openPendingIntent)
                .setAutoCancel(true)
                .addAction(android.R.drawable.ic_menu_view, getString(R.string.open_file), openPendingIntent);

        NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager != null) {
            manager.notify((int) (System.currentTimeMillis() & 0x7fffffff), builder.build());
        }
    }

    private void requestBlobDownload(String blobUrl, String contentDispositionOrFileName, String mimeType) {
        if (blobUrl == null || blobUrl.length() == 0) {
            return;
        }
        String resolvedName = resolveBlobFileName(blobUrl, contentDispositionOrFileName, mimeType);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                && checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            pendingBlobUrl = blobUrl;
            pendingBlobFileName = resolvedName;
            pendingBlobMimeType = mimeType;
            requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE},
                    STORAGE_PERMISSION_REQUEST_CODE);
            return;
        }
        fetchBlobAndSave(blobUrl, resolvedName, mimeType);
    }

    private void fetchBlobAndSave(String blobUrl, String fileName, String mimeType) {
        String safeUrl = escapeJsString(blobUrl);
        String safeName = escapeJsString(fileName);
        String safeMime = escapeJsString(mimeType != null ? mimeType : "");
        String js = "(function(){"
                + "var url='" + safeUrl + "';"
                + "var fileName='" + safeName + "';"
                + "var mimeType='" + safeMime + "';"
                + "var xhr=new XMLHttpRequest();"
                + "xhr.open('GET', url, true);"
                + "xhr.responseType='blob';"
                + "xhr.onload=function(){"
                + "if (this.status===200 || this.status===0){"
                + "var blob=this.response;"
                + "var reader=new FileReader();"
                + "reader.onloadend=function(){"
                + "var data=reader.result||'';"
                + "var base64=data.split(',')[1]||'';"
                + "window.AndroidBlobDownloader.saveBase64(base64, fileName, mimeType||(blob&&blob.type?blob.type:''));"
                + "};"
                + "reader.readAsDataURL(blob);"
                + "}else{window.AndroidBlobDownloader.onError('status_'+this.status);}"
                + "};"
                + "xhr.onerror=function(){window.AndroidBlobDownloader.onError('xhr_error');};"
                + "xhr.send();"
                + "})();";
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            webView.evaluateJavascript(js, null);
        } else {
            webView.loadUrl("javascript:" + js);
        }
    }

    private void injectBlobDownloadSupport() {
        String js = "(function(){"
                + "if(window.__dbg_blob_installed){return;}"
                + "window.__dbg_blob_installed=true;"
                + "document.addEventListener('click',function(e){"
                + "var el=e.target;"
                + "while(el&&el.tagName!=='A'){el=el.parentElement;}"
                + "if(!el){return;}"
                + "var href=el.getAttribute('href');"
                + "if(href&&href.indexOf('blob:')===0){"
                + "e.preventDefault();"
                + "var name=el.getAttribute('download')||'download';"
                + "var type=el.getAttribute('type')||'';"
                + "window.AndroidBlobDownloader.requestBlobDownload(href,name,type);"
                + "}"
                + "},true);"
                + "})();";
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            webView.evaluateJavascript(js, null);
        } else {
            webView.loadUrl("javascript:" + js);
        }
    }

    private void openDownloadedFile(Uri fileUri, String mimeType) {
        Intent intent = new Intent(Intent.ACTION_VIEW);
        intent.setDataAndType(fileUri, mimeType != null ? mimeType : "*/*");
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            startActivity(intent);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, R.string.open_file_missing, Toast.LENGTH_SHORT).show();
        }
    }

    private void saveBase64File(final String base64Data, final String fileName, final String mimeType) {
        if (base64Data == null || base64Data.length() == 0) {
            showToast(R.string.download_failed);
            return;
        }
        final String safeName = ensureFileName(fileName, mimeType);
        final String safeMime = (mimeType == null || mimeType.length() == 0)
                ? "application/octet-stream" : mimeType;
        new Thread(new Runnable() {
            @Override
            public void run() {
                byte[] data;
                try {
                    data = Base64.decode(base64Data, Base64.DEFAULT);
                } catch (IllegalArgumentException e) {
                    showToast(R.string.download_failed);
                    return;
                }
                File dir = getDownloadDir();
                if (!dir.exists() && !dir.mkdirs()) {
                    showToast(R.string.download_failed);
                    return;
                }
                final File outFile = makeUniqueFile(new File(dir, safeName));
                FileOutputStream output = null;
                try {
                    output = new FileOutputStream(outFile);
                    output.write(data);
                    output.flush();
                } catch (IOException e) {
                    showToast(R.string.download_failed);
                    return;
                } finally {
                    if (output != null) {
                        try {
                            output.close();
                        } catch (IOException ignored) {
                        }
                    }
                }
                final Uri fileUri = getFileUri(outFile);
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        Toast.makeText(MainActivity.this, R.string.download_complete_title, Toast.LENGTH_SHORT).show();
                        showDownloadCompleteDialog(fileUri, safeMime);
                        showDownloadNotification(fileUri, safeMime, outFile.getName());
                    }
                });
            }
        }).start();
    }

    private void showToast(final int resId) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                Toast.makeText(MainActivity.this, resId, Toast.LENGTH_SHORT).show();
            }
        });
    }

    private File getDownloadDir() {
        return new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "DBGID-XLXS");
    }

    private Uri getFileUri(File file) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            return FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", file);
        }
        return Uri.fromFile(file);
    }

    private File makeUniqueFile(File file) {
        if (!file.exists()) {
            return file;
        }
        String name = file.getName();
        String base = name;
        String ext = "";
        int dot = name.lastIndexOf('.');
        if (dot > 0) {
            base = name.substring(0, dot);
            ext = name.substring(dot);
        }
        int index = 1;
        File candidate;
        do {
            candidate = new File(file.getParentFile(), base + "(" + index + ")" + ext);
            index++;
        } while (candidate.exists());
        return candidate;
    }

    private String ensureFileName(String fileName, String mimeType) {
        String name = fileName != null ? fileName.trim() : "";
        if (name.length() == 0) {
            name = "download";
        }
        name = sanitizeFileName(name);
        if (name.indexOf('.') == -1 && mimeType != null && mimeType.length() > 0) {
            String ext = MimeTypeMap.getSingleton().getExtensionFromMimeType(mimeType);
            if (ext != null && ext.length() > 0) {
                name = name + "." + ext;
            }
        }
        return name;
    }

    private String resolveBlobFileName(String blobUrl, String contentDispositionOrFileName, String mimeType) {
        String name = null;
        if (contentDispositionOrFileName != null && contentDispositionOrFileName.trim().length() > 0) {
            String trimmed = contentDispositionOrFileName.trim();
            if (trimmed.contains("filename=") || trimmed.contains("filename*=")) {
                name = URLUtil.guessFileName(blobUrl, trimmed, mimeType);
            } else {
                name = trimmed;
            }
        }
        if (name == null || name.length() == 0) {
            name = URLUtil.guessFileName(blobUrl, null, mimeType);
        }
        return ensureFileName(name, mimeType);
    }

    private String sanitizeFileName(String fileName) {
        return fileName.replaceAll("[\\\\/:*?\"<>|]", "_");
    }

    private String escapeJsString(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("\\", "\\\\")
                .replace("'", "\\'")
                .replace("\n", "\\n")
                .replace("\r", "");
    }

    private boolean isBlobUrl(String url) {
        return url != null && url.startsWith("blob:");
    }

    private class BlobDownloadInterface {
        @JavascriptInterface
        public void requestBlobDownload(final String blobUrl, final String fileName, final String mimeType) {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    MainActivity.this.requestBlobDownload(blobUrl, fileName, mimeType);
                }
            });
        }

        @JavascriptInterface
        public void saveBase64(final String base64Data, final String fileName, final String mimeType) {
            saveBase64File(base64Data, fileName, mimeType);
        }

        @JavascriptInterface
        public void onError(String message) {
            showToast(R.string.download_failed);
        }
    }

    private void openFileChooserLegacy(ValueCallback<Uri> uploadMsg, String acceptType) {
        filePathCallbackLegacy = uploadMsg;
        Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType(acceptType == null || acceptType.length() == 0 ? "*/*" : acceptType);
        try {
            startActivityForResult(Intent.createChooser(intent, getString(R.string.file_chooser_title)),
                    FILE_CHOOSER_REQUEST_CODE);
        } catch (ActivityNotFoundException e) {
            filePathCallbackLegacy = null;
            Toast.makeText(this, R.string.file_chooser_missing, Toast.LENGTH_SHORT).show();
        }
    }

    private void loadHome() {
        if (isOnline()) {
            offlineView.setVisibility(View.GONE);
            webView.loadUrl(getString(R.string.web_url));
        } else {
            showOffline();
        }
    }

    private void showOffline() {
        offlineView.setVisibility(View.VISIBLE);
    }

    private boolean isOnline() {
        ConnectivityManager cm = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
        if (cm == null) {
            return false;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Network network = cm.getActiveNetwork();
            if (network == null) {
                return false;
            }
            NetworkCapabilities caps = cm.getNetworkCapabilities(network);
            return caps != null && (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                    || caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
                    || caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET));
        } else {
            NetworkInfo info = cm.getActiveNetworkInfo();
            return info != null && info.isConnected();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        if (requestCode == STORAGE_PERMISSION_REQUEST_CODE) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                if (pendingDownloadUrl != null) {
                    enqueueDownload(pendingDownloadUrl, pendingDownloadUserAgent,
                            pendingDownloadContentDisposition, pendingDownloadMimeType);
                }
                if (pendingBlobUrl != null) {
                    fetchBlobAndSave(pendingBlobUrl, pendingBlobFileName, pendingBlobMimeType);
                }
                if (pendingClearRequest) {
                    performClearExcel();
                }
            } else {
                Toast.makeText(this, R.string.download_permission_denied, Toast.LENGTH_SHORT).show();
            }
            pendingDownloadUrl = null;
            pendingDownloadUserAgent = null;
            pendingDownloadContentDisposition = null;
            pendingDownloadMimeType = null;
            pendingBlobUrl = null;
            pendingBlobFileName = null;
            pendingBlobMimeType = null;
            pendingClearRequest = false;
            return;
        }
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
    }
}
