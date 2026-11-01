package kr.suin.kakao;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.util.Base64;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.content.FileProvider;

import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.spec.MGF1ParameterSpec;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.HashSet;
import java.util.Set;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.OAEPParameterSpec;
import javax.crypto.spec.PSource;
import javax.crypto.spec.SecretKeySpec;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 수인T 카톡 — Claude가 '보낼 함'(Firebase kakaoOutbox)에 넣어 둔 공지 문구와 파일을
 * 선생님이 확인한 뒤 카카오톡으로 넘기는 앱.
 *
 * 안전장치: 이 앱은 스스로 아무것도 보내지 않는다.
 *  1) 선생님이 [카톡으로 보내기] → 확인 창에서 [보내기]를 눌러야 카카오톡이 열리고,
 *  2) 카카오톡에서 받을 방을 고르고 전송을 눌러야 실제로 간다.
 */
public class MainActivity extends Activity {
    private static final int BG = 0xFFF6F5F0, INK = 0xFF1F2430, MUTED = 0xFF6B7280, LINE = 0xFFDADDE3, KAKAO = 0xFFFEE500, KAKAO_INK = 0xFF191919;
    private static final String KAKAO_PKG = "com.kakao.talk";

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private SharedPreferences sp;
    private String idToken;
    private long tokenExp;
    private LinearLayout listBox;
    private TextView status;

