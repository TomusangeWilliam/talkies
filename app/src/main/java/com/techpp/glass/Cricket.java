package com.techpp.glass;

import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.media.tv.TvContract;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TimeZone;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Cricket scores from CricketData.org (formerly CricAPI; your own free key in files/cricket_key.txt): international
 * matches (all teams, men's and women's) and the domestic competitions of the TV's country (Region), live or on
 * today; the home nation's matches come first.
 * The free key allows about 100 requests a day, so this asks at most every 15 minutes while a match is live and
 * every hour otherwise, only while the home screen is showing, and stops for the day near the limit.
 * Watching: the apps' own TV channels are searched for a card naming both teams (that card's link opens the stream).
 * Otherwise, in India, the app that holds the rights there opens; elsewhere rights vary too much to guess, so the
 * card opens nothing and says so.
 */
final class Cricket {
    private static final String TAG = "GlassCricket";
    private static final long LIVE_MS = 15 * 60_000L, IDLE_MS = 60 * 60_000L, RETRY_MS = 10 * 60_000L;
    private static final int DAILY_STOP = 92;
    /** Statuses of a match that's on but not being played right now ("Day 1: Stumps - …", "Lunch", "Rain delay"). */
    private static final java.util.regex.Pattern PAUSED = java.util.regex.Pattern.compile(
            "\\b(stumps|lunch|tea|dinner|rain|wet outfield|bad light|delay|delayed|play stopped|no play|suspended)\\b",
            java.util.regex.Pattern.CASE_INSENSITIVE);

    /** Full-member and associate nations: a match between two of these (or their women's sides) is international. */
    private static final Set<String> NATIONS = new HashSet<>(Arrays.asList(
            "india", "australia", "england", "south africa", "new zealand", "pakistan", "sri lanka", "bangladesh",
            "west indies", "afghanistan", "ireland", "zimbabwe", "netherlands", "scotland", "nepal", "oman",
            "united arab emirates", "uae", "united states of america", "usa", "namibia", "canada", "hong kong",
            "papua new guinea", "kenya", "uganda", "italy", "jersey"));
    /** Each cricket country's domestic competitions (matched in the series name), by the TV's country code. */
    private static final java.util.Map<String, String[]> DOMESTIC = new java.util.HashMap<>();
    /** The TV's country code → its national team's name in the feed. */
    private static final java.util.Map<String, String> HOME_TEAM = new java.util.HashMap<>();
    static {
        DOMESTIC.put("IN", new String[]{"indian premier league", "ipl", "women's premier league", "ranji trophy",
                "syed mushtaq ali", "vijay hazare", "duleep trophy", "irani cup", "irani trophy", "deodhar trophy",
                "cooch behar", "cos nayudu", "ck nayudu"});
        DOMESTIC.put("AU", new String[]{"big bash", "sheffield shield", "marsh cup", "one-day cup"});
        DOMESTIC.put("GB", new String[]{"county championship", "the hundred", "vitality blast", "t20 blast", "one-day cup"});
        DOMESTIC.put("NZ", new String[]{"super smash", "plunket shield", "ford trophy"});
        DOMESTIC.put("ZA", new String[]{"sa20", "csa t20", "4-day series", "one-day cup"});
        DOMESTIC.put("PK", new String[]{"pakistan super league", "psl", "quaid-e-azam", "national t20"});
        DOMESTIC.put("LK", new String[]{"lanka premier league"});
        DOMESTIC.put("BD", new String[]{"bangladesh premier league", "bpl"});
        DOMESTIC.put("AE", new String[]{"ilt20", "international league t20"});
        DOMESTIC.put("US", new String[]{"major league cricket"});
        String[][] home = {{"IN", "india"}, {"AU", "australia"}, {"GB", "england"}, {"NZ", "new zealand"},
                {"ZA", "south africa"}, {"PK", "pakistan"}, {"LK", "sri lanka"}, {"BD", "bangladesh"},
                {"AF", "afghanistan"}, {"IE", "ireland"}, {"ZW", "zimbabwe"}, {"NL", "netherlands"}, {"NP", "nepal"},
                {"OM", "oman"}, {"AE", "united arab emirates"}, {"US", "united states of america"}, {"CA", "canada"},
                {"NA", "namibia"}, {"HK", "hong kong"}, {"KE", "kenya"}, {"UG", "uganda"}};
        for (String[] h : home) HOME_TEAM.put(h[0], h[1]);
        // West Indies plays for the Caribbean.
        for (String cc : new String[]{"JM", "TT", "BB", "GY", "AG", "LC", "VC", "GD", "KN", "DM"}) {
            HOME_TEAM.put(cc, "west indies");
            DOMESTIC.put(cc, new String[]{"caribbean premier league", "cpl", "super50"});
        }
    }
    /** India's cricket streamers: their TV channels may carry a live match card, and one has the rights. */
    static final String HOTSTAR = "in.startv.hotstar", FANCODE = "com.fancode.tv", SONYLIV = "com.sonyliv",
            PRIME = "com.amazon.amazonvideo.livingroom", XSTREAM = "tv.airtel.xstream.tvapp";
    private static final String[] STREAMERS = {HOTSTAR, FANCODE, SONYLIV, PRIME, XSTREAM};

