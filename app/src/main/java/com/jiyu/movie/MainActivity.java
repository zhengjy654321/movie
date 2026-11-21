package com.jiyu.movie;

import android.app.Activity;
import android.content.Context;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class MainActivity extends Activity {
    private final Handler clock = new Handler(Looper.getMainLooper());
    private FrameLayout root;
    private LinearLayout searchScreen;
    private EditText query;
    private LinearLayout titles;
    private TextView searchStatus;
    private Button previous;
    private Button next;
    private WebView searchBrowser;
    private WebView playerBrowser;
    private FrameLayout playback;
    private TextView loading;
    private TextView fullscreenMask;
    private Button coverButton;
    private View fullscreen;
    private WebChromeClient.CustomViewCallback fullscreenCallback;
    private Uri source;
    private String searchScript;
    private String playerScript;
    private String resultKey = "";
    private String lastSearch = "";
    private long searchStarted;
    private long playerStarted;
    private int searchRequest;
    private int playerRequest;
    private int visualRevision;
    private boolean waitingForPage;
    private boolean playerRevealed;
    private boolean revealRequested;
    private boolean playbackAd;
    private boolean playbackManual;

    @Override
    public void onCreate(Bundle saved) {
        super.onCreate(saved);
        getWindow().setStatusBarColor(Color.WHITE);
        getWindow().setNavigationBarColor(Color.WHITE);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR |
            View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        source = Uri.parse(asset("source-origin.txt").trim());
        searchScript = asset("search.js");
        playerScript = asset("player.js");
        root = new FrameLayout(this);
        root.setBackgroundColor(Color.WHITE);
        setContentView(root);
        makeSearchScreen();
        if (saved != null) {
            String savedQuery = saved.getString("query", "");
            String lastSearch = saved.getString("lastSearch", "");
            query.setText(savedQuery);
            if (!lastSearch.isEmpty()) beginSearch(lastSearch);
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        out.putString("query", query.getText().toString());
        out.putString("lastSearch", lastSearch);
    }

    private String asset(String file) {
        try (InputStream input = getAssets().open(file)) {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            for (int size; (size = input.read(buffer)) != -1; ) bytes.write(buffer, 0, size);
            return new String(bytes.toByteArray(), StandardCharsets.UTF_8);
        } catch (IOException ignored) {
            return "";
        }
    }

    private int dp(int n) {
        return Math.round(n * getResources().getDisplayMetrics().density);
    }

    private void makeSearchScreen() {
        searchScreen = new LinearLayout(this);
        searchScreen.setOrientation(LinearLayout.VERTICAL);
        searchScreen.setBackgroundColor(Color.WHITE);
        searchScreen.setPadding(dp(22), dp(26), dp(22), dp(8));
        root.addView(searchScreen, new FrameLayout.LayoutParams(-1, -1));

        query = new EditText(this);
        query.setSingleLine(true);
        query.setInputType(InputType.TYPE_CLASS_TEXT);
        query.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        query.setFilters(new android.text.InputFilter[] {new android.text.InputFilter.LengthFilter(20)});
        query.setHint("搜索");
        query.setTextSize(18);
        query.setSelectAllOnFocus(false);
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        searchScreen.addView(row);
        row.addView(query, new LinearLayout.LayoutParams(0, dp(54), 1));
        Button submit = new Button(this);
        submit.setText("搜索");
        submit.setTextColor(0xff164b39);
        submit.setOnClickListener(view -> {
            String term = query.getText().toString().trim();
            if (!term.isEmpty()) beginSearch(term);
        });
        row.addView(submit);
        query.setOnEditorActionListener((view, action, event) -> {
            if (action != EditorInfo.IME_ACTION_SEARCH &&
                (event == null || event.getKeyCode() != android.view.KeyEvent.KEYCODE_ENTER)) return false;
            String term = query.getText().toString().trim();
            if (!term.isEmpty()) beginSearch(term);
            return true;
        });
        searchScreen.setFocusableInTouchMode(true);
        searchScreen.requestFocus();

        searchStatus = new TextView(this);
        searchStatus.setPadding(dp(5), dp(16), dp(5), 0);
        searchStatus.setVisibility(View.GONE);
        searchScreen.addView(searchStatus);
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(false);
        searchScreen.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(titles, new ScrollView.LayoutParams(-1, -2));
        LinearLayout controls = new LinearLayout(this);
        controls.setGravity(Gravity.CENTER);
        previous = new Button(this);
        next = new Button(this);
        previous.setText("上一页");
        next.setText("下一页");
        previous.setVisibility(View.GONE);
        next.setVisibility(View.GONE);
        previous.setOnClickListener(view -> turnPage("上一页"));
        next.setOnClickListener(view -> turnPage("下一页"));
        controls.addView(previous);
        controls.addView(next);
        searchScreen.addView(controls);
    }

    private boolean isSource(Uri url) {
        return "https".equalsIgnoreCase(url.getScheme()) && source.getHost() != null &&
            source.getHost().equalsIgnoreCase(url.getHost()) && source.getPort() == url.getPort();
    }

    private boolean isPlayback(Uri url) {
        String path = url.getPath();
        return isSource(url) && path != null && (path.startsWith("/play/") || path.equals("/watch") || path.startsWith("/watch/"));
    }

    private WebView makeBrowser() {
        WebView browser = new WebView(this);
        browser.setBackgroundColor(Color.BLACK);
        WebSettings settings = browser.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        settings.setJavaScriptCanOpenWindowsAutomatically(false);
        settings.setSupportMultipleWindows(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        settings.setMediaPlaybackRequiresUserGesture(true);
        String agent = settings.getUserAgentString();
        Matcher version = Pattern.compile("Chrome/([0-9.]+)").matcher(agent);
        if (version.find()) {
            settings.setUserAgentString("Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/" + version.group(1) + " Safari/537.36");
        }
        return browser;
    }

    private final class PageClient extends WebViewClient {
        private final boolean playing;

        private PageClient(boolean playing) {
            this.playing = playing;
        }

        @Override
        public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
            if (!request.isForMainFrame()) return false;
            Uri url = request.getUrl();
            return playing ? !isPlayback(url) : !isSource(url) ||
                url.getPath() == null || !url.getPath().startsWith("/search/");
        }

        @Override
        public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) {
            if (playing && view == playerBrowser) {
                playerRevealed = false;
                revealRequested = false;
                visualRevision++;
                loading.setVisibility(View.VISIBLE);
            }
        }

        @Override
        public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
            if (!request.isForMainFrame()) return;
            if (playing && view == playerBrowser) {
                playerRequest++;
                loading.setText("无法加载播放器");
                loading.setVisibility(View.VISIBLE);
            } else if (!playing && view == searchBrowser) {
                searchRequest++;
                showSearchMessage("搜索失败，请重试");
            }
        }
    }

    private void showSearchMessage(String text) {
        searchStatus.setText(text);
        searchStatus.setVisibility(text.isEmpty() ? View.GONE : View.VISIBLE);
    }

    private void beginSearch(String term) {
        lastSearch = term;
        ((InputMethodManager)getSystemService(Context.INPUT_METHOD_SERVICE))
            .hideSoftInputFromWindow(query.getWindowToken(), 0);
        if (source.getHost() == null) {
            showSearchMessage("缺少播放配置");
            return;
        }
        titles.removeAllViews();
        previous.setVisibility(View.GONE);
        next.setVisibility(View.GONE);
        fetchPage(term, 1);
    }

    private int pageNumber = 1;
    private final java.util.concurrent.ExecutorService searches = java.util.concurrent.Executors.newCachedThreadPool();

    private void turnPage(String direction) {
        fetchPage(lastSearch, pageNumber + ("下一页".equals(direction) ? 1 : -1));
    }

    private void fetchPage(String term, int page) {
        int request = ++searchRequest;
        previous.setEnabled(false);
        next.setEnabled(false);
        showSearchMessage("正在搜索…");
        searches.execute(() -> {
            try {
                JSONObject state = SearchClient.search(source.toString(), term, page);
                JSONArray items = state.getJSONArray("items");
                clock.post(() -> {
                    if (request != searchRequest || isDestroyed()) return;
                    pageNumber = page;
                    renderResults(items, state);
                    if (items.length() == 0) showSearchMessage("没有匹配的标题");
                });
            } catch (Exception error) {
                clock.post(() -> {
                    if (request != searchRequest || isDestroyed()) return;
                    previous.setEnabled(true);
                    next.setEnabled(true);
                    showSearchMessage("搜索请求失败，请检查网络后重试");
                });
            }
        });
    }

    private void renderResults(JSONArray items, JSONObject state) {
        titles.removeAllViews();
        for (int i = 0; i < items.length(); i++) {
            JSONObject item = items.optJSONObject(i);
            if (item == null) continue;
            Uri url = Uri.parse(item.optString("url"));
            if (!isPlayback(url)) continue;
            TextView title = new TextView(this);
            title.setText(item.optString("name"));
            title.setTextColor(0xff17202e);
            title.setTextSize(17);
            title.setGravity(Gravity.CENTER_VERTICAL);
            title.setMinHeight(dp(49));
            title.setPadding(dp(5), dp(9), dp(8), dp(9));
            title.setFocusable(true);
            title.setOnClickListener(view -> openPlayer(url));
            titles.addView(title, new LinearLayout.LayoutParams(-1, -2));
            View line = new View(this);
            line.setBackgroundColor(0xffe6e8ed);
            titles.addView(line, new LinearLayout.LayoutParams(-1, dp(1)));
        }
        showSearchMessage("");
        previous.setVisibility(state.optBoolean("previous") ? View.VISIBLE : View.GONE);
        next.setVisibility(state.optBoolean("next") ? View.VISIBLE : View.GONE);
        previous.setEnabled(true);
        next.setEnabled(true);
        ((ScrollView) titles.getParent()).scrollTo(0, 0);
    }

    private TextView blackCover() {
        TextView view = new TextView(this);
        view.setBackgroundColor(Color.BLACK);
        view.setTextColor(Color.WHITE);
        view.setGravity(Gravity.CENTER);
        view.setClickable(true);
        return view;
    }

    private void openPlayer(Uri url) {
        if (playerBrowser != null) return;
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().setStatusBarColor(Color.BLACK);
        getWindow().setNavigationBarColor(Color.BLACK);
        getWindow().getDecorView().setSystemUiVisibility(0);
        playback = new FrameLayout(this);
        playback.setBackgroundColor(Color.BLACK);
        root.addView(playback, new FrameLayout.LayoutParams(-1, -1));
        searchScreen.setVisibility(View.INVISIBLE);
        playerBrowser = makeBrowser();
        playerBrowser.setWebViewClient(new PageClient(true));
        playerBrowser.setWebChromeClient(new PlayerChrome());
        playback.addView(playerBrowser, new FrameLayout.LayoutParams(-1, -1));

        coverButton = new Button(this);
        coverButton.setText("遮挡");
        coverButton.setTextSize(12);
        coverButton.setVisibility(View.GONE);
        coverButton.setOnClickListener(view -> toggleCover());
        FrameLayout.LayoutParams buttonLayout = new FrameLayout.LayoutParams(-2, -2, Gravity.END | Gravity.TOP);
        buttonLayout.setMargins(dp(8), dp(5), dp(8), dp(5));
        playback.addView(coverButton, buttonLayout);

        loading = blackCover();
        playback.addView(loading, new FrameLayout.LayoutParams(-1, -1));
        playerStarted = SystemClock.uptimeMillis();
        int request = ++playerRequest;
        playerBrowser.loadUrl(url.toString());
        clock.postDelayed(() -> checkPlayer(request), 180);
    }

    private void toggleCover() {
        if (playerBrowser == null) return;
        playerBrowser.evaluateJavascript("window.__moviePlayer?.manual()", answer -> {
            if (coverButton == null) return;
            playbackManual = "true".equals(answer);
            coverButton.setText(playbackManual ? "取消遮挡" : "遮挡");
        });
    }

    private void checkPlayer(int request) {
        if (request != playerRequest || playerBrowser == null) return;
        if (!isPlayback(Uri.parse(playerBrowser.getUrl() == null ? "" : playerBrowser.getUrl()))) {
            clock.postDelayed(() -> checkPlayer(request), 200);
            return;
        }
        playerBrowser.evaluateJavascript(playerScript, answer -> {
            if (request != playerRequest || playerBrowser == null) return;
            try {
                JSONObject state = new JSONObject(answer);
                playbackAd = state.optBoolean("ad");
                playbackManual = state.optBoolean("manual");
                coverButton.setText(playbackManual ? "取消遮挡" : "遮挡");
                updateFullscreenMask();
                if (state.optBoolean("ready") && !playerRevealed && !revealRequested) {
                    revealRequested = true;
                    int revision = visualRevision;
                    playerBrowser.postVisualStateCallback(revision, new WebView.VisualStateCallback() {
                        @Override public void onComplete(long id) {
                            if (request != playerRequest || id != visualRevision || loading == null) return;
                            playerRevealed = true;
                            loading.setVisibility(View.GONE);
                            coverButton.setVisibility(View.VISIBLE);
                        }
                    });
                }
            } catch (JSONException ignored) {
                // The opaque native loading layer remains until the player reports ready.
            }
            if (!playerRevealed && SystemClock.uptimeMillis() - playerStarted > 18000) {
                loading.setText("播放器加载较慢");
            }
            clock.postDelayed(() -> checkPlayer(request), playerRevealed ? 175 : 220);
        });
    }

    private void updateFullscreenMask() {
        if (fullscreenMask == null) return;
        fullscreenMask.setText(playbackAd ? "广告播放中" : "已遮挡");
        fullscreenMask.setVisibility(playbackAd || playbackManual ? View.VISIBLE : View.GONE);
    }

    private final class PlayerChrome extends WebChromeClient {
        @Override
        public void onShowCustomView(View view, CustomViewCallback callback) {
            if (fullscreen != null || !playerRevealed) {
                callback.onCustomViewHidden();
                return;
            }
            fullscreen = view;
            fullscreenCallback = callback;
            root.addView(view, new FrameLayout.LayoutParams(-1, -1));
            fullscreenMask = blackCover();
            root.addView(fullscreenMask, new FrameLayout.LayoutParams(-1, -1));
            updateFullscreenMask();
            getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN |
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        }

        @Override public void onHideCustomView() {
            hideFullscreen();
        }
    }

    private void hideFullscreen() {
        if (fullscreen == null) return;
        root.removeView(fullscreenMask);
        root.removeView(fullscreen);
        fullscreenMask = null;
        fullscreen = null;
        WebChromeClient.CustomViewCallback callback = fullscreenCallback;
        fullscreenCallback = null;
        callback.onCustomViewHidden();
        getWindow().getDecorView().setSystemUiVisibility(0);
    }

    private void closePlayer() {
        hideFullscreen();
        playerRequest++;
        visualRevision++;
        playerBrowser.stopLoading();
        playback.removeView(playerBrowser);
        playerBrowser.destroy();
        root.removeView(playback);
        playerBrowser = null;
        playback = null;
        coverButton = null;
        loading = null;
        playbackAd = playbackManual = revealRequested = playerRevealed = false;
        searchScreen.setVisibility(View.VISIBLE);
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().setStatusBarColor(Color.WHITE);
        getWindow().setNavigationBarColor(Color.WHITE);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR |
            View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
    }

    @Override public void onBackPressed() {
        if (fullscreen != null) hideFullscreen();
        else if (playerBrowser != null) closePlayer();
        else super.onBackPressed();
    }

    @Override protected void onPause() {
        if (playerBrowser != null) {
            playerBrowser.evaluateJavascript("document.querySelectorAll('video,audio').forEach(e => e.pause())", null);
            playerBrowser.onPause();
        }
        super.onPause();
    }

    @Override protected void onResume() {
        super.onResume();
        if (playerBrowser != null) playerBrowser.onResume();
    }

    @Override protected void onDestroy() {
        if (playerBrowser != null) closePlayer();
        searchRequest++;
        searches.shutdownNow();
        clock.removeCallbacksAndMessages(null);
        if (searchBrowser != null) {
            ((ViewGroup) searchBrowser.getParent()).removeView(searchBrowser);
            searchBrowser.destroy();
            searchBrowser = null;
        }
        super.onDestroy();
    }
}
