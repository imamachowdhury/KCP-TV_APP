package com.plugintv.livetv;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.text.Editable;
import android.text.TextWatcher;

import androidx.media3.common.util.UnstableApi;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.exoplayer.DefaultLoadControl;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.ui.PlayerView;

import android.util.Log;
import android.os.Handler;
import android.os.Looper;
import org.json.JSONObject;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public class MainActivity extends Activity {
    private static final String APP_NAME = "KCP TV";
    private static final String DEVELOPER_NAME = "Imam Ahmed Chowdhury";
    private static final String DEVELOPER_WEB = "imamahmed.net";
    private static final String PREFS = "plugintv_prefs";
    private static final String PREF_LAST_URL = "last_url";
    private static final String PREF_FAVORITES = "favorites";

    // Update Configuration
    private static final String GITHUB_USER = "imamachowdhury"; // Change to your GitHub username
    private static final String GITHUB_REPO = "KCP-TV_APP"; // Change to your repo name
    private static final String UPDATE_API = "https://api.github.com/repos/" + GITHUB_USER + "/" + GITHUB_REPO + "/releases/latest";

    private final List<Channel> allChannels = new ArrayList<>();
    private final List<Channel> visibleChannels = new ArrayList<>();
    private final List<String> categories = new ArrayList<>();
    private final Set<String> favorites = new HashSet<>();

    private SharedPreferences prefs;
    private ExoPlayer player;
    private PlayerView playerView;
    private ChannelAdapter adapter;
    private EditText search;
    private TextView nowPlaying;
    private TextView status;
    private TextView countText;
    private ProgressBar loading;
    private Button favoriteToggle;
    private Button showFavorites;
    private Button retryButton;
    private LinearLayout categoryList;

    private Channel currentChannel;
    private boolean favoritesOnly = false;
    private String currentCategory = "All";
    private String currentQuery = "";

    private final int bg = Color.rgb(13, 17, 21);
    private final int panel = Color.rgb(21, 27, 33);
    private final int panel2 = Color.rgb(15, 20, 25);
    private final int text = Color.rgb(245, 247, 250);
    private final int muted = Color.rgb(168, 179, 191);
    private final int accent = Color.rgb(18, 184, 134);
    private final int danger = Color.rgb(254, 202, 202);

    private final Handler searchHandler = new Handler(Looper.getMainLooper());
    private Runnable searchRunnable;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        favorites.addAll(prefs.getStringSet(PREF_FAVORITES, new HashSet<>()));
        allChannels.addAll(readChannels());
        
        // Expert Sort: Alphabetical order
        Collections.sort(allChannels, (c1, c2) -> c1.name.compareToIgnoreCase(c2.name));
        
        visibleChannels.addAll(allChannels);
        
        checkForUpdates();

        // Smart behavior: Skip splash if we have a last played channel
        if (prefs.contains(PREF_LAST_URL)) {
            buildPlayer();
            buildUi();
            restoreOrPlayFirstChannel();
        } else {
            showOpeningPage();
        }
    }

    @UnstableApi
    private void buildPlayer() {
        if (player != null) return;

        DefaultHttpDataSource.Factory dataSourceFactory = new DefaultHttpDataSource.Factory()
            .setUserAgent("Mozilla/5.0 " + APP_NAME + " Android")
            .setConnectTimeoutMs(15000)
            .setReadTimeoutMs(30000)
            .setAllowCrossProtocolRedirects(true);

        DefaultLoadControl loadControl = new DefaultLoadControl.Builder()
            .setBufferDurationsMs(25000, 70000, 1500, 3500)
            .build();

        player = new ExoPlayer.Builder(this)
            .setMediaSourceFactory(new DefaultMediaSourceFactory(this).setDataSourceFactory(dataSourceFactory))
            .setLoadControl(loadControl)
            .build();

        player.addListener(new Player.Listener() {
            @Override
            public void onPlaybackStateChanged(int state) {
                if (loading == null) return;
                if (state == Player.STATE_BUFFERING) {
                    loading.setVisibility(View.VISIBLE);
                    setStatus("Buffering...", false);
                } else if (state == Player.STATE_READY) {
                    loading.setVisibility(View.GONE);
                    setStatus("", false);
                } else if (state == Player.STATE_ENDED) {
                    loading.setVisibility(View.GONE);
                    setStatus("This live stream ended. Try another channel.", true);
                }
            }

            @Override
            public void onPlayerError(PlaybackException error) {
                if (loading == null) return;
                loading.setVisibility(View.GONE);
                setStatus("Channel failed: " + readableError(error), true);
            }
        });

        if (playerView != null) {
            playerView.setPlayer(player);
        }
    }

    private void releasePlayer() {
        if (player != null) {
            player.release();
            player = null;
        }
    }

    private void checkForUpdates() {
        new Thread(() -> {
            try {
                URL url = new URL(UPDATE_API);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("GET");
                conn.setRequestProperty("Accept", "application/vnd.github.v3+json");
                
                if (conn.getResponseCode() == 200) {
                    BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()));
                    StringBuilder response = new StringBuilder();
                    String line;
                    while ((line = reader.readLine()) != null) response.append(line);
                    reader.close();

                    JSONObject json = new JSONObject(response.toString());
                    String latestVersion = json.getString("tag_name").replace("v", "");
                    String downloadUrl = json.getString("html_url");
                    
                    PackageInfo pInfo = getPackageManager().getPackageInfo(getPackageName(), 0);
                    String currentVersion = pInfo.versionName;

                    if (isNewer(latestVersion, currentVersion)) {
                        new Handler(Looper.getMainLooper()).post(() -> showUpdateDialog(latestVersion, downloadUrl));
                    }
                }
            } catch (Exception e) {
                Log.e("KCPTV", "Update check failed", e);
            }
        }).start();
    }

    private boolean isNewer(String latest, String current) {
        try {
            String[] v1 = latest.split("\\.");
            String[] v2 = current.split("\\.");
            for (int i = 0; i < Math.min(v1.length, v2.length); i++) {
                int n1 = Integer.parseInt(v1[i]);
                int n2 = Integer.parseInt(v2[i]);
                if (n1 > n2) return true;
                if (n1 < n2) return false;
            }
            return v1.length > v2.length;
        } catch (Exception e) {
            return !latest.equals(current);
        }
    }

    private void showUpdateDialog(String version, String url) {
        new AlertDialog.Builder(this)
            .setTitle("Update Available")
            .setMessage("A new version (" + version + ") is available on GitHub. Would you like to update now?")
            .setPositiveButton("Update", (dialog, which) -> {
                Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
                startActivity(intent);
            })
            .setNegativeButton("Later", null)
            .show();
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(bg);
        root.setPadding(dp(12), dp(12), dp(12), dp(12));
        
        // Android TV: Support D-pad navigation
        root.setFocusable(false);

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);

        ImageView logo = new ImageView(this);
        logo.setImageResource(getResources().getIdentifier("ic_logo", "drawable", getPackageName()));
        header.addView(logo, new LinearLayout.LayoutParams(dp(40), dp(40)));

        LinearLayout titleBlock = new LinearLayout(this);
        titleBlock.setOrientation(LinearLayout.VERTICAL);
        titleBlock.setPadding(dp(12), 0, 0, 0);

        TextView title = new TextView(this);
        title.setText(APP_NAME);
        title.setTextColor(text);
        title.setTextSize(26);
        titleBlock.addView(title, new LinearLayout.LayoutParams(-1, -2));

        countText = new TextView(this);
        countText.setTextColor(muted);
        countText.setTextSize(13);
        titleBlock.addView(countText, new LinearLayout.LayoutParams(-1, -2));

        header.addView(titleBlock, new LinearLayout.LayoutParams(0, -2, 1f));

        retryButton = makeButton("Retry");
        retryButton.setOnClickListener(v -> {
            if (currentChannel != null) {
                playChannel(currentChannel);
            }
        });
        header.addView(retryButton, new LinearLayout.LayoutParams(dp(84), dp(42)));

        root.addView(header, new LinearLayout.LayoutParams(-1, -2));

        nowPlaying = new TextView(this);
        nowPlaying.setTextColor(muted);
        nowPlaying.setTextSize(14);
        nowPlaying.setPadding(0, dp(6), 0, dp(8));
        root.addView(nowPlaying, new LinearLayout.LayoutParams(-1, -2));

        playerView = new PlayerView(this);
        playerView.setPlayer(player);
        playerView.setUseController(true);
        playerView.setControllerAutoShow(true);
        playerView.setBackgroundColor(Color.BLACK);
        playerView.setKeepScreenOn(true);
        
        // Stable: Hide system bars when playing
        playerView.setControllerVisibilityListener(new PlayerView.ControllerVisibilityListener() {
            @Override
            public void onVisibilityChanged(int visibility) {
                if (visibility == View.GONE) {
                    hideSystemUI();
                } else {
                    showSystemUI();
                }
            }
        });
        
        root.addView(playerView, new LinearLayout.LayoutParams(-1, 0, 1.05f));

        loading = new ProgressBar(this);
        loading.setVisibility(View.GONE);
        root.addView(loading, new LinearLayout.LayoutParams(-1, dp(30)));

        status = new TextView(this);
        status.setTextColor(muted);
        status.setTextSize(14);
        status.setPadding(0, dp(4), 0, dp(8));
        root.addView(status, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout controls = new LinearLayout(this);
        controls.setOrientation(LinearLayout.HORIZONTAL);
        controls.setGravity(Gravity.CENTER_VERTICAL);

        favoriteToggle = makeButton("Star");
        favoriteToggle.setOnClickListener(v -> toggleFavorite());
        favoriteToggle.setFocusable(true);
        controls.addView(favoriteToggle, new LinearLayout.LayoutParams(dp(88), dp(42)));

        showFavorites = makeButton("Favorites");
        showFavorites.setOnClickListener(v -> {
            favoritesOnly = !favoritesOnly;
            updateFilters();
        });
        showFavorites.setFocusable(true);
        controls.addView(showFavorites, new LinearLayout.LayoutParams(dp(118), dp(42)));

        search = new EditText(this);
        search.setHint("Search channels");
        search.setHintTextColor(muted);
        search.setTextColor(text);
        search.setSingleLine(true);
        search.setPadding(dp(12), 0, dp(12), 0);
        search.setBackgroundColor(panel2);
        search.setFocusable(true);
        controls.addView(search, new LinearLayout.LayoutParams(0, dp(42), 1f));

        root.addView(controls, new LinearLayout.LayoutParams(-1, -2));

        android.widget.HorizontalScrollView categoryScroll = new android.widget.HorizontalScrollView(this);
        categoryScroll.setPadding(0, dp(8), 0, dp(8));
        categoryList = new LinearLayout(this);
        categoryList.setOrientation(LinearLayout.HORIZONTAL);
        categoryScroll.addView(categoryList);
        root.addView(categoryScroll, new LinearLayout.LayoutParams(-1, -2));

        ListView listView = new ListView(this);
        listView.setBackgroundColor(panel);
        listView.setDividerHeight(1);
        adapter = new ChannelAdapter();
        listView.setAdapter(adapter);
        root.addView(listView, new LinearLayout.LayoutParams(-1, 0, 1.25f));

        listView.setOnItemClickListener((parent, view, position, id) -> {
            hideKeyboard(search);
            playChannel(visibleChannels.get(position));
        });

        search.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                currentQuery = s.toString();
                
                searchHandler.removeCallbacks(searchRunnable);
                searchRunnable = () -> updateFilters();
                searchHandler.postDelayed(searchRunnable, 300);
            }

            @Override
            public void afterTextChanged(Editable s) {}
        });

        setContentView(root);
        updateFilters();
    }

    private void showOpeningPage() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        root.setBackgroundColor(bg);
        root.setPadding(dp(26), dp(26), dp(26), dp(26));

        ImageView bigLogo = new ImageView(this);
        bigLogo.setImageResource(getResources().getIdentifier("ic_logo", "drawable", getPackageName()));
        root.addView(bigLogo, new LinearLayout.LayoutParams(dp(120), dp(120)));

        TextView appName = new TextView(this);
        appName.setText(APP_NAME);
        appName.setTextColor(text);
        appName.setTextSize(42);
        appName.setGravity(Gravity.CENTER);
        appName.setPadding(0, dp(16), 0, 0);
        root.addView(appName, new LinearLayout.LayoutParams(-1, -2));

        TextView tagline = new TextView(this);
        tagline.setText("Live TV");
        tagline.setTextColor(accent);
        tagline.setTextSize(18);
        tagline.setGravity(Gravity.CENTER);
        tagline.setPadding(0, dp(8), 0, dp(32));
        root.addView(tagline, new LinearLayout.LayoutParams(-1, -2));

        TextView developer = new TextView(this);
        developer.setText("Developer: " + DEVELOPER_NAME);
        developer.setTextColor(muted);
        developer.setTextSize(16);
        developer.setGravity(Gravity.CENTER);
        root.addView(developer, new LinearLayout.LayoutParams(-1, -2));

        TextView website = new TextView(this);
        website.setText("Website: " + DEVELOPER_WEB);
        website.setTextColor(muted);
        website.setTextSize(16);
        website.setGravity(Gravity.CENTER);
        website.setPadding(0, dp(8), 0, dp(34));
        root.addView(website, new LinearLayout.LayoutParams(-1, -2));

        Button openButton = makeButton("Open TV");
        openButton.setTextSize(16);
        openButton.setOnClickListener(v -> {
            buildUi();
            restoreOrPlayFirstChannel();
        });
        root.addView(openButton, new LinearLayout.LayoutParams(dp(180), dp(52)));

        setContentView(root);
    }

    private Button makeButton(String label) {
        Button button = new Button(this);
        button.setText(label);
        button.setTextColor(text);
        button.setTextSize(13);
        button.setAllCaps(false);
        button.setBackgroundColor(panel2);
        button.setFocusable(true);
        
        // Expert: TV Focus Effect
        button.setOnFocusChangeListener((v, hasFocus) -> {
            v.setBackgroundColor(hasFocus ? accent : panel2);
            v.setScaleX(hasFocus ? 1.1f : 1.0f);
            v.setScaleY(hasFocus ? 1.1f : 1.0f);
        });
        return button;
    }

    private void restoreOrPlayFirstChannel() {
        if (allChannels.isEmpty()) {
            setStatus("No channels found in channels.m3u", true);
            return;
        }

        String lastUrl = prefs.getString(PREF_LAST_URL, "");

        for (Channel channel : allChannels) {
            if (channel.url.equals(lastUrl)) {
                playChannel(channel);
                return;
            }
        }

        playChannel(allChannels.get(0));
    }

    private void playChannel(Channel channel) {
        currentChannel = channel;
        prefs.edit().putString(PREF_LAST_URL, channel.url).apply();
        updateFavoriteButton();
        nowPlaying.setText("Now playing: " + channel.name);
        setStatus("Loading...", false);
        loading.setVisibility(View.VISIBLE);

        MediaItem mediaItem = MediaItem.fromUri(Uri.parse(channel.url));
        
        player.stop();
        player.setMediaItem(mediaItem);
        player.prepare();
        player.setPlayWhenReady(true);
    }

    private void toggleFavorite() {
        if (currentChannel == null) {
            return;
        }

        if (favorites.contains(currentChannel.url)) {
            favorites.remove(currentChannel.url);
        } else {
            favorites.add(currentChannel.url);
        }

        prefs.edit().putStringSet(PREF_FAVORITES, new HashSet<>(favorites)).apply();
        updateFavoriteButton();
        updateFilters();
    }

    private void updateFavoriteButton() {
        if (currentChannel == null) {
            favoriteToggle.setText("Star");
            return;
        }

        favoriteToggle.setText(favorites.contains(currentChannel.url) ? "Starred" : "Star");
    }

    private void updateFilters() {
        String needle = currentQuery.toLowerCase(Locale.US).trim();
        visibleChannels.clear();

        for (Channel channel : allChannels) {
            boolean matchesSearch = needle.isEmpty() || channel.name.toLowerCase(Locale.US).contains(needle);
            boolean matchesFavorite = !favoritesOnly || favorites.contains(channel.url);
            boolean matchesCategory = currentCategory.equals("All") || channel.category.equals(currentCategory);

            if (matchesSearch && matchesFavorite && matchesCategory) {
                visibleChannels.add(channel);
            }
        }

        showFavorites.setText(favoritesOnly ? "All" : "Starred");
        countText.setText(visibleChannels.size() + " channels found");

        updateCategoryButtons();

        if (adapter != null) {
            adapter.notifyDataSetChanged();
        }
    }

    private void updateCategoryButtons() {
        if (categoryList == null) return;
        categoryList.removeAllViews();

        List<String> displayCategories = new ArrayList<>();
        displayCategories.add("All");
        displayCategories.addAll(categories);

        for (String cat : displayCategories) {
            Button btn = new Button(this);
            btn.setText(cat);
            btn.setTextSize(12);
            btn.setAllCaps(false);
            btn.setTextColor(cat.equals(currentCategory) ? Color.WHITE : muted);
            btn.setBackgroundColor(cat.equals(currentCategory) ? accent : panel2);
            
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-2, dp(34));
            params.setMargins(0, 0, dp(8), 0);
            btn.setFocusable(true);
            btn.setOnClickListener(v -> {
                currentCategory = cat;
                updateFilters();
            });
            categoryList.addView(btn, params);
        }
    }

    private void setStatus(String message, boolean error) {
        status.setText(message);
        status.setTextColor(error ? danger : muted);
        
        // Expert: Auto-show retry button only on errors
        retryButton.setVisibility(error ? View.VISIBLE : View.GONE);
    }

    private void hideSystemUI() {
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
    }

    private void showSystemUI() {
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN);
    }

    private String readableError(PlaybackException error) {
        if (error == null) {
            return "stream not responding. Try another channel.";
        }

        if (error.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED) {
            return "network connection failed.";
        }

        if (error.errorCode == PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS) {
            return "server rejected the stream.";
        }

        if (error.errorCode == PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED) {
            return "invalid playlist.";
        }

        return "stream not responding. Try another channel.";
    }

    private List<Channel> readChannels() {
        List<Channel> channels = new ArrayList<>();

        try {
            InputStream input = getResources().openRawResource(R.raw.channels);
            BufferedReader reader = new BufferedReader(new InputStreamReader(input));
            String line;
            String pendingName = null;

            while ((line = reader.readLine()) != null) {
                line = line.trim();

                if (line.isEmpty() || line.equals("#EXTM3U") || line.startsWith("```")) {
                    continue;
                }

                if (line.startsWith("#EXTINF")) {
                    int comma = line.lastIndexOf(",");
                    pendingName = comma >= 0 ? cleanName(line.substring(comma + 1)) : "Channel";
                    continue;
                }

                if (pendingName != null && (line.startsWith("http://") || line.startsWith("https://"))) {
                    String category = "General";
                    String cleanName = pendingName;
                    
                    if (pendingName.contains("|")) {
                        int pipe = pendingName.indexOf("|");
                        category = pendingName.substring(0, pipe).trim();
                        cleanName = pendingName.substring(pipe + 1).trim();
                    }

                    if (!categories.contains(category)) {
                        categories.add(category);
                    }

                    channels.add(new Channel(cleanName, line, category));
                    pendingName = null;
                }
            }

            reader.close();
        } catch (Exception e) {
            Log.e("KCPTV", "Error reading channels", e);
        }

        return channels;
    }

    private String cleanName(String value) {
        String name = value.trim();

        if (name.startsWith("\"") && name.endsWith("\"") && name.length() > 1) {
            return name.substring(1, name.length() - 1);
        }

        return name;
    }

    private void hideKeyboard(View view) {
        InputMethodManager manager = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);

        if (manager != null) {
            manager.hideSoftInputFromWindow(view.getWindowToken(), 0);
        }
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (android.os.Build.VERSION.SDK_INT >= 24) {
            buildPlayer();
            if (currentChannel != null) playChannel(currentChannel);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (android.os.Build.VERSION.SDK_INT < 24 || player == null) {
            buildPlayer();
            if (currentChannel != null) playChannel(currentChannel);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (android.os.Build.VERSION.SDK_INT < 24) {
            releasePlayer();
        }
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (android.os.Build.VERSION.SDK_INT >= 24) {
            releasePlayer();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        releasePlayer();
    }

    private final class ChannelAdapter extends BaseAdapter {
        @Override
        public int getCount() {
            return visibleChannels.size();
        }

        @Override
        public Object getItem(int position) {
            return visibleChannels.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, android.view.ViewGroup parent) {
            Channel channel = visibleChannels.get(position);

            LinearLayout row = new LinearLayout(MainActivity.this);
            row.setOrientation(LinearLayout.VERTICAL);
            row.setPadding(dp(12), dp(9), dp(12), dp(9));
            row.setFocusable(true);
            row.setClickable(true);
            
            // Expert: TV Focus Effect for List Items
            row.setOnFocusChangeListener((v, hasFocus) -> {
                if (hasFocus) {
                    v.setBackgroundColor(Color.rgb(30, 45, 60)); // Highlight color
                } else {
                    v.setBackgroundColor(channel == currentChannel ? Color.rgb(18, 38, 35) : panel);
                }
            });
            
            // Initial state
            row.setBackgroundColor(channel == currentChannel ? Color.rgb(18, 38, 35) : panel);

            TextView name = new TextView(MainActivity.this);
            name.setText((favorites.contains(channel.url) ? "* " : "") + channel.name);
            name.setTextColor(text);
            name.setTextSize(16);
            row.addView(name, new LinearLayout.LayoutParams(-1, -2));

            TextView url = new TextView(MainActivity.this);
            url.setText(channel.url.startsWith("https://") ? "HTTPS stream" : "HTTP stream");
            url.setTextColor(channel.url.startsWith("https://") ? accent : muted);
            url.setTextSize(12);
            row.addView(url, new LinearLayout.LayoutParams(-1, -2));

            return row;
        }
    }

    private static final class Channel {
        final String name;
        final String url;
        final String category;

        Channel(String name, String url, String category) {
            this.name = name;
            this.url = url;
            this.category = category;
        }
    }
}