    static final class Match {
        String id, name, status, type, start;   // start: "HH:mm" local, for upcoming
        boolean live, ended, home, domestic, intl, test;
        boolean paused;                           // on, but not playing now: stumps, lunch, tea, rain… (not "live")
        String kind;                              // "Test", "ODI", "T20I"; domestic "First-class", "One-day", "T20"
        String t1, t2, s1 = "", s2 = "";         // short names and score text ("245/4 (42.3)")
        String full1, full2;                      // full team names ("India", "New Zealand")
        String img1, img2;
        String watchPkg, watchUri;
        String search;                            // no match card found: typed into the app's search ("India vs New Zealand")
        int rank;                                 // lower shows first
        boolean football;                         // a Football match (same card): kind is the league, status the clock
        String short1, short2;                    // football: three-letter names for the score line ("ARS")
        String league;                            // football: the league's short name for the score line ("EPL")
        String leagueLogo;                        // football: the league logo URL (from ESPN leagues array, dark variant)
        String scorer;                            // football: scorer name from the last goal event, if the feed exposes one
        String goalMinute;                        // football: the minute of the last goal, from the match detail feed
        long at;                                  // kickoff time in UTC epoch millis
    }

    interface Listener { void onMatches(List<Match> matches); }

    private final Context ctx;
    private final File dir;
    private final SharedPreferences prefs;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private boolean running;

    Cricket(Context c) {
        ctx = c.getApplicationContext();
        dir = new File(c.getExternalFilesDir(null), "cricket");
        prefs = c.getSharedPreferences("cricket", Context.MODE_PRIVATE);
    }

    private String key() {
        try (InputStream in = new FileInputStream(new File(dir.getParentFile(), "cricket_key.txt"))) {
            String k = new String(readAll(in), StandardCharsets.UTF_8).trim();
            return k.isEmpty() ? null : k;
        } catch (IOException e) {
            return null;
        }
    }

    /** The last matches fetched (empty without a key), with watch links looked up again. Blocking (reads channels). */
    List<Match> cached() {
        List<Match> out = new ArrayList<>();
        if (key() == null) return out;
        try (InputStream in = new FileInputStream(new File(dir, "matches.json"))) {
            out = parse(new JSONObject(new String(readAll(in), StandardCharsets.UTF_8)), Region.country(ctx));
        } catch (Exception e) {
            return out;
        }
        JSONObject innings = readInnings();
        for (Match m : out) {
            applyInnings(m, innings);
            findStream(m);
        }
        return out;
    }

    /** The network is back: a fetch that failed while it was down may run again now, without waiting out the retry. */
    void networkBack() {
        if (prefs.getLong("try_at", 0) > prefs.getLong("ok_at", 0)) prefs.edit().remove("try_at").apply();
    }

