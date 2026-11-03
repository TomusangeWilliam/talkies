package com.techpp.glass;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class FloatingAlertService extends Service {
    public static final String ACTION_SHOW = "com.techpp.glass.action.SHOW_FLOATING_ALERT";
    public static final String ACTION_BACKGROUND_CHECK = "com.techpp.glass.action.CHECK_FLOATING_ALERT";
    public static final String ACTION_LIVE_PANEL = "com.techpp.glass.action.LIVE_PANEL";
    public static final String ACTION_HIDE_PANEL = "com.techpp.glass.action.HIDE_FLOATING_ALERT";
    private static final int NOTIFY_ID = 9001;
    private static final String CHANNEL_ID = "talkies_live_alerts";
    private static final long ALERT_VISIBLE_MS = 5000L;
    private static final long BACKGROUND_CHECK_MS = 30_000L;
    private static final long LIVE_PANEL_REFRESH_MS = 10_000L;
    private WindowManager windowManager;
    private View root;
    private final Handler uiHandler = new Handler(Looper.getMainLooper());
    private Runnable livePanelRunnable;
    private int currentMatchIndex = 0;
    private List<Cricket.Match> lastKnownMatches = new java.util.ArrayList<>();
    private long lastFetchTime = 0L;
    private final java.util.concurrent.ExecutorService fetchExecutor = java.util.concurrent.Executors.newSingleThreadExecutor();

    public static void show(Context context, String label, String message, String league, String homeUrl, String awayUrl, String leagueLogoUrl) {
        Context app = context.getApplicationContext();
        if (Build.VERSION.SDK_INT >= 23 && !Settings.canDrawOverlays(app)) {
            Intent i = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + app.getPackageName()));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            app.startActivity(i);
            return;
        }
        Intent i = new Intent(app, FloatingAlertService.class);
        i.setAction(ACTION_SHOW);
        i.putExtra("label", label);
        i.putExtra("message", message);
        i.putExtra("league", league);
        i.putExtra("league_logo_url", leagueLogoUrl);
        i.putExtra("home_url", homeUrl);
        i.putExtra("away_url", awayUrl);
        if (Build.VERSION.SDK_INT >= 26) {
            app.startForegroundService(i);
        } else {
            app.startService(i);
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    public static void checkBackground(Context context) {
        Context app = context.getApplicationContext();
        Intent i = new Intent(app, FloatingAlertService.class);
        i.setAction(ACTION_BACKGROUND_CHECK);
        if (Build.VERSION.SDK_INT >= 26) {
            app.startForegroundService(i);
        } else {
            app.startService(i);
        }
    }

    public static void activateLivePanel(Context context) {
        Context app = context.getApplicationContext();
        Intent i = new Intent(app, FloatingAlertService.class);
        i.setAction(ACTION_LIVE_PANEL);
        if (Build.VERSION.SDK_INT >= 26) {
            app.startForegroundService(i);
        } else {
            app.startService(i);
        }
    }

    public static void hideLivePanel(Context context) {
        Context app = context.getApplicationContext();
        Intent i = new Intent(app, FloatingAlertService.class);
        i.setAction(ACTION_HIDE_PANEL);
        if (Build.VERSION.SDK_INT >= 26) {
            app.startForegroundService(i);
        } else {
            app.startService(i);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startForeground(NOTIFY_ID, buildNotification());
        if (intent != null) {
            String action = intent.getAction();
            if (ACTION_SHOW.equals(action)) {
                showOverlay(
                        intent.getStringExtra("label"),
                        intent.getStringExtra("message"),
                        intent.getStringExtra("league"),
                        intent.getStringExtra("home_url"),
                        intent.getStringExtra("away_url"),
                        intent.getStringExtra("league_logo_url")
                );
                stopSelf();
                return START_NOT_STICKY;
            }
            if (ACTION_LIVE_PANEL.equals(action)) {
                showPersistentLivePanel();
                return START_STICKY;
            }
            if (ACTION_HIDE_PANEL.equals(action)) {
                hidePersistentLivePanel();
                return START_NOT_STICKY;
            }
            if (ACTION_BACKGROUND_CHECK.equals(action)) {
                checkForBackgroundLiveAlerts();
                scheduleNextBackgroundCheck();
                return START_STICKY;
            }
        }
        return START_NOT_STICKY;
    }

    private Notification buildNotification() {
        Context c = this;
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel channel = new NotificationChannel(CHANNEL_ID, "Live score alerts", NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("Live score floating notifications");
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) nm.createNotificationChannel(channel);
        }
        Notification.Builder builder = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(c, CHANNEL_ID)
                : new Notification.Builder(c);
        builder.setContentTitle("Talkies live alerts")
                .setContentText("Live score notifications are active")
                .setSmallIcon(R.drawable.ic_bell)
                .setOngoing(true)
                .setOnlyAlertOnce(true);
        return builder.build();
    }

    private void scheduleNextBackgroundCheck() {
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            checkBackground(this);
        }, BACKGROUND_CHECK_MS);
    }

    private void hidePersistentLivePanel() {
        if (livePanelRunnable != null) {
            uiHandler.removeCallbacks(livePanelRunnable);
            livePanelRunnable = null;
        }
        if (root != null && windowManager != null) {
            try {
                windowManager.removeView(root);
            } catch (RuntimeException ignored) {
            }
        }
        root = null;
        stopSelf();
    }

    private void showPersistentLivePanel() {
        if (livePanelRunnable != null) {
            uiHandler.removeCallbacks(livePanelRunnable);
        }
        renderPersistentPanel();
        livePanelRunnable = new Runnable() {
            @Override
            public void run() {
                renderPersistentPanel();
                uiHandler.postDelayed(this, LIVE_PANEL_REFRESH_MS);
            }
        };
        uiHandler.postDelayed(livePanelRunnable, LIVE_PANEL_REFRESH_MS);
    }

    private void renderPersistentPanel() {
        List<Cricket.Match> matches = getDisplayMatches();
        if (matches == null || matches.isEmpty()) {
            showOverlay("LIVE", "Checking live fixtures", "FOOTBALL", null, null, null);
            return;
        }

        int total = matches.size();
        if (currentMatchIndex >= total) {
            currentMatchIndex = 0;
        }
        Cricket.Match match = matches.get(currentMatchIndex);
        int displayIndex = currentMatchIndex + 1;
        currentMatchIndex = (currentMatchIndex + 1) % total;

        String leagueName = match.league == null || match.league.trim().isEmpty() ? "Football" : match.league.trim();
        boolean isUpcoming = !match.live && !match.ended;
        String countdown = isUpcoming && match.at > 0 ? formatCountdown(match.at) : "";
        String statusText;
        if (isUpcoming) {
            statusText = countdown.isEmpty() ? "UPCOMING" : ("IN " + countdown.toUpperCase(Locale.US));
        } else if (match.status != null && !match.status.trim().isEmpty()) {
            statusText = match.status.trim();
        } else {
            statusText = match.live ? "LIVE" : "FINISHED";
        }
        String headline = buildPersistentHeadline(match);
        String scoreText = buildPersistentScore(match);
        String detailText = buildPersistentDetail(match, statusText);

        if (root != null) {
            if (windowManager != null) {
                try {
                    windowManager.removeView(root);
                } catch (RuntimeException ignored) {
                }
            }
            root = null;
        }

        final float dp = getResources().getDisplayMetrics().density;
        Context ctx = this;
        LinearLayout container = new LinearLayout(ctx);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setPadding(Math.round(18 * dp), Math.round(14 * dp), Math.round(18 * dp), Math.round(14 * dp));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xEB111722);
        bg.setCornerRadius(22 * dp);
        bg.setStroke(Math.round(1.2f * dp), 0x33FFFFFF);
        container.setBackground(bg);

        LinearLayout header = new LinearLayout(ctx);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);

        // League competition logo (shown small before the home badge).
        ImageView leagueLogoBadge = new ImageView(ctx);
        LinearLayout.LayoutParams leagueLogoBadgeLp = new LinearLayout.LayoutParams(Math.round(18 * dp), Math.round(18 * dp));
        leagueLogoBadgeLp.rightMargin = Math.round(6 * dp);
        leagueLogoBadge.setLayoutParams(leagueLogoBadgeLp);
        leagueLogoBadge.setScaleType(ImageView.ScaleType.FIT_CENTER);
        leagueLogoBadge.setVisibility(View.GONE);
        header.addView(leagueLogoBadge);

        ImageView homeBadge = new ImageView(ctx);
        LinearLayout.LayoutParams homeLp = new LinearLayout.LayoutParams(Math.round(28 * dp), Math.round(28 * dp));
        homeBadge.setLayoutParams(homeLp);
        homeBadge.setScaleType(ImageView.ScaleType.CENTER_CROP);
        homeBadge.setPadding(Math.round(2 * dp), Math.round(2 * dp), Math.round(2 * dp), Math.round(2 * dp));
        header.addView(homeBadge);

        TextView scoreView = new TextView(ctx);
        scoreView.setText(scoreText);
        scoreView.setTextColor(0xFFF5F7FB);
        scoreView.setTextSize(17f);
        scoreView.setTypeface(getResources().getFont(R.font.outfit));
        scoreView.getPaint().setFakeBoldText(true);
        LinearLayout.LayoutParams scoreLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        scoreLp.leftMargin = Math.round(10 * dp);
        scoreLp.rightMargin = Math.round(10 * dp);
        header.addView(scoreView, scoreLp);

        ImageView awayBadge = new ImageView(ctx);
        LinearLayout.LayoutParams awayLp = new LinearLayout.LayoutParams(Math.round(28 * dp), Math.round(28 * dp));
        awayLp.leftMargin = Math.round(6 * dp);
        awayBadge.setLayoutParams(awayLp);
        awayBadge.setScaleType(ImageView.ScaleType.CENTER_CROP);
        awayBadge.setPadding(Math.round(2 * dp), Math.round(2 * dp), Math.round(2 * dp), Math.round(2 * dp));
        header.addView(awayBadge);

        container.addView(header);

        TextView leagueText = new TextView(ctx);
        String leagueBadgeText = leagueName.toUpperCase(Locale.US);
        if (total > 1) {
            leagueBadgeText += "  •  " + displayIndex + "/" + total;
        }
        leagueText.setText(leagueBadgeText);
        leagueText.setTextColor(0xFFFFB14A);
        leagueText.setTextSize(11f);
        leagueText.setTypeface(getResources().getFont(R.font.outfit));
        leagueText.getPaint().setFakeBoldText(true);
        leagueText.setPadding(0, Math.round(8 * dp), 0, 0);
        container.addView(leagueText);

        TextView headlineView = new TextView(ctx);
        headlineView.setText(headline);
        headlineView.setTextColor(0xFFF8FAFC);
        headlineView.setTextSize(15f);
        headlineView.setTypeface(getResources().getFont(R.font.outfit));
        headlineView.getPaint().setFakeBoldText(true);
        headlineView.setPadding(0, Math.round(4 * dp), 0, 0);
        container.addView(headlineView);

        TextView detailView = new TextView(ctx);
        detailView.setText(detailText);
        detailView.setTextColor(0xFFB7C0CF);
        detailView.setTextSize(11f);
        detailView.setTypeface(getResources().getFont(R.font.outfit));
        detailView.setPadding(0, Math.round(4 * dp), 0, 0);
        container.addView(detailView);

        windowManager = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                Build.VERSION.SDK_INT >= 26 ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY : WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.BOTTOM | Gravity.END;
        params.x = Math.round(42 * dp);
        params.y = Math.round(42 * dp);

        root = container;
        windowManager.addView(root, params);

        if (match.img1 != null && !match.img1.trim().isEmpty()) loadBadge(homeBadge, match.img1);
        if (match.img2 != null && !match.img2.trim().isEmpty()) loadBadge(awayBadge, match.img2);
        if (match.leagueLogo != null && !match.leagueLogo.trim().isEmpty()) {
            leagueLogoBadge.setVisibility(View.VISIBLE);
            loadBadge(leagueLogoBadge, match.leagueLogo);
        }
    }

    private List<Cricket.Match> getDisplayMatches() {
        Football football = new Football(this);
        List<Cricket.Match> all = football.cached(Football.allLeagueIds());
        if (all == null || all.isEmpty()) {
            all = football.cached();
        }
        List<Cricket.Match> list = new ArrayList<>();
        if (all != null) {
            for (Cricket.Match m : all) {
                if (m != null && m.football) list.add(m);
            }
        }
        long now = System.currentTimeMillis();
        if (now - lastFetchTime > 30_000L) {
            lastFetchTime = now;
            fetchExecutor.execute(() -> {
                try {
                    List<Cricket.Match> refreshed = football.refreshNow(Football.allLeagueIds());
                    if (refreshed != null && !refreshed.isEmpty()) {
                        uiHandler.post(() -> { lastKnownMatches = refreshed; });
                    }
                } catch (Exception ignored) {}
            });
        }
        if (!lastKnownMatches.isEmpty()) {
            List<Cricket.Match> fb = new ArrayList<>();
            for (Cricket.Match m : lastKnownMatches) {
                if (m != null && m.football) fb.add(m);
            }
            if (!fb.isEmpty()) list = fb;
        }
        return list;
    }

    private String buildPersistentScore(Cricket.Match match) {
        String home = (match.short1 == null || match.short1.isEmpty()) ? match.t1 : match.short1;
        String away = (match.short2 == null || match.short2.isEmpty()) ? match.t2 : match.short2;
        boolean hasScore = (match.s1 != null && !match.s1.isEmpty() && !"0".equals(match.s1))
                || (match.s2 != null && !match.s2.isEmpty() && !"0".equals(match.s2));
        if (!match.live && !match.ended && !hasScore) {
            return home + "  vs  " + away;
        }
        return home + " " + (match.s1 == null ? "0" : match.s1) + " - " + (match.s2 == null ? "0" : match.s2) + " " + away;
    }

    private String buildPersistentHeadline(Cricket.Match match) {
        if (match == null) return "LIVE MATCH";
        if (match.live) {
            String minute = (match.goalMinute == null || match.goalMinute.trim().isEmpty())
                    ? (match.status == null || match.status.trim().isEmpty() ? "LIVE" : match.status.trim())
                    : match.goalMinute.trim();
            return (match.scorer == null || match.scorer.trim().isEmpty()) ? minute : "GOAL • " + match.scorer.trim() + " " + minute;
        }
        if (match.ended) {
            return "FULL TIME";
        }
        if (match.at > 0) {
            String cd = formatCountdown(match.at);
            if (!cd.isEmpty()) return "STARTS IN " + cd.toUpperCase(Locale.US);
        }
        if (match.status != null && !match.status.trim().isEmpty()) return match.status.trim();
        return "UPCOMING";
    }

    private String buildPersistentDetail(Cricket.Match match, String statusText) {
        if (match == null) return "LIVE football update";
        if (match.scorer != null && !match.scorer.trim().isEmpty()) {
            return "Goal update • " + match.scorer.trim() + " • " + statusText;
        }
        if (isHalfTimeStatus(statusText)) return "Half time • score locked in";
        if (isFullTimeStatus(statusText)) return "Full time • final whistle";
        if (match.live) return "Live match • updates in real time";
        if (!match.ended && match.at > 0) {
            String cd = formatCountdown(match.at);
            String timeStr = match.start != null && !match.start.trim().isEmpty() ? match.start.trim() : "";
            if (!timeStr.isEmpty()) {
                return "Kickoff at " + timeStr + " • " + cd + " remaining";
            }
            return "Kickoff in " + cd;
        }
        return "Countdown • " + statusText;
    }

    private static String formatCountdown(long atMillis) {
        if (atMillis <= 0) return "";
        long diff = atMillis - System.currentTimeMillis();
        if (diff <= 0) return "Starting now";
        long totalMinutes = diff / 60_000L;
        long hours = totalMinutes / 60L;
        long minutes = totalMinutes % 60L;
        if (hours > 0) {
            return minutes > 0 ? (hours + "h " + minutes + "m") : (hours + "h");
        }
        if (minutes > 0) {
            return minutes + "m";
        }
        return "< 1m";
    }

    private void checkForBackgroundLiveAlerts() {
        Football football = new Football(this);
        List<Cricket.Match> matches = football.refreshNow(Football.allLeagueIds());
        if (matches == null || matches.isEmpty()) return;
        android.content.SharedPreferences prefs = getSharedPreferences("bg_live_alerts", MODE_PRIVATE);
        for (Cricket.Match match : matches) {
            if (match == null || !match.football || (!match.live && !match.ended)) continue;
            String key = "score_" + match.id;
            String nowState = matchScoreState(match);
            String prevState = prefs.getString(key, "");
            if (prevState.isEmpty()) {
                int home = parseGoals(match.s1), away = parseGoals(match.s2);
                if (home > 0 || away > 0) {
                    String message = buildGoalMessage(match, "0-0");
                    showOverlay("GOAL", message, match.league == null ? "Football" : match.league, match.img1, match.img2, match.leagueLogo);
                }
            } else if (hasGoalScored(prevState, nowState)) {
                String message = buildGoalMessage(match, prevState);
                showOverlay("GOAL", message, match.league == null ? "Football" : match.league, match.img1, match.img2, match.leagueLogo);
            }
            String statusKey = "status_" + match.id;
            String prevStatus = prefs.getString(statusKey, "");
            String status = match.status == null ? "" : match.status.trim();
            if (!status.isEmpty() && !status.equals(prevStatus)) {
                if (isHalfTimeStatus(status)) {
                    showOverlay("HALF TIME", formatResultLine(match), match.league == null ? "Football" : match.league, match.img1, match.img2, match.leagueLogo);
                } else if (isFullTimeStatus(status)) {
                    showOverlay("FULL TIME", formatResultLine(match), match.league == null ? "Football" : match.league, match.img1, match.img2, match.leagueLogo);
                }
            }
            prefs.edit().putString(key, nowState).putString(statusKey, status).apply();
        }
    }

    private static String matchScoreState(Cricket.Match match) {
        return (match.s1 == null ? "0" : match.s1) + "-" + (match.s2 == null ? "0" : match.s2);
    }

    private static boolean hasGoalScored(String previous, String current) {
        if (previous == null || previous.trim().isEmpty() || current == null || current.trim().isEmpty()) return false;
        String[] prev = previous.split("-");
        String[] curr = current.split("-");
        if (prev.length != 2 || curr.length != 2) return false;
        int prevHome = parseGoals(prev[0]);
        int prevAway = parseGoals(prev[1]);
        int currHome = parseGoals(curr[0]);
        int currAway = parseGoals(curr[1]);
        return (currHome > prevHome || currAway > prevAway) && (currHome > 0 || currAway > 0);
    }

    private static int parseGoals(String value) {
        try {
            String clean = value == null ? "0" : value.trim();
            if (clean.isEmpty()) return 0;
            int slash = clean.indexOf('/');
            if (slash >= 0) clean = clean.substring(0, slash);
            int dash = clean.indexOf('-');
            if (dash >= 0) clean = clean.substring(0, dash);
            return Integer.parseInt(clean.replaceAll("[^0-9]", ""));
        } catch (Exception e) {
            return 0;
        }
    }

    private static boolean isHalfTimeStatus(String status) {
        if (status == null) return false;
        String s = status.trim().toLowerCase(Locale.US);
        return s.contains("half") || s.contains("ht") || s.contains("45+") || s.contains("45'");
    }

    private static boolean isFullTimeStatus(String status) {
        if (status == null) return false;
        String s = status.trim().toLowerCase(Locale.US);
        return s.contains("full time") || s.contains("full-time") || s.contains("ft") || s.contains("final") || s.contains("ended");
    }

    private static String buildGoalMessage(Cricket.Match match, String previous) {
        String minute = (match.goalMinute == null || match.goalMinute.trim().isEmpty())
                ? ((match.status == null || match.status.trim().isEmpty()) ? "25'" : match.status.trim())
                : match.goalMinute.trim();
        String scorer = (match.scorer == null || match.scorer.trim().isEmpty()) ? "" : match.scorer.trim();
        String homeName = (match.short1 == null || match.short1.isEmpty()) ? match.t1 : match.short1;
        String awayName = (match.short2 == null || match.short2.isEmpty()) ? match.t2 : match.short2;
        if (!scorer.isEmpty()) return "GOAL • " + scorer + " " + minute;
        String[] prevParts = previous.split("-");
        int prevHome = prevParts.length > 0 ? parseGoals(prevParts[0]) : 0;
        int prevAway = prevParts.length > 1 ? parseGoals(prevParts[1]) : 0;
        int home = parseGoals(match.s1), away = parseGoals(match.s2);
        if (home > prevHome) return "GOAL • " + homeName + " " + minute;
        if (away > prevAway) return "GOAL • " + awayName + " " + minute;
        return "GOAL • " + (home >= away ? homeName : awayName) + " " + minute;
    }

    private static String formatResultLine(Cricket.Match match) {
        String home = (match.short1 == null || match.short1.isEmpty()) ? match.t1 : match.short1;
        String away = (match.short2 == null || match.short2.isEmpty()) ? match.t2 : match.short2;
        return home + " " + (match.s1 == null ? "0" : match.s1) + "–" + (match.s2 == null ? "0" : match.s2) + " " + away;
    }

    private void showOverlay(String label, String message, String league, String homeUrl, String awayUrl, String leagueLogoUrl) {
        if (root != null) {
            if (windowManager != null) {
                try { windowManager.removeView(root); } catch (RuntimeException ignored) {}
            }
            root = null;
        }

        final float dp = getResources().getDisplayMetrics().density;
        Context ctx = this;

        LinearLayout container = new LinearLayout(ctx);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setPadding(Math.round(18 * dp), Math.round(14 * dp), Math.round(18 * dp), Math.round(14 * dp));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xEB111722);
        bg.setCornerRadius(22 * dp);
        bg.setStroke(Math.round(1.2f * dp), 0x33FFFFFF);
        container.setBackground(bg);

        LinearLayout header = new LinearLayout(ctx);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);

        // League logo badge (shown before team badges to show which competition this is).
        ImageView leagueLogoView = new ImageView(ctx);
        LinearLayout.LayoutParams leagueLogLp = new LinearLayout.LayoutParams(Math.round(20 * dp), Math.round(20 * dp));
        leagueLogLp.rightMargin = Math.round(6 * dp);
        leagueLogoView.setLayoutParams(leagueLogLp);
        leagueLogoView.setScaleType(ImageView.ScaleType.FIT_CENTER);
        leagueLogoView.setVisibility(View.GONE);
        header.addView(leagueLogoView);

        LinearLayout badgeWrap = new LinearLayout(ctx);
        badgeWrap.setOrientation(LinearLayout.HORIZONTAL);
        badgeWrap.setGravity(Gravity.CENTER_VERTICAL);

        ImageView homeBadge = new ImageView(ctx);
        LinearLayout.LayoutParams homeLp = new LinearLayout.LayoutParams(Math.round(30 * dp), Math.round(30 * dp));
        homeBadge.setLayoutParams(homeLp);
        homeBadge.setScaleType(ImageView.ScaleType.CENTER_CROP);
        homeBadge.setPadding(Math.round(2 * dp), Math.round(2 * dp), Math.round(2 * dp), Math.round(2 * dp));
        badgeWrap.addView(homeBadge);

        ImageView awayBadge = new ImageView(ctx);
        LinearLayout.LayoutParams awayLp = new LinearLayout.LayoutParams(Math.round(30 * dp), Math.round(30 * dp));
        awayLp.leftMargin = Math.round(8 * dp);
        awayBadge.setLayoutParams(awayLp);
        awayBadge.setScaleType(ImageView.ScaleType.CENTER_CROP);
        awayBadge.setPadding(Math.round(2 * dp), Math.round(2 * dp), Math.round(2 * dp), Math.round(2 * dp));
        badgeWrap.addView(awayBadge);

        header.addView(badgeWrap);

        LinearLayout labelCol = new LinearLayout(ctx);
        labelCol.setOrientation(LinearLayout.VERTICAL);
        labelCol.setPadding(Math.round(10 * dp), 0, 0, 0);

        TextView livePill = new TextView(ctx);
        livePill.setText((label == null ? "GOAL" : label).toUpperCase(Locale.US));
        livePill.setTextColor(label != null && label.equals("GOAL") ? 0xFFFF5B4D : label != null && label.equals("HALF TIME") ? 0xFFFFB14A : 0xFF70F0A1);
        livePill.setTextSize(12f);
        livePill.setTypeface(getResources().getFont(R.font.outfit));
        livePill.getPaint().setFakeBoldText(true);
        labelCol.addView(livePill);

        TextView leagueText = new TextView(ctx);
        leagueText.setText((league == null || league.trim().isEmpty()) ? "LIVE" : league.toUpperCase(Locale.US));
        leagueText.setTextColor(0xFFBFC9D6);
        leagueText.setTextSize(10f);
        leagueText.setTypeface(getResources().getFont(R.font.outfit));
        leagueText.setPadding(0, Math.round(3 * dp), 0, 0);
        labelCol.addView(leagueText);

        header.addView(labelCol);
        container.addView(header);

        TextView mainText = new TextView(ctx);
        mainText.setText(message == null ? "" : message);
        mainText.setTextColor(0xFFF8FAFC);
        mainText.setTextSize(15f);
        mainText.setTypeface(getResources().getFont(R.font.outfit));
        mainText.getPaint().setFakeBoldText(true);
        mainText.setPadding(0, Math.round(8 * dp), 0, 0);
        container.addView(mainText);

        TextView statusText = new TextView(ctx);
        statusText.setText("LIVE MATCH");
        statusText.setTextColor(0xFFB3BCC9);
        statusText.setTextSize(10.5f);
        statusText.setTypeface(getResources().getFont(R.font.outfit));
        statusText.setPadding(0, Math.round(4 * dp), 0, 0);
        container.addView(statusText);

        windowManager = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                Build.VERSION.SDK_INT >= 26 ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY : WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.BOTTOM | Gravity.END;
        params.x = Math.round(42 * dp);
        params.y = Math.round(42 * dp);

        root = container;
        windowManager.addView(root, params);

        if (homeUrl != null && !homeUrl.trim().isEmpty()) {
            loadBadge(homeBadge, homeUrl);
        }
        if (awayUrl != null && !awayUrl.trim().isEmpty()) {
            loadBadge(awayBadge, awayUrl);
        }
        if (leagueLogoUrl != null && !leagueLogoUrl.trim().isEmpty()) {
            leagueLogoView.setVisibility(View.VISIBLE);
            loadBadge(leagueLogoView, leagueLogoUrl);
        }

        root.postDelayed(() -> {
            try {
                if (windowManager != null && root != null) windowManager.removeView(root);
            } catch (RuntimeException ignored) {
            }
            root = null;
            stopSelf();
        }, ALERT_VISIBLE_MS);
    }

    private void loadBadge(final ImageView target, String badgeUrl) {
        new Thread(() -> {
            File badgeFile = WatchNext.cached(this, new File(getExternalFilesDir(null), "cricket/flags"), badgeUrl, 64);
            Bitmap bmp = badgeFile == null ? null : BitmapFactory.decodeFile(badgeFile.getPath());
            if (bmp != null) {
                target.post(() -> target.setImageBitmap(bmp));
            }
        }).start();
    }
}