    // ------------------------------------------------------------------ lifecycle

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        sp = getSharedPreferences("kakao", MODE_PRIVATE);
        getWindow().setStatusBarColor(BG);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
        if (sp.getString("refresh", "").isEmpty()) showLogin(); else showList();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (listBox != null) load();
    }

    // ------------------------------------------------------------------ small UI helpers

    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    private GradientDrawable box(int bg, int stroke, int r) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(bg);
        g.setCornerRadius(dp(r));
        if (stroke != 0) g.setStroke(dp(1), stroke);
        return g;
    }

    private TextView text(String s, int sp_, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp_);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    private Button button(String label, int bg, int fg) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextSize(15);
        b.setTextColor(fg);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setBackground(box(bg, bg == Color.WHITE ? LINE : 0, 10));
        b.setPadding(dp(16), dp(10), dp(16), dp(10));
        b.setStateListAnimator(null);
        return b;
    }

    private EditText input(String hint, int type) {
        EditText e = new EditText(this);
        e.setHint(hint);
        e.setInputType(type);
        e.setTextSize(16);
        e.setBackground(box(Color.WHITE, LINE, 10));
        e.setPadding(dp(14), dp(12), dp(14), dp(12));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(8);
        e.setLayoutParams(lp);
        return e;
    }

    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_LONG).show(); }

    // ------------------------------------------------------------------ Firebase REST

    private static String readAll(InputStream in) throws Exception {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        byte[] buf = new byte[65536];
        int n;
        while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
        return bo.toString("UTF-8");
    }

    private static String http(String method, String url, String body, String ctype) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(20000);
        c.setReadTimeout(60000);
        if ("PATCH".equals(method)) { c.setRequestMethod("POST"); c.setRequestProperty("X-HTTP-Method-Override", "PATCH"); }
        else c.setRequestMethod(method);
        if (body != null) {
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", ctype);
            try (OutputStream o = c.getOutputStream()) { o.write(body.getBytes(StandardCharsets.UTF_8)); }
        }
        int code = c.getResponseCode();
        String res = readAll(code < 400 ? c.getInputStream() : c.getErrorStream());
        if (code >= 400) throw new Exception("HTTP " + code + " " + res);
        return res;
    }

    private void signIn(String email, String pw) throws Exception {
        JSONObject b = new JSONObject().put("email", email).put("password", pw).put("returnSecureToken", true);
        JSONObject r = new JSONObject(http("POST", "https://identitytoolkit.googleapis.com/v1/accounts:signInWithPassword?key=" + Config.API_KEY, b.toString(), "application/json"));
        idToken = r.getString("idToken");
        tokenExp = System.currentTimeMillis() + (Long.parseLong(r.optString("expiresIn", "3600")) - 120) * 1000L;
        sp.edit().putString("refresh", r.getString("refreshToken")).putString("email", email).apply();
    }

    private String token() throws Exception {
        if (idToken != null && System.currentTimeMillis() < tokenExp) return idToken;
        String body = "grant_type=refresh_token&refresh_token=" + URLEncoder.encode(sp.getString("refresh", ""), "UTF-8");
        JSONObject r = new JSONObject(http("POST", "https://securetoken.googleapis.com/v1/token?key=" + Config.API_KEY, body, "application/x-www-form-urlencoded"));
        idToken = r.getString("id_token");
        tokenExp = System.currentTimeMillis() + (Long.parseLong(r.optString("expires_in", "3600")) - 120) * 1000L;
        sp.edit().putString("refresh", r.getString("refresh_token")).apply();
        return idToken;
    }

    private String db(String method, String path, String body) throws Exception {
        return http(method, Config.DB_URL + "/" + path + ".json?auth=" + token(), body, "application/json");
    }

    // ------------------------------------------------------------------ GitHub 우편함 (암호화)
    // Claude는 Firebase에 직접 못 들어가므로, 이 폰의 공개 열쇠로 암호화한 공지를
    // GitHub 저장소의 outbox 가지에 올린다. 풀 수 있는 건 이 폰(비밀 열쇠)뿐이다.
    private static final String GH_LIST = "https://api.github.com/repos/suin0721/suin-attend/contents/outbox?ref=outbox";
    private static final String GH_RAW = "https://raw.githubusercontent.com/suin0721/suin-attend/outbox/outbox/";

    private void ensureKeys() throws Exception {
        if (!sp.getString("priv", "").isEmpty()) return;
        KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
        g.initialize(2048);
        KeyPair kp = g.generateKeyPair();
        sp.edit().putString("priv", Base64.encodeToString(kp.getPrivate().getEncoded(), Base64.NO_WRAP))
                .putString("pub", Base64.encodeToString(kp.getPublic().getEncoded(), Base64.NO_WRAP)).apply();
    }

    private String pubKey() { try { ensureKeys(); } catch (Exception ignored) { } return sp.getString("pub", ""); }

    private String fingerprint() {
        try {
            byte[] h = MessageDigest.getInstance("SHA-256").digest(Base64.decode(pubKey(), Base64.NO_WRAP));
            StringBuilder b = new StringBuilder();
            for (int i = 0; i < 4; i++) b.append(String.format("%02X", h[i]));
            return b.toString();
        } catch (Exception e) { return "?"; }
    }

    private byte[] unwrapKey(String ek) throws Exception {
        PrivateKey pk = KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(Base64.decode(sp.getString("priv", ""), Base64.NO_WRAP)));
        Cipher c = Cipher.getInstance("RSA/ECB/OAEPPadding");
        c.init(Cipher.DECRYPT_MODE, pk, new OAEPParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA256, PSource.PSpecified.DEFAULT));
        return c.doFinal(Base64.decode(ek, Base64.DEFAULT));
    }

    private static byte[] aesOpen(byte[] key, String iv, byte[] ct) throws Exception {
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, Base64.decode(iv, Base64.DEFAULT)));
        return c.doFinal(ct);
    }

    private static byte[] getBytes(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(20000);
        c.setReadTimeout(120000);
        c.setRequestProperty("Cache-Control", "no-cache");
        int code = c.getResponseCode();
        if (code >= 400) throw new Exception("HTTP " + code);
        try (InputStream in = c.getInputStream()) {
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            return bo.toByteArray();
        }
    }

    private Set<String> localSet(String k) { return new HashSet<>(sp.getStringSet(k, new HashSet<>())); }

    /** GitHub 우편함의 항목들을 풀어서 목록 형식(JSONObject)으로 돌려준다. */
    private List<JSONObject> loadGithub() {
        List<JSONObject> out = new ArrayList<>();
        try {
            if (sp.getString("priv", "").isEmpty()) return out;
            String list = new String(getBytes(GH_LIST), StandardCharsets.UTF_8);
            JSONArray arr = new JSONArray(list);
            Set<String> gone = localSet("ghDeleted"), sent = localSet("ghSent");
            for (int i = 0; i < arr.length(); i++) {
                JSONObject e = arr.getJSONObject(i);
                String name = e.optString("name");
                if (!name.endsWith(".json")) continue;
                String id = "gh:" + name.replace(".json", "");
                if (gone.contains(id)) continue;
                try {
                    JSONObject env = new JSONObject(new String(getBytes(GH_RAW + name), StandardCharsets.UTF_8));
                    byte[] key = unwrapKey(env.getString("ek"));
                    JSONObject o = new JSONObject(new String(aesOpen(key, env.getString("iv"), Base64.decode(env.getString("ct"), Base64.DEFAULT)), StandardCharsets.UTF_8));
                    o.put("_id", id);
                    o.put("_key", Base64.encodeToString(key, Base64.NO_WRAP));
                    if (sent.contains(id)) { o.put("status", "shared"); o.put("sharedAt", sp.getLong("sentAt:" + id, 0)); }
                    out.add(o);
                } catch (Exception bad) {
                    // 다른 열쇠로 잠긴 항목(예전 폰용)은 건너뜀
                }
            }
        } catch (Exception ignored) { }
        return out;
    }

    private void showKeyDialog() {
        String k = pubKey();
        LinearLayout v = new LinearLayout(this);
        v.setOrientation(LinearLayout.VERTICAL);
        v.setPadding(dp(20), dp(8), dp(20), 0);
        v.addView(text("아래 [열쇠 복사]를 눌러 Claude 채팅에 붙여넣어 주세요. Claude는 이 열쇠로 공지를 잠가서 보내고, 여는 건 이 폰만 할 수 있어요. 한 번만 하면 돼요.", 14, INK, false));
        TextView fp = text("열쇠 확인 번호: " + fingerprint(), 15, INK, true);
        fp.setPadding(0, dp(12), 0, dp(4));
        v.addView(fp);
        TextView kt = text(k.length() > 60 ? k.substring(0, 60) + "…" : k, 12, MUTED, false);
        v.addView(kt);
        new AlertDialog.Builder(this)
                .setTitle("Claude 연결 열쇠")
                .setView(v)
                .setPositiveButton("열쇠 복사", (d, w) -> {
                    ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                    if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("열쇠", "수인T카톡열쇠:" + k));
                    toast("복사했어요. Claude 채팅에 붙여넣어 주세요");
                })
                .setNegativeButton("닫기", null).show();
    }

    // ------------------------------------------------------------------ login

    private void showLogin() {
        listBox = null;
        LinearLayout v = new LinearLayout(this);
        v.setOrientation(LinearLayout.VERTICAL);
        v.setBackgroundColor(BG);
        v.setPadding(dp(24), dp(80), dp(24), dp(24));
        v.setFitsSystemWindows(true);
        v.addView(text("수인T 카톡", 26, INK, true));
        TextView sub = text("출결 앱과 같은 계정으로 로그인하세요. Claude가 만든 공지와 파일이 '보낼 함'에 들어와요.", 14, MUTED, false);
        sub.setPadding(0, dp(6), 0, dp(24));
        v.addView(sub);
        EditText email = input("이메일", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS);
        email.setText(sp.getString("email", ""));
        EditText pw = input("비밀번호", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        v.addView(email);
        v.addView(pw);
        Button go = button("로그인", INK, Color.WHITE);
        LinearLayout.LayoutParams gp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52));
        gp.topMargin = dp(16);
        v.addView(go, gp);
        go.setOnClickListener(x -> {
            String e = email.getText().toString().trim(), p = pw.getText().toString();
            if (e.isEmpty() || p.isEmpty()) { toast("이메일과 비밀번호를 넣어 주세요"); return; }
            go.setEnabled(false);
            io.execute(() -> {
                try { signIn(e, p); ui.post(this::showList); }
                catch (Exception err) { ui.post(() -> { go.setEnabled(true); toast("로그인하지 못했어요. 이메일·비밀번호를 확인해 주세요."); }); }
            });
        });
        setContentView(v);
    }

    // ------------------------------------------------------------------ list

    private void showList() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);
        root.setPadding(dp(16), dp(12), dp(16), 0);
        root.setFitsSystemWindows(true);
        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.addView(text("보낼 함", 22, INK, true), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        Button refresh = button("새로고침", Color.WHITE, INK);
        refresh.setOnClickListener(x -> load());
        Button more = button("⋯", Color.WHITE, INK);
        more.setOnClickListener(x -> new AlertDialog.Builder(this)
                .setTitle("설정")
                .setMessage("로그인: " + sp.getString("email", "") + "\n열쇠 확인 번호: " + fingerprint())
                .setPositiveButton("Claude 연결 열쇠", (d, w) -> showKeyDialog())
                .setNeutralButton("로그아웃", (d, w) -> { sp.edit().remove("refresh").apply(); idToken = null; showLogin(); })
                .setNegativeButton("닫기", null).show());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(44));
        lp.leftMargin = dp(8);
        top.addView(refresh, lp);
        top.addView(more, lp);
        root.addView(top);
        status = text("불러오는 중…", 13, MUTED, false);
        status.setPadding(0, dp(8), 0, dp(8));
        root.addView(status);
        ScrollView sc = new ScrollView(this);
        listBox = new LinearLayout(this);
        listBox.setOrientation(LinearLayout.VERTICAL);
        listBox.setPadding(0, 0, 0, dp(24));
        sc.addView(listBox);
        root.addView(sc, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        setContentView(root);
        load();
        if (!sp.getBoolean("keyShown", false)) { sp.edit().putBoolean("keyShown", true).apply(); ui.postDelayed(this::showKeyDialog, 600); }
    }

    private void load() {
        if (listBox == null) return;
        if (status != null) status.setText("불러오는 중…");
        io.execute(() -> {
            try {
                List<JSONObject> items = new ArrayList<>(loadGithub());
                String s = "null";
                try { s = db("GET", "kakaoOutbox", null); } catch (Exception fe) { if (items.isEmpty()) throw fe; }
                if (s != null && !s.trim().equals("null")) {
                    JSONObject all = new JSONObject(s);
                    Iterator<String> it = all.keys();
                    while (it.hasNext()) {
                        String k = it.next();
                        JSONObject o = all.optJSONObject(k);
                        if (o == null || "deleted".equals(o.optString("status"))) continue;
                        o.put("_id", k);
                        items.add(o);
                    }
                }
                Collections.sort(items, (a, b) -> Long.compare(b.optLong("createdAt"), a.optLong("createdAt")));
                ui.post(() -> render(items));
            } catch (Exception e) {
                ui.post(() -> {
                    if (status != null) status.setText("불러오지 못했어요: 인터넷 연결이나 로그인을 확인해 주세요");
                    if (String.valueOf(e.getMessage()).contains("401") || String.valueOf(e.getMessage()).contains("TOKEN")) { sp.edit().remove("refresh").apply(); showLogin(); }
                });
            }
        });
    }

    private static String when(long ms) {
        return ms <= 0 ? "" : new SimpleDateFormat("M월 d일 a h:mm", Locale.KOREA).format(new Date(ms));
    }

    private JSONArray files(JSONObject o) {
        Object f = o.opt("files");
        if (f instanceof JSONArray) return (JSONArray) f;
        JSONArray a = new JSONArray();
        if (f instanceof JSONObject) { Iterator<String> it = ((JSONObject) f).keys(); while (it.hasNext()) a.put(((JSONObject) f).opt(it.next())); }
        return a;
    }

    private void render(List<JSONObject> items) {
        listBox.removeAllViews();
        int ready = 0;
        for (JSONObject o : items) if (!"shared".equals(o.optString("status"))) ready++;
        status.setText(items.isEmpty() ? "보낼 것이 없어요. Claude에게 '카톡 보낼 함에 넣어 줘'라고 하면 여기로 와요." : "보낼 것 " + ready + "개 · 보낸 것 " + (items.size() - ready) + "개");
        for (JSONObject o : items) {
            boolean done = "shared".equals(o.optString("status"));
            LinearLayout card = new LinearLayout(this);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setBackground(box(done ? 0xFFF0F0EC : Color.WHITE, LINE, 14));
            card.setPadding(dp(16), dp(14), dp(16), dp(14));
            LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            cp.bottomMargin = dp(10);
            String title = o.optString("title", "공지");
            card.addView(text((done ? "✓ 보냄 · " : "") + title, 16, done ? MUTED : INK, true));
            String to = o.optString("to", "");
            if (!to.isEmpty()) card.addView(text("받을 곳: " + to, 13, MUTED, false));
            String t = o.optString("text", "");
            TextView pv = text(t.length() > 120 ? t.substring(0, 120) + "…" : t, 14, done ? MUTED : INK, false);
            pv.setPadding(0, dp(6), 0, dp(4));
            card.addView(pv);
            JSONArray fs = files(o);
            for (int i = 0; i < fs.length(); i++) {
                JSONObject f = fs.optJSONObject(i);
                if (f != null) card.addView(text("📎 " + f.optString("name") + "  (" + (f.optLong("size") / 1024) + "KB)", 13, MUTED, false));
            }
            card.addView(text(when(o.optLong("createdAt")) + (done ? " · 보낸 시각 " + when(o.optLong("sharedAt")) : ""), 12, MUTED, false));
            card.setOnClickListener(x -> openItem(o));
            listBox.addView(card, cp);
        }
    }

    // ------------------------------------------------------------------ detail + send

    private void openItem(JSONObject o) {
        LinearLayout v = new LinearLayout(this);
        v.setOrientation(LinearLayout.VERTICAL);
        v.setPadding(dp(20), dp(8), dp(20), 0);
        String to = o.optString("to", "");
        if (!to.isEmpty()) v.addView(text("받을 곳: " + to, 14, INK, true));
        v.addView(text("보낼 문구 (고칠 수 있어요)", 13, MUTED, false));
        EditText msg = input("", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        msg.setText(o.optString("text", ""));
        msg.setMinLines(5);
        msg.setGravity(Gravity.TOP | Gravity.START);
        v.addView(msg);
        JSONArray fs = files(o);
        StringBuilder fl = new StringBuilder();
        for (int i = 0; i < fs.length(); i++) { JSONObject f = fs.optJSONObject(i); if (f != null) fl.append("📎 ").append(f.optString("name")).append("\n"); }
        TextView ft = text(fs.length() == 0 ? "첨부 파일 없음" : fl.toString().trim(), 14, INK, false);
        ft.setPadding(0, dp(10), 0, 0);
        v.addView(ft);
        ScrollView sc = new ScrollView(this);
        sc.addView(v);
        new AlertDialog.Builder(this)
                .setTitle(o.optString("title", "공지"))
                .setView(sc)
                .setPositiveButton("카톡으로 보내기", (d, w) -> confirmSend(o, msg.getText().toString().trim()))
                .setNeutralButton("목록에서 지우기", (d, w) -> new AlertDialog.Builder(this)
                        .setMessage("이 항목을 보낼 함에서 지울까요?")
                        .setPositiveButton("지우기", (d2, w2) -> setStatus(o.optString("_id"), "deleted"))
                        .setNegativeButton("취소", null).show())
                .setNegativeButton("닫기", null)
                .show();
    }

    /** 최종 확인: 선생님이 이 창에서 [보내기]를 눌러야만 카카오톡이 열린다. */
    private void confirmSend(JSONObject o, String text) {
        JSONArray fs = files(o);
        if (text.isEmpty() && fs.length() == 0) { toast("보낼 문구나 파일이 없어요"); return; }
        String summary = "문구 " + (text.isEmpty() ? "없음" : text.length() + "자") + " · 파일 " + fs.length() + "개를 카카오톡으로 넘겨요.\n\n"
                + "카톡이 열리면 받을 방(단톡방·학부모)을 직접 고르고 전송을 눌러야 보내져요."
                + (o.optString("to").isEmpty() ? "" : "\n\n받을 곳: " + o.optString("to"));
        new AlertDialog.Builder(this)
                .setTitle("정말 보낼까요?")
                .setMessage(summary)
                .setPositiveButton("보내기", (d, w) -> prepareAndShare(o, text, fs))
                .setNegativeButton("취소", null)
                .show();
    }

    @SuppressWarnings("deprecation")
    private void prepareAndShare(JSONObject o, String text, JSONArray fs) {
        ProgressDialog pd = new ProgressDialog(this);
        pd.setMessage(fs.length() == 0 ? "준비 중…" : "파일 받는 중…");
        pd.setCancelable(false);
        pd.show();
        io.execute(() -> {
            try {
                ArrayList<Uri> uris = new ArrayList<>();
                File dir = new File(getCacheDir(), "outbox");
                if (!dir.exists()) dir.mkdirs();
                for (int i = 0; i < fs.length(); i++) {
                    JSONObject f = fs.getJSONObject(i);
                    String safe = f.optString("name", "file").replaceAll("[\\\\/:*?\"<>|]", "_");
                    File out = new File(dir, safe);
                    if (f.has("blob")) {
                        ui.post(() -> pd.setMessage("파일 받는 중… " + f.optString("name")));
                        byte[] key = Base64.decode(o.getString("_key"), Base64.NO_WRAP);
                        byte[] plain = aesOpen(key, f.getString("iv"), getBytes(GH_RAW + f.getString("blob")));
                        try (FileOutputStream fo = new FileOutputStream(out)) { fo.write(plain); }
                        if (f.optLong("size") > 0 && out.length() != f.optLong("size")) throw new Exception(f.optString("name") + " 파일이 다 받아지지 않았어요");
                        uris.add(FileProvider.getUriForFile(this, "kr.suin.kakao.files", out));
                        continue;
                    }
                    int chunks = f.optInt("chunks", 1);
                    try (FileOutputStream fo = new FileOutputStream(out)) {
                        for (int k = 0; k < chunks; k++) {
                            final int kk = k;
                            ui.post(() -> pd.setMessage("파일 받는 중… " + f.optString("name") + " (" + (kk + 1) + "/" + chunks + ")"));
                            String s = db("GET", "kakaoFiles/" + f.getString("id") + "/" + k, null).trim();
                            if (s.startsWith("\"")) s = new JSONArray("[" + s + "]").getString(0);
                            fo.write(Base64.decode(s, Base64.DEFAULT));
                        }
                    }
                    if (f.optLong("size") > 0 && out.length() != f.optLong("size")) throw new Exception(f.optString("name") + " 파일이 다 받아지지 않았어요");
                    uris.add(FileProvider.getUriForFile(this, "kr.suin.kakao.files", out));
                }
                ui.post(() -> { pd.dismiss(); share(o, text, fs, uris); });
            } catch (Exception e) {
                ui.post(() -> { pd.dismiss(); toast("준비하지 못했어요: " + e.getMessage()); });
            }
        });
    }

    private void share(JSONObject o, String text, JSONArray fs, ArrayList<Uri> uris) {
        if (!text.isEmpty()) {
            ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("공지", text));
        }
        Intent i;
        if (uris.isEmpty()) {
            i = new Intent(Intent.ACTION_SEND);
            i.setType("text/plain");
        } else if (uris.size() == 1) {
            i = new Intent(Intent.ACTION_SEND);
            String mime = fs.optJSONObject(0) != null ? fs.optJSONObject(0).optString("mime", "*/*") : "*/*";
            i.setType(mime.isEmpty() ? "*/*" : mime);
            i.putExtra(Intent.EXTRA_STREAM, uris.get(0));
            i.setClipData(ClipData.newRawUri("file", uris.get(0)));
        } else {
            i = new Intent(Intent.ACTION_SEND_MULTIPLE);
            i.setType("*/*");
            i.putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris);
            ClipData cd = ClipData.newRawUri("file", uris.get(0));
            for (int k = 1; k < uris.size(); k++) cd.addItem(new ClipData.Item(uris.get(k)));
            i.setClipData(cd);
        }
        if (!text.isEmpty()) i.putExtra(Intent.EXTRA_TEXT, text);
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        i.setPackage(KAKAO_PKG);
        try {
            startActivity(i);
        } catch (Exception e) {
            toast("카카오톡을 열 수 없어요. 카카오톡이 설치돼 있는지 확인해 주세요.");
            return;
        }
        if (!uris.isEmpty() && !text.isEmpty()) toast("파일과 함께 문구가 안 보이면, 대화창을 길게 눌러 붙여넣기 하세요 (문구는 복사돼 있어요)");
        setStatus(o.optString("_id"), "shared");
    }

    private void setStatus(String id, String st) {
        if (id == null || id.isEmpty()) return;
        if (id.startsWith("gh:")) {
            Set<String> set = localSet("shared".equals(st) ? "ghSent" : "ghDeleted");
            set.add(id);
            SharedPreferences.Editor ed = sp.edit().putStringSet("shared".equals(st) ? "ghSent" : "ghDeleted", set);
            if ("shared".equals(st)) ed.putLong("sentAt:" + id, System.currentTimeMillis());
            ed.apply();
            load();
            return;
        }
        io.execute(() -> {
            try {
                JSONObject b = new JSONObject().put("status", st);
                if ("shared".equals(st)) b.put("sharedAt", System.currentTimeMillis());
                db("PATCH", "kakaoOutbox/" + id, b.toString());
                if ("deleted".equals(st)) { try { db("DELETE", "kakaoOutbox/" + id, null); } catch (Exception ignored) { } }
            } catch (Exception ignored) { }
            ui.post(this::load);
        });
    }
}