    void refreshIfStale(Listener l) {
        String key = key();
        if (key == null || running) return;
        long now = System.currentTimeMillis();
        String today = new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date());
        if (!today.equals(prefs.getString("day", ""))) prefs.edit().putString("day", today).putInt("hits", 0).apply();
        if (prefs.getInt("hits", 0) >= DAILY_STOP) return;
        long every = prefs.getBoolean("any_live", false) ? LIVE_MS : IDLE_MS;
        if (prefs.getInt("key_hash", 0) != key.hashCode()) {
            prefs.edit().putInt("key_hash", key.hashCode()).remove("ok_at").remove("try_at").apply();
        }
        if (now - prefs.getLong("ok_at", 0) < every || now - prefs.getLong("try_at", 0) < RETRY_MS) return;
        prefs.edit().putLong("try_at", now).apply();
        running = true;
        worker.execute(() -> {
            try {
                JSONObject json = fetch(key);
                if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("Cannot create " + dir);
                File tmp = new File(dir, "matches.json.tmp");
                try (OutputStream out = new FileOutputStream(tmp)) { out.write(json.toString().getBytes(StandardCharsets.UTF_8)); }
                if (!tmp.renameTo(new File(dir, "matches.json"))) throw new IOException("Cannot save");
                JSONObject info = json.optJSONObject("info");
                int hits = info != null ? info.optInt("hitsToday", prefs.getInt("hits", 0) + 1) : prefs.getInt("hits", 0) + 1;
                List<Match> list = parse(json, Region.country(ctx));
                JSONObject innings = updateInnings(key, list);
                for (Match m : list) applyInnings(m, innings);
                boolean anyLive = false;
                for (Match m : list) anyLive |= m.live || m.paused;  // a paused match is checked as often: play resumes
                prefs.edit().putLong("ok_at", System.currentTimeMillis()).putInt("hits", hits).putBoolean("any_live", anyLive).apply();
                for (Match m : list) {
                    findStream(m);
                    Log.i(TAG, m.t1 + " v " + m.t2 + (m.live ? " (live)" : "") + " → " + m.watchPkg + (m.watchUri != null ? " (match link)" : " (app)"));
                }
                Log.i(TAG, list.size() + " matches (" + (anyLive ? "live" : "none live") + "), " + hits + " requests today");
                main.post(() -> l.onMatches(list));
            } catch (Exception e) {
                Log.w(TAG, "Refresh failed: " + e.getClass().getSimpleName() + " " + e.getMessage());
            } finally {
                main.post(() -> running = false);
            }
        });
    }

    // ---------------------------------------------------------------- Test innings

    /**
     * The quick score list only has each team's latest innings, so a live Test's full innings come from match_info:
     * for at most three live Tests (the ones shown first), each at most every 45 minutes. Kept in innings.json.
     */
    private JSONObject updateInnings(String key, List<Match> list) {
        JSONObject all = readInnings(), keep = new JSONObject();
        int asked = 0;
        long now = System.currentTimeMillis();
        boolean first = true;
        for (Match m : list) {
            if (!m.test || (!m.live && !m.paused && !m.ended)) continue;
            JSONObject rec = all.optJSONObject(m.id);
            // The Test shown first follows every refresh (15 min); others every 45 min; all of them 45 once the day's
            // requests run low.
            long every = m.live && first && prefs.getInt("hits", 0) < 70 ? 14 * 60_000L : 45 * 60_000L;
            if (m.live) first = false;
            boolean stale = rec == null || (m.live && now - rec.optLong("at") > every);
            if (stale && asked < 3 && prefs.getInt("hits", 0) + asked < DAILY_STOP) {
                asked++;
                try {
                    JSONObject d = get("https://api.cricapi.com/v1/match_info?id=" + m.id + "&apikey=" + key).getJSONObject("data");
                    rec = new JSONObject().put("at", now).put("score", d.optJSONArray("score") == null ? new JSONArray() : d.getJSONArray("score"));
                } catch (Exception e) {
                    Log.w(TAG, "Innings for " + m.t1 + " v " + m.t2 + " failed: " + e.getMessage());
                }
            }
            try { if (rec != null) keep.put(m.id, rec); } catch (Exception ignored) { }
        }
        if (asked > 0) prefs.edit().putInt("hits", prefs.getInt("hits", 0) + asked).apply();
        try (OutputStream out = new FileOutputStream(new File(dir, "innings.json"))) {
            out.write(keep.toString().getBytes(StandardCharsets.UTF_8));
        } catch (IOException ignored) { }
        return keep;
    }

    private JSONObject readInnings() {
        try (InputStream in = new FileInputStream(new File(dir, "innings.json"))) {
            return new JSONObject(new String(readAll(in), StandardCharsets.UTF_8));
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    private static final class Inn {
        String team;
        int r, w;
        String o;
        boolean done;   // all out, declared, or the other side batted after it
    }

    /** Rewrites a Test's scores from its innings: "425/9d & 84/1 (25)" and "99". */
    private static void applyInnings(Match m, JSONObject all) {
        JSONObject rec = all.optJSONObject(m.id);
        if (!m.test || rec == null) return;
        JSONArray sc = rec.optJSONArray("score");
        if (sc == null || sc.length() == 0) return;
        List<Inn> inns = new ArrayList<>();
        String prev = null;
        for (int i = 0; i < sc.length(); i++) {
            JSONObject s = sc.optJSONObject(i);
            if (s == null) continue;
            Inn in = new Inn();
            String label = s.optString("inning", "").toLowerCase(Locale.ROOT).replaceAll("\\s+inning.*", "").trim();
            // The feed sometimes labels an innings with both teams ("a,b"): it's the side that didn't bat just before.
            if (label.contains(",")) {
                String[] two = label.split(",");
                label = two[0].trim().equals(prev) ? two[1].trim() : two[0].trim();
            }
            in.team = label;
            in.r = s.optInt("r");
            in.w = s.optInt("w");
            in.o = s.optString("o");
            if (in.o.endsWith(".0")) in.o = in.o.substring(0, in.o.length() - 2);
            in.done = in.w >= 10;
            inns.add(in);
            prev = label;
        }
        fixLastInnings(inns, m);
        for (int i = 0; i < inns.size() - 1; i++) inns.get(i).done = true;   // someone batted after it
        if (m.ended && !inns.isEmpty()) inns.get(inns.size() - 1).done = true;
        m.s1 = teamScore(inns, m.full1.toLowerCase(Locale.ROOT), m.s1, m.live || m.paused);
        m.s2 = teamScore(inns, m.full2.toLowerCase(Locale.ROOT), m.s2, m.live || m.paused);
    }

    /**
     * The feed sometimes credits the latest innings to the wrong team (a follow-on came through as "South Africa
     * Inning 2" when Australia were batting). The status says who leads, trails or needs how many, so check the
     * innings against it and swap the latest one to the other team when only that adds up.
     */
    private static void fixLastInnings(List<Inn> inns, Match m) {
        if (inns.isEmpty()) return;
        String a = m.full1.toLowerCase(Locale.ROOT), b = m.full2.toLowerCase(Locale.ROOT);
        Inn last = inns.get(inns.size() - 1);
        if (!last.team.equals(a) && !last.team.equals(b)) return;
        String other = last.team.equals(a) ? b : a;
        if (consistent(inns, m.status, a, b)) return;
        String was = last.team;
        last.team = other;
        if (consistent(inns, m.status, a, b)) {
            Log.i(TAG, "Latest innings of " + m.t1 + " v " + m.t2 + " belongs to " + other + ", not " + was + " (from the status)");
        } else {
            last.team = was;  // the status doesn't settle it: keep the feed's label
        }
    }

    /** Whether the runs add up with "X trail by N runs", "X lead by N runs" or "X need N runs". Unknown status: yes. */
    private static boolean consistent(List<Inn> inns, String status, String a, String b) {
        java.util.regex.Matcher mm = java.util.regex.Pattern
                .compile("(.+?) (trail|lead|need)s? by (\\d+) run|(.+?) (need) (\\d+) run").matcher(status.toLowerCase(Locale.ROOT));
        if (!mm.find()) return true;
        String who = (mm.group(1) != null ? mm.group(1) : mm.group(4)).replaceAll(".*[:\\-–] *", "").trim();
        String verb = mm.group(2) != null ? mm.group(2) : mm.group(5);
        int n = Integer.parseInt(mm.group(3) != null ? mm.group(3) : mm.group(6));
        String team = who.equals(a) || a.startsWith(who) ? a : who.equals(b) || b.startsWith(who) ? b : null;
        if (team == null) return true;
        String opp = team.equals(a) ? b : a;
        int mine = 0, theirs = 0;
        for (Inn in : inns) {
            if (in.team.equals(team)) mine += in.r;
            else if (in.team.equals(opp)) theirs += in.r;
        }
        switch (verb) {
            case "trail": return mine + n == theirs;
            case "lead": return mine == theirs + n;
            default: return mine + n == theirs + 1;   // need N to win
        }
    }

    /**
     * One team's innings joined with "&": completed ones as "99" (all out) or "425/9d" (declared), the one in progress
     * as "84/1 (25)".
     */
    private static String teamScore(List<Inn> inns, String team, String latest, boolean live) {
        List<Inn> mine = new ArrayList<>();
        for (Inn in : inns) if (in.team.equals(team)) mine.add(in);
        if (mine.isEmpty()) return latest;
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < mine.size(); i++) {
            Inn in = mine.get(i);
            boolean isLast = i == mine.size() - 1;
            if (b.length() > 0) b.append(" & ");
            if (in.w >= 10) b.append(in.r);
            else if (in.done) b.append(in.r).append('/').append(in.w).append('d');
            else b.append(in.r).append('/').append(in.w).append(" (").append(in.o).append(')');
        }
        // The quick list's per-team figures can't be trusted for Tests (it credited a follow-on to the wrong side),
        // so a Test's scores come only from its innings list, refreshed above.
        return b.toString();
    }

    private static JSONObject get(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(15_000);
        c.setReadTimeout(20_000);
        try {
            if (c.getResponseCode() != 200) throw new IOException("HTTP " + c.getResponseCode());
            try (InputStream in = c.getInputStream()) {
                JSONObject j = new JSONObject(new String(readAll(in), StandardCharsets.UTF_8));
                if (!"success".equalsIgnoreCase(j.optString("status"))) throw new IOException(j.optString("reason", j.optString("status")));
                return j;
            }
        } finally {
            c.disconnect();
        }
    }

    /** Whether CricketData takes this key (Keys.OK, REJECTED or OFFLINE), for Control Center. Uses one of its daily calls. */
    static int check(String key) {
        try {
            fetch(key);
            return Keys.OK;
        } catch (IOException e) {
            return String.valueOf(e.getMessage()).startsWith("API says") ? Keys.REJECTED : Keys.OFFLINE;
        } catch (Exception e) {
            return Keys.REJECTED;
        }
    }

    private static JSONObject fetch(String key) throws Exception {
        // cricScore: every current match (fixtures, live, results) in one request, with scores ready to show.
        HttpURLConnection c = (HttpURLConnection) new URL("https://api.cricapi.com/v1/cricScore?apikey=" + key).openConnection();
        c.setConnectTimeout(15_000);
        c.setReadTimeout(20_000);
        try {
            if (c.getResponseCode() != 200) throw new IOException("HTTP " + c.getResponseCode());
            try (InputStream in = c.getInputStream()) {
                JSONObject j = new JSONObject(new String(readAll(in), StandardCharsets.UTF_8));
                if (!"success".equalsIgnoreCase(j.optString("status"))) throw new IOException("API says: " + j.optString("reason", j.optString("status")));
                return j;
            }
        } finally {
            c.disconnect();
        }
    }

    // ---------------------------------------------------------------- which matches

    private static List<Match> parse(JSONObject json, String cc) throws Exception {
        String[] domesticNames = DOMESTIC.getOrDefault(cc, new String[0]);
        String homeTeam = HOME_TEAM.get(cc);
        List<Match> out = new ArrayList<>();
        JSONArray data = json.optJSONArray("data");
        if (data == null) return out;
        String today = new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date());
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < data.length(); i++) {
            JSONObject o = data.getJSONObject(i);
            if (!seen.add(o.optString("id"))) continue;
            // Teams come as "India [IND]".
            String[] a = team(o.optString("t1")), b = team(o.optString("t2"));
            if (a[0].isEmpty() || b[0].isEmpty()) continue;
            String series = o.optString("series", "");
            boolean intl = nation(a[0]) && nation(b[0]);
            String lower = series.toLowerCase(Locale.ROOT);
            boolean domestic = false;
            for (String d : domesticNames) if (lower.contains(d)) { domestic = true; break; }
            if (!intl && !domestic) continue;
            String ms = o.optString("ms", "");
            Match m = new Match();
            m.id = o.optString("id");
            m.full1 = a[0];
            m.full2 = b[0];
            m.name = a[0] + " vs " + b[0] + ", " + series;
            m.status = o.optString("status", "");
            m.type = o.optString("matchType", "").toUpperCase(Locale.ROOT);
            // The feed sometimes leaves a match "live" that never started ("Match starts at Oct 18…"): it isn't.
            m.live = "live".equals(ms) && !m.status.toLowerCase(Locale.ROOT).startsWith("match starts");
            // A Test stays "live" in the feed for all five days; at stumps, a meal break or a rain delay nobody is
            // playing, so it isn't live (a live football match wins the score line), only paused.
            if (m.live && PAUSED.matcher(m.status).find()) {
                m.live = false;
                m.paused = true;
            }
            m.ended = "result".equals(ms);
            m.domestic = domestic;
            m.home = homeTeam != null && (a[0].toLowerCase(Locale.ROOT).startsWith(homeTeam)
                    || b[0].toLowerCase(Locale.ROOT).startsWith(homeTeam));
            String gmt = o.optString("dateTimeGMT", "");
            // Live, or starting or finished today (a Test that began days ago is still live).
            if (!m.live && !m.paused && !today.equals(localDate(gmt, ""))) continue;
            m.start = m.live || m.paused || m.ended ? null : localTime(gmt);
            m.t1 = a[1];
            m.t2 = b[1];
            m.s1 = o.optString("t1s", "").trim();
            m.s2 = o.optString("t2s", "").trim();
            m.img1 = o.optString("t1img", null);
            m.img2 = o.optString("t2img", null);
            m.intl = intl;
            // The feed sometimes leaves the type blank; a status like "Day 4: …" means a multi-day match.
            m.test = m.type.equals("TEST") || (m.type.isEmpty() && m.status.toLowerCase(Locale.ROOT).startsWith("day "));
            m.kind = m.test ? (intl ? "Test" : "First-class") : m.type.equals("ODI") ? (intl ? "ODI" : "One-day")
                    : m.type.equals("T20") ? (intl ? "T20I" : "T20") : "";
            m.rank = (m.live ? 0 : m.paused ? 5 : m.ended ? 20 : 10) + (m.home ? 0 : 4) + (intl ? 0 : 2);
            out.add(m);
        }
        out.sort((x, y) -> Integer.compare(x.rank, y.rank));
        return out;
    }

    /** "India Women [INDW]" → {"India Women", "IND-W"}. */
    private static String[] team(String s) {
        String name = s, code = "";
        int k = s.lastIndexOf(" [");
        if (k > 0 && s.endsWith("]")) {
            name = s.substring(0, k).trim();
            code = s.substring(k + 2, s.length() - 1).trim().toUpperCase(Locale.ROOT);
        }
        if (code.isEmpty()) code = name.length() <= 4 ? name.toUpperCase(Locale.ROOT) : name.substring(0, 3).toUpperCase(Locale.ROOT);
        if (name.toLowerCase(Locale.ROOT).endsWith(" women") && code.endsWith("W") && !code.endsWith("-W")) {
            code = code.substring(0, code.length() - 1) + "-W";
        }
        return new String[]{name, code};
    }

    private static boolean nation(String team) {
        String t = team.toLowerCase(Locale.ROOT).trim();
        if (t.endsWith(" women")) t = t.substring(0, t.length() - 6).trim();
        return NATIONS.contains(t);
    }




    private static Date parseGmt(String gmt) {
        try {
            SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US);
            f.setTimeZone(TimeZone.getTimeZone("GMT"));
            return f.parse(gmt);
        } catch (Exception e) {
            return null;
        }
    }

    private static String localDate(String gmt, String fallback) {
        Date d = parseGmt(gmt);
        return d == null ? fallback : new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(d);
    }

    private static String localTime(String gmt) {
        Date d = parseGmt(gmt);
        return d == null ? null : new SimpleDateFormat("h:mm a", Locale.US).format(d).toLowerCase(Locale.ROOT);
    }

    // ---------------------------------------------------------------- where to watch

    /**
     * Looks through the streaming apps' TV channels for a card that names both teams (e.g. JioHotstar's
     * "IND vs AUS, 2nd ODI – LIVE"): its link opens the stream itself. Otherwise picks the app with the rights in India,
     * and for JioHotstar its search, with both teams typed in (see SleepService.searchHotstar).
     */
    private void findStream(Match m) {
        boolean india = "IN".equals(Region.country(ctx));
        String[] names1 = {m.t1.toLowerCase(Locale.ROOT).replace("-w", ""), m.full1.toLowerCase(Locale.ROOT)},
                names2 = {m.t2.toLowerCase(Locale.ROOT).replace("-w", ""), m.full2.toLowerCase(Locale.ROOT)};
        try (Cursor ch = ctx.getContentResolver().query(TvContract.Channels.CONTENT_URI,
                new String[]{TvContract.Channels._ID, TvContract.Channels.COLUMN_PACKAGE_NAME, TvContract.Channels.COLUMN_TYPE},
                null, null, null)) {
            while (ch != null && ch.moveToNext()) {
                String pkg = ch.getString(1);
                // In India, only the cricket streamers' rows (news apps name teams too); elsewhere any app's.
                if (india && !Arrays.asList(STREAMERS).contains(pkg)) continue;
                if (!TvContract.Channels.TYPE_PREVIEW.equals(ch.getString(2))) continue;
                try (Cursor p = ctx.getContentResolver().query(TvContract.buildPreviewProgramsUriForChannel(ch.getLong(0)),
                        new String[]{TvContract.PreviewPrograms.COLUMN_TITLE, TvContract.PreviewPrograms.COLUMN_SHORT_DESCRIPTION,
                                TvContract.PreviewPrograms.COLUMN_INTENT_URI}, null, null, null)) {
                    while (p != null && p.moveToNext()) {
                        String text = (p.getString(0) + " " + p.getString(1)).toLowerCase(Locale.ROOT);
                        if (mentions(text, names1) && mentions(text, names2) && p.getString(2) != null) {
                            m.watchPkg = pkg;
                            m.watchUri = p.getString(2);
                            return;
                        }
                    }
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Couldn't search channels: " + e);
        }
        // No card found: in India the rights holder opens (JioHotstar with its search typed in); elsewhere nothing.
        m.watchPkg = india ? rightsHolder(m) : null;
        m.search = india ? m.full1 + " vs " + m.full2 : null;
    }

    private static boolean mentions(String text, String[] names) {
        for (String n : names) {
            if (n.isEmpty()) continue;
            if (n.length() <= 4 ? text.matches("(?s).*\\b" + java.util.regex.Pattern.quote(n) + "\\b.*") : text.contains(n)) return true;
        }
        return false;
    }

    /** Who streams it in India (2026): JioHotstar has BCCI (home internationals, domestic, IPL) and ICC events. */
    private static String rightsHolder(Match m) {
        String n = m.name.toLowerCase(Locale.ROOT);
        if (n.contains("lanka premier league")) return FANCODE;
        if (n.contains("asia cup")) return SONYLIV;
        return HOTSTAR;
    }

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] b = new byte[8192];
        for (int n; (n = in.read(b)) > 0; ) out.write(b, 0, n);
        return out.toByteArray();
    }
}

