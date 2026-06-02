package com.plugintv.livetv;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.exoplayer.DefaultLoadControl;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.ui.PlayerView;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;

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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@UnstableApi
public class MainActivity extends Activity {
    private static final String TAG = "KCPTV";
    private static final String APP_NAME = "KCP TV";
    private static final String DEVELOPER_NAME = "Imam Ahmed Chowdhury";
    private static final String PREFS = "plugintv_prefs";
    private static final String PREF_LAST_URL = "last_url";
    private static final String PREF_FAVORITES = "favorites";
    private static final String UPDATE_API = "https://api.github.com/repos/imamachowdhury/KCP-TV_APP/releases/latest";

    private final List<Channel> allChannels = new ArrayList<>();
    private final List<Channel> visibleChannels = new ArrayList<>();
    private final List<String> categories = new ArrayList<>();
    private final Set<String> favorites = new HashSet<>();
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

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

    // Design System (Fast & Stable)
    private final int bg = Color.parseColor("#0D1115");
    private final int panel = Color.parseColor("#151B21");
    private final int panel2 = Color.parseColor("#1F2933");
    private final int text = Color.parseColor("#F5F7FA");
    private final int muted = Color.parseColor("#A8B3BF");
    private final int accent = Color.parseColor("#12B886");
    private final int danger = Color.parseColor("#FF6B6B");

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        favorites.addAll(prefs.getStringSet(PREF_FAVORITES, new HashSet<>()));

        // Start loading data immediately on background thread
        loadChannelsAsync();
        checkForUpdates();

        if (prefs.contains(PREF_LAST_URL)) {
            buildUi();
        } else {
            showOpeningPage();
        }
    }

    private void loadChannelsAsync() {
        executor.execute(() -> {
            List<Channel> channels = readChannelsFromM3U();
            Collections.sort(channels, (c1, c2) -> c1.name.compareToIgnoreCase(c2.name));
            
            mainHandler.post(() -> {
                allChannels.clear();
                allChannels.addAll(channels);
                updateFilters();
                restoreOrPlayFirstChannel();
            });
        });
    }

    private void buildPlayer() {
        if (player != null) return;

        DefaultHttpDataSource.Factory dataSourceFactory = new DefaultHttpDataSource.Factory()
            .setUserAgent(APP_NAME + " Player")
            .setConnectTimeoutMs(15000)
            .setReadTimeoutMs(20000)
            .setAllowCrossProtocolRedirects(true);

        // Optimized Buffer for Stability
        DefaultLoadControl loadControl = new DefaultLoadControl.Builder()
            .setBufferDurationsMs(30000, 60000, 2500, 5000)
            .build();

        player = new ExoPlayer.Builder(this)
            .setMediaSourceFactory(new DefaultMediaSourceFactory(this).setDataSourceFactory(dataSourceFactory))
            .setLoadControl(loadControl)
            .build();

        player.addListener(new Player.Listener() {
            @Override
            public void onPlaybackStateChanged(int state) {
                if (loading == null) return;
                boolean isBuffering = state == Player.STATE_BUFFERING;
                loading.setVisibility(isBuffering ? View.VISIBLE : View.GONE);
                if (isBuffering) setStatus("Buffering...", false);
                else if (state == Player.STATE_READY) setStatus("", false);
            }

            @Override
            public void onPlayerError(@NonNull PlaybackException error) {
                if (loading != null) loading.setVisibility(View.GONE);
                setStatus("Stream Error: " + getErrorMessage(error), true);
            }
        });

        if (playerView != null) playerView.setPlayer(player);
    }

    private void buildUi() {
        buildPlayer();
        
        // Cache Logo Resource ID to avoid reflection in loop
        final int logoResId = getResources().getIdentifier("ic_logo", "drawable", getPackageName());
        
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(bg);
        root.setPadding(dp(12), dp(12), dp(12), dp(12));

        // Header
        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        
        ImageView logo = new ImageView(this);
        logo.setImageResource(logoResId != 0 ? logoResId : android.R.drawable.ic_menu_slideshow);
        header.addView(logo, new LinearLayout.LayoutParams(dp(40), dp(40)));

        LinearLayout titleBlock = new LinearLayout(this);
        titleBlock.setOrientation(LinearLayout.VERTICAL);
        titleBlock.setPadding(dp(12), 0, 0, 0);

        TextView title = new TextView(this);
        title.setText(APP_NAME);
        title.setTextColor(text);
        title.setTextSize(24);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        titleBlock.addView(title);

        countText = new TextView(this);
        countText.setTextColor(muted);
        countText.setTextSize(12);
        titleBlock.addView(countText);
        header.addView(titleBlock, new LinearLayout.LayoutParams(0, -2, 1f));

        retryButton = makeButton("Retry", false);
        retryButton.setOnClickListener(v -> { if (currentChannel != null) playChannel(currentChannel); });
        header.addView(retryButton, new LinearLayout.LayoutParams(dp(80), dp(36)));
        root.addView(header, new LinearLayout.LayoutParams(-1, -2));

        nowPlaying = new TextView(this);
        nowPlaying.setTextColor(accent);
        nowPlaying.setTextSize(14);
        nowPlaying.setPadding(0, dp(8), 0, dp(8));
        root.addView(nowPlaying);

        // Player Section
        playerView = new PlayerView(this);
        playerView.setPlayer(player);
        playerView.setBackgroundColor(Color.BLACK);
        playerView.setKeepScreenOn(true);
        root.addView(playerView, new LinearLayout.LayoutParams(-1, 0, 1.2f));

        loading = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        loading.setIndeterminate(true);
        loading.setVisibility(View.GONE);
        root.addView(loading, new LinearLayout.LayoutParams(-1, dp(4)));

        status = new TextView(this);
        status.setTextSize(13);
        status.setPadding(0, dp(4), 0, dp(4));
        root.addView(status);

        // Controls
        LinearLayout controls = new LinearLayout(this);
        controls.setGravity(Gravity.CENTER_VERTICAL);

        favoriteToggle = makeButton("Star", false);
        favoriteToggle.setOnClickListener(v -> toggleFavorite());
        controls.addView(favoriteToggle, new LinearLayout.LayoutParams(dp(80), dp(40)));

        showFavorites = makeButton("Favorites", false);
        showFavorites.setOnClickListener(v -> { favoritesOnly = !favoritesOnly; updateFilters(); });
        LinearLayout.LayoutParams favParams = new LinearLayout.LayoutParams(dp(110), dp(40));
        favParams.setMargins(dp(8), 0, dp(8), 0);
        controls.addView(showFavorites, favParams);

        search = new EditText(this);
        search.setHint("Search...");
        search.setHintTextColor(muted);
        search.setTextColor(text);
        search.setSingleLine(true);
        search.setPadding(dp(12), 0, dp(12), 0);
        search.setBackground(getRoundedDrawable(panel2, 8));
        controls.addView(search, new LinearLayout.LayoutParams(0, dp(40), 1f));
        root.addView(controls, new LinearLayout.LayoutParams(-1, -2));

        // Categories
        android.widget.HorizontalScrollView categoryScroll = new android.widget.HorizontalScrollView(this);
        categoryScroll.setPadding(0, dp(10), 0, dp(10));
        categoryScroll.setHorizontalScrollBarEnabled(false);
        categoryList = new LinearLayout(this);
        categoryScroll.addView(categoryList);
        root.addView(categoryScroll);

        // Optimized List (RecyclerView)
        RecyclerView recyclerView = new RecyclerView(this);
        recyclerView.setLayoutManager(new LinearLayoutManager(this));
        recyclerView.setBackgroundColor(panel);
        
        // Add Divider for clarity
        androidx.recyclerview.widget.DividerItemDecoration divider = new androidx.recyclerview.widget.DividerItemDecoration(this, androidx.recyclerview.widget.DividerItemDecoration.VERTICAL);
        GradientDrawable devDrawable = new GradientDrawable();
        devDrawable.setColor(Color.parseColor("#252D35"));
        devDrawable.setSize(1, dp(1));
        divider.setDrawable(devDrawable);
        recyclerView.addItemDecoration(divider);

        adapter = new ChannelAdapter();
        recyclerView.setAdapter(adapter);
        root.addView(recyclerView, new LinearLayout.LayoutParams(-1, 0, 1.5f));

        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                currentQuery = s.toString();
                updateFilters();
            }
            @Override public void afterTextChanged(Editable s) {}
        });

        setContentView(root);
    }

    private void showOpeningPage() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        root.setBackgroundColor(bg);
        
        ImageView bigLogo = new ImageView(this);
        int openingLogoId = getResources().getIdentifier("ic_logo", "drawable", getPackageName());
        bigLogo.setImageResource(openingLogoId != 0 ? openingLogoId : android.R.drawable.ic_menu_slideshow);
        root.addView(bigLogo, new LinearLayout.LayoutParams(dp(100), dp(100)));

        TextView appName = new TextView(this);
        appName.setText(APP_NAME);
        appName.setTextColor(text);
        appName.setTextSize(36);
        appName.setPadding(0, dp(20), 0, 0);
        root.addView(appName);

        TextView dev = new TextView(this);
        dev.setText("by " + DEVELOPER_NAME);
        dev.setTextColor(muted);
        dev.setTextSize(14);
        root.addView(dev);

        Button start = makeButton("Launch TV", true);
        start.setOnClickListener(v -> { buildUi(); updateFilters(); restoreOrPlayFirstChannel(); });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(200), dp(50));
        lp.setMargins(0, dp(40), 0, 0);
        root.addView(start, lp);

        setContentView(root);
    }

    private void playChannel(Channel channel) {
        if (channel == null || player == null) return;
        currentChannel = channel;
        prefs.edit().putString(PREF_LAST_URL, channel.url).apply();
        
        nowPlaying.setText("Playing: " + channel.name);
        updateFavoriteButton();
        
        MediaItem mediaItem = MediaItem.fromUri(Uri.parse(channel.url));
        player.setMediaItem(mediaItem);
        player.prepare();
        player.play();
        
        if (adapter != null) adapter.notifyDataSetChanged();
    }

    private void updateFilters() {
        String query = currentQuery.toLowerCase(Locale.US).trim();
        visibleChannels.clear();

        for (Channel c : allChannels) {
            boolean matchesSearch = query.isEmpty() || c.name.toLowerCase(Locale.US).contains(query);
            boolean matchesFav = !favoritesOnly || favorites.contains(c.url);
            boolean matchesCat = currentCategory.equals("All") || c.category.equals(currentCategory);
            if (matchesSearch && matchesFav && matchesCat) visibleChannels.add(c);
        }

        if (countText != null) countText.setText(visibleChannels.size() + " Channels");
        if (showFavorites != null) showFavorites.setText(favoritesOnly ? "Show All" : "Favorites");
        
        updateCategoryButtons();
        if (adapter != null) adapter.notifyDataSetChanged();
    }

    private void updateCategoryButtons() {
        if (categoryList == null) return;
        categoryList.removeAllViews();

        List<String> cats = new ArrayList<>();
        cats.add("All");
        cats.addAll(categories);

        for (String cat : cats) {
            boolean active = cat.equals(currentCategory);
            Button b = new Button(this);
            b.setText(cat);
            b.setTextSize(12);
            b.setAllCaps(false);
            b.setTextColor(active ? Color.WHITE : muted);
            b.setBackground(getRoundedDrawable(active ? accent : panel2, 20));
            
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-2, dp(32));
            p.setMargins(0, 0, dp(8), 0);
            b.setOnClickListener(v -> { currentCategory = cat; updateFilters(); });
            categoryList.addView(b, p);
        }
    }

    private void toggleFavorite() {
        if (currentChannel == null) return;
        if (favorites.contains(currentChannel.url)) favorites.remove(currentChannel.url);
        else favorites.add(currentChannel.url);
        
        prefs.edit().putStringSet(PREF_FAVORITES, new HashSet<>(favorites)).apply();
        updateFavoriteButton();
        updateFilters();
    }

    private void updateFavoriteButton() {
        if (favoriteToggle == null) return;
        boolean isFav = currentChannel != null && favorites.contains(currentChannel.url);
        favoriteToggle.setText(isFav ? "Starred" : "Star");
        favoriteToggle.setTextColor(isFav ? accent : text);
    }

    private void restoreOrPlayFirstChannel() {
        if (allChannels.isEmpty()) return;
        String last = prefs.getString(PREF_LAST_URL, "");
        for (Channel c : allChannels) {
            if (c.url.equals(last)) { playChannel(c); return; }
        }
        playChannel(allChannels.get(0));
    }

    private List<Channel> readChannelsFromM3U() {
        List<Channel> list = new ArrayList<>();
        categories.clear();
        try (InputStream in = getResources().openRawResource(R.raw.channels);
             BufferedReader reader = new BufferedReader(new InputStreamReader(in))) {
            String line, name = null;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#EXTM3U")) continue;
                if (line.startsWith("#EXTINF")) {
                    int comma = line.lastIndexOf(",");
                    name = comma >= 0 ? line.substring(comma + 1).trim() : "Channel";
                    name = cleanName(name);
                } else if (name != null && line.startsWith("http")) {
                    String cat = "General";
                    if (name.contains("|")) {
                        int p = name.indexOf("|");
                        cat = name.substring(0, p).trim();
                        name = name.substring(p + 1).trim();
                    }
                    if (!categories.contains(cat)) categories.add(cat);
                    list.add(new Channel(name, line, cat));
                    name = null;
                }
            }
        } catch (Exception e) { Log.e(TAG, "M3U Load Error", e); }
        return list;
    }

    private String cleanName(String value) {
        String name = value.trim();
        if (name.startsWith("\"") && name.endsWith("\"") && name.length() > 1) {
            return name.substring(1, name.length() - 1);
        }
        return name;
    }

    private Button makeButton(String label, boolean big) {
        Button b = new Button(this);
        b.setText(label);
        b.setTextColor(text);
        b.setAllCaps(false);
        b.setBackground(getRoundedDrawable(panel2, big ? 12 : 8));
        b.setOnFocusChangeListener((v, f) -> {
            v.setBackground(getRoundedDrawable(f ? accent : panel2, big ? 12 : 8));
            v.animate().scaleX(f ? 1.05f : 1.0f).scaleY(f ? 1.05f : 1.0f).setDuration(200).start();
        });
        return b;
    }

    private GradientDrawable getRoundedDrawable(int color, int radiusDp) {
        GradientDrawable gd = new GradientDrawable();
        gd.setColor(color);
        gd.setCornerRadius(dp(radiusDp));
        return gd;
    }

    private void setStatus(String msg, boolean err) {
        if (status == null) return;
        status.setText(msg);
        status.setTextColor(err ? danger : muted);
        retryButton.setVisibility(err ? View.VISIBLE : View.GONE);
    }

    private String getErrorMessage(PlaybackException e) {
        if (e.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED) return "No Internet";
        if (e.errorCode == PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS) return "Server Error (404/500)";
        return "Stream Unavailable";
    }

    private void checkForUpdates() {
        executor.execute(() -> {
            try {
                HttpURLConnection c = (HttpURLConnection) new URL(UPDATE_API).openConnection();
                if (c.getResponseCode() == 200) {
                    JSONObject json = new JSONObject(new BufferedReader(new InputStreamReader(c.getInputStream())).readLine());
                    String latest = json.getString("tag_name").replace("v", "").trim();
                    String url = json.getString("html_url");
                    PackageInfo p = getPackageManager().getPackageInfo(getPackageName(), 0);
                    String current = p.versionName != null ? p.versionName.trim() : "0.0";
                    
                    if (isNewerVersion(latest, current)) {
                        mainHandler.post(() -> showUpdateDialog(latest, url));
                    }
                }
            } catch (Exception ignored) {}
        });
    }

    private boolean isNewerVersion(String latest, String current) {
        try {
            String[] v1 = latest.split("\\.");
            String[] v2 = current.split("\\.");
            int length = Math.max(v1.length, v2.length);
            for (int i = 0; i < length; i++) {
                int n1 = i < v1.length ? Integer.parseInt(v1[i]) : 0;
                int n2 = i < v2.length ? Integer.parseInt(v2[i]) : 0;
                if (n1 > n2) return true;
                if (n1 < n2) return false;
            }
        } catch (Exception e) {
            return !latest.equals(current);
        }
        return false;
    }

    private void showUpdateDialog(String v, String url) {
        new AlertDialog.Builder(this).setTitle("Update " + v).setMessage("A new version is available.")
            .setPositiveButton("Download", (d, w) -> startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url))))
            .setNegativeButton("Later", null).show();
    }

    private int dp(int v) { return (int) (v * getResources().getDisplayMetrics().density + 0.5f); }

    @Override protected void onPause() { super.onPause(); if (player != null) player.pause(); }
    @Override protected void onDestroy() { super.onDestroy(); if (player != null) player.release(); executor.shutdown(); }

    // Optimized Adapter with ViewHolder
    private class ChannelAdapter extends RecyclerView.Adapter<ChannelAdapter.Holder> {
        @NonNull @Override public Holder onCreateViewHolder(@NonNull ViewGroup p, int t) {
            LinearLayout row = new LinearLayout(MainActivity.this);
            row.setOrientation(LinearLayout.VERTICAL);
            row.setPadding(dp(16), dp(10), dp(16), dp(10));
            row.setFocusable(true);
            row.setClickable(true);
            row.setLayoutParams(new RecyclerView.LayoutParams(-1, -2));
            return new Holder(row);
        }

        @Override public void onBindViewHolder(@NonNull Holder h, int p) {
            Channel c = visibleChannels.get(p);
            boolean active = c == currentChannel;
            boolean isFav = favorites.contains(c.url);
            
            h.name.setText((isFav ? "★ " : "") + c.name);
            h.name.setTextColor(active ? accent : text);
            
            String streamType = c.url.startsWith("https") ? "HTTPS" : "HTTP";
            h.info.setText(c.category + " | " + streamType);

            // Handle T-Sports Logo
            if (c.name.toLowerCase().contains("t-sports")) {
                h.logo.setVisibility(View.VISIBLE);
                Glide.with(h.itemView.getContext())
                    .load("https://upload.wikimedia.org/wikipedia/commons/thumb/4/4c/T_Sports_logo.svg/1280px-T_Sports_logo.svg.png")
                    .into(h.logo);
            } else {
                h.logo.setVisibility(View.GONE);
            }
            
            h.itemView.setBackground(getRoundedDrawable(active ? Color.parseColor("#1A222C") : Color.TRANSPARENT, 4));
            h.itemView.setOnClickListener(v -> playChannel(c));
            h.itemView.setOnFocusChangeListener((v, f) -> {
                v.setBackground(getRoundedDrawable(f ? Color.parseColor("#2A3644") : (active ? Color.parseColor("#1A222C") : Color.TRANSPARENT), 4));
                v.animate().scaleX(f ? 1.02f : 1.0f).scaleY(f ? 1.02f : 1.0f).setDuration(150).start();
            });
        }

        @Override public int getItemCount() { return visibleChannels.size(); }

        class Holder extends RecyclerView.ViewHolder {
            TextView name, info;
            ImageView logo;
            Holder(View v) {
                super(v);
                LinearLayout rowLayout = (LinearLayout) v;
                rowLayout.setGravity(Gravity.CENTER_VERTICAL);

                LinearLayout horizontalContainer = new LinearLayout(MainActivity.this);
                horizontalContainer.setOrientation(LinearLayout.HORIZONTAL);
                horizontalContainer.setGravity(Gravity.CENTER_VERTICAL);

                logo = new ImageView(MainActivity.this);
                logo.setVisibility(View.GONE);
                LinearLayout.LayoutParams logoParams = new LinearLayout.LayoutParams(dp(40), dp(40));
                logoParams.setMargins(0, 0, dp(12), 0);
                horizontalContainer.addView(logo, logoParams);

                LinearLayout textContainer = new LinearLayout(MainActivity.this);
                textContainer.setOrientation(LinearLayout.VERTICAL);

                name = new TextView(MainActivity.this);
                name.setTextSize(16);
                name.setSingleLine(true);
                name.setEllipsize(android.text.TextUtils.TruncateAt.END);
                textContainer.addView(name);
                
                info = new TextView(MainActivity.this);
                info.setTextSize(12);
                info.setTextColor(muted);
                textContainer.addView(info);

                horizontalContainer.addView(textContainer);
                rowLayout.addView(horizontalContainer);
            }
        }
    }

    private static class Channel {
        final String name, url, category;
        Channel(String n, String u, String c) { name = n; url = u; category = c; }
    }
}
