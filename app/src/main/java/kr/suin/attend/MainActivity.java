package kr.suin.attend;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.PendingIntent;
import android.app.TimePickerDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.telephony.SmsManager;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.ServerValue;
import com.google.firebase.database.ValueEventListener;

import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * 수인T 출결: 등원/하원 버튼을 누르면 시간을 기록하고 학부모 문자를 보낸다.
 * 학생·출결·문구는 Firebase 에 저장되어 폰 앱과 노트북 웹, 조교 폰이 같은 기록을 본다.
 * 문자는 outbox 에 쌓이고, "문자 발송 담당"을 켠 선생님 폰(SmsGatewayService)이 선생님 번호로 보낸다.
 */
public class MainActivity extends Activity {

    private static final String ACTION_SENT = "kr.suin.attend.SMS_SENT";
    private static final int REQ_SMS = 7;

    private static final int GREEN = 0xFF1E7B4F;
    private static final int GREEN_SOFT = 0xFFE1F2E8;
    private static final int AMBER = 0xFFB35C00;
    private static final int AMBER_SOFT = 0xFFFBEBD8;
    private static final int INK = 0xFF16201C;
    private static final int MUTED = 0xFF5E6B66;
    private static final int LINE = 0xFFDCE3E0;
    private static final int BG = 0xFFF4F6F5;

    private static final String DEFAULT_IN = "[수인T 수학] {이름} 학생이 {시간}에 등원했습니다.";
    private static final String DEFAULT_OUT = "[수인T 수학] {이름} 학생이 {시간}에 하원했습니다. 오늘도 수고 많았어요!";

    private SharedPreferences sp;
    private final Calendar day = Calendar.getInstance();
    private LinearLayout listBox;
    private TextView dateTv;
    private TextView summaryTv;
    private LinearLayout classBar;
    private LinearLayout selBar;
    private boolean selMode = false;
    private final Set<String> selected = new HashSet<>();
    /** special filter value: students who checked in and have not left yet */
    private static final String IN_NOW = "__in";
    private Runnable pendingAfterPermission;
    private final List<JSONObject> studentCache = new ArrayList<>();
    private JSONObject dayCache = new JSONObject();
    private JSONObject settingsCache = new JSONObject();
    private String attachedDay = null;
    private DatabaseReference dayRef;
    private ValueEventListener dayListener;
    private boolean studentsLoaded = false;
    private String loadError = null;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private int requestCode = 100;

    private final BroadcastReceiver sentReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String name = intent.getStringExtra("name");
            if (getResultCode() == Activity.RESULT_OK) {
                toast(name + " 학부모에게 문자를 보냈어요");
            } else {
                toast(name + " 문자 발송 실패: 신호나 요금제를 확인해 주세요");
            }
        }
    };

    // ------------------------------------------------------------------ lifecycle

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        sp = getSharedPreferences("attend", MODE_PRIVATE);
        getWindow().setStatusBarColor(BG);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
        if (FirebaseAuth.getInstance().getCurrentUser() == null) showLogin();
        else startApp();

        IntentFilter filter = new IntentFilter(ACTION_SENT);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(sentReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(sentReceiver, filter);
        }
    }

    // ------------------------------------------------------------------ login + cloud

    private void showLogin() {
        LinearLayout v = new LinearLayout(this);
        v.setOrientation(LinearLayout.VERTICAL);
        v.setBackgroundColor(BG);
        v.setPadding(dp(24), dp(80), dp(24), dp(24));
        v.setFitsSystemWindows(true);
        v.addView(text("수인T 출결", 26, INK, true));
        TextView sub = text("선생님·조교 계정으로 로그인하면 폰·노트북이 같은 출결 기록을 봐요.", 14, MUTED, false);
        sub.setPadding(0, dp(6), 0, dp(24));
        v.addView(sub);
        EditText email = input("이메일", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS);
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
            FirebaseAuth.getInstance().signInWithEmailAndPassword(e, p)
                    .addOnSuccessListener(r -> startApp())
                    .addOnFailureListener(err -> { go.setEnabled(true); toast("로그인하지 못했어요. 이메일·비밀번호를 확인해 주세요."); });
        });
        setContentView(v);
    }

    private void startApp() {
        setContentView(buildUi());
        render();
        Cloud.root().child("students").addValueEventListener(new ValueEventListener() {
            @Override
            public void onDataChange(DataSnapshot snap) {
                studentCache.clear();
                for (DataSnapshot c : snap.getChildren()) {
                    JSONObject o = Cloud.toJson(c.getValue());
                    try { o.put("id", c.getKey()); } catch (JSONException ignored) { }
                    studentCache.add(o);
                }
                boolean first = !studentsLoaded;
                studentsLoaded = true;
                loadError = null;
                if (first && studentCache.isEmpty()) migrateLocalData(false);
                render();
            }
            @Override public void onCancelled(DatabaseError e) {
                loadError = "공용 저장소를 읽을 권한이 없어요.\nFirebase → Realtime Database → 규칙에 안내받은 규칙을 붙여넣고 '게시'했는지 확인해 주세요.\n(" + e.getMessage() + ")";
                render();
            }
        });
        Cloud.root().child("settings").addValueEventListener(new ValueEventListener() {
            @Override public void onDataChange(DataSnapshot snap) { settingsCache = Cloud.toJson(snap.getValue()); }
            @Override public void onCancelled(DatabaseError e) { }
        });
        if (sp.getBoolean("gateway", false)) SmsGatewayService.start(this);
        else if (!sp.getBoolean("gatewayAsked", false)) askGateway();
    }

    private void attachDay() {
        String key = dayKey(day);
        if (key.equals(attachedDay)) return;
        if (dayRef != null && dayListener != null) dayRef.removeEventListener(dayListener);
        attachedDay = key;
        dayCache = new JSONObject();
        dayRef = Cloud.root().child("days").child(key);
        dayListener = new ValueEventListener() {
            @Override public void onDataChange(DataSnapshot snap) { dayCache = Cloud.toJson(snap.getValue()); render(); }
            @Override public void onCancelled(DatabaseError e) { }
        };
        dayRef.addValueEventListener(dayListener);
    }

    /** 이 폰에만 있던 학생·출결 기록(예전 버전)을 Firebase 로 올린다. 이미 있는 학생·기록은 건드리지 않는다. */
    private void migrateLocalData(boolean force) {
        if (!force && sp.getBoolean("migratedOk", false)) return;
        try {
            JSONArray a = new JSONArray(sp.getString("students", "[]"));
            if (a.length() == 0) { if (force) toast("이 폰에 남아 있는 예전 기록이 없어요"); return; }
            Set<String> have = new HashSet<>();
            for (JSONObject c : studentCache) have.add(c.optString("id"));
            Map<String, Object> up = new HashMap<>();
            int n = 0;
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                String id = o.optString("id");
                if (id.isEmpty() || have.contains(id)) continue;
                JSONObject copy = new JSONObject(o.toString());
                copy.remove("id");
                up.put("students/" + id, Cloud.toFirebase(copy));
                n++;
            }
            for (String k : sp.getAll().keySet()) {
                if (!k.startsWith("rec_")) continue;
                JSONObject rec = new JSONObject(sp.getString(k, "{}"));
                Iterator<String> it = rec.keys();
                while (it.hasNext()) {
                    String sid = it.next();
                    JSONObject e = rec.optJSONObject(sid);
                    if (e == null) continue;
                    Iterator<String> kt = e.keys();
                    while (kt.hasNext()) {
                        String kind = kt.next();
                        // per-field paths: an existing cloud time for the same student/day is only replaced by the phone's own record
                        up.put("days/" + k.substring(4) + "/" + sid + "/" + kind, e.optString(kind));
                    }
                }
            }
            if (sp.contains("tplIn")) up.put("settings/tplIn", sp.getString("tplIn", DEFAULT_IN));
            if (sp.contains("tplOut")) up.put("settings/tplOut", sp.getString("tplOut", DEFAULT_OUT));
            final int count = n;
            Cloud.root().updateChildren(up)
                    .addOnSuccessListener(r -> {
                        sp.edit().putBoolean("migratedOk", true).apply();
                        toast("이 폰에 있던 학생 " + count + "명과 출결 기록을 올렸어요");
                    })
                    .addOnFailureListener(err -> toast("예전 기록을 올리지 못했어요. Firebase 규칙(게시)을 확인해 주세요."));
        } catch (JSONException ignored) { }
    }

    private void askGateway() {
        new AlertDialog.Builder(this)
                .setTitle("이 폰에서 학부모 문자를 보낼까요?")
                .setMessage("선생님 번호(유심)가 들어 있는 폰만 '예'를 누르세요.\n노트북·조교 폰에서 체크해도 이 폰이 대신 문자를 보내요. 이 폰은 켜져 있고 인터넷이 연결돼 있어야 해요.")
                .setPositiveButton("예, 이 폰이 보내요", (d, w) -> { sp.edit().putBoolean("gatewayAsked", true).apply(); enableGateway(); })
                .setNegativeButton("아니요", (d, w) -> sp.edit().putBoolean("gatewayAsked", true).apply())
                .show();
    }

    private void enableGateway() {
        List<String> need = new ArrayList<>();
        if (checkSelfPermission(Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED) need.add(Manifest.permission.SEND_SMS);
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) need.add(Manifest.permission.POST_NOTIFICATIONS);
        if (!need.isEmpty()) {
            pendingAfterPermission = this::enableGateway;
            requestPermissions(need.toArray(new String[0]), REQ_SMS);
            return;
        }
        sp.edit().putBoolean("gateway", true).apply();
        SmsGatewayService.start(this);
        toast("이 폰이 학부모 문자를 보내요");
        PowerManager pm = getSystemService(PowerManager.class);
        if (pm != null && !pm.isIgnoringBatteryOptimizations(getPackageName())) {
            try {
                startActivity(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + getPackageName())));
            } catch (Exception ignored) { }
        }
    }

    private void disableGateway() {
        sp.edit().putBoolean("gateway", false).apply();
        SmsGatewayService.stop(this);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (listBox != null) render();
    }

    @Override
    protected void onDestroy() {
        try { unregisterReceiver(sentReceiver); } catch (Exception ignored) { }
        super.onDestroy();
    }

    // ------------------------------------------------------------------ UI

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private GradientDrawable box(int fill, int stroke, int radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill);
        g.setCornerRadius(dp(radiusDp));
        if (stroke != 0) g.setStroke(dp(1), stroke);
        return g;
    }

    private TextView text(String s, int sizeSp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sizeSp);
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
        b.setMinWidth(dp(88));
        b.setStateListAnimator(null);
        return b;
    }

    private View buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);
        root.setPadding(dp(16), dp(12), dp(16), 0);
        root.setFitsSystemWindows(true);

        // top bar
        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = text("수인T 출결", 20, INK, true);
        top.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        Button studentsBtn = button("학생", Color.WHITE, INK);
        studentsBtn.setOnClickListener(v -> showStudents());
        Button tplBtn = button("문구", Color.WHITE, INK);
        tplBtn.setOnClickListener(v -> showTemplates());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(44));
        lp.leftMargin = dp(8);
        studentsBtn.setMinWidth(dp(64));
        tplBtn.setMinWidth(dp(64));
        top.addView(studentsBtn, lp);
        top.addView(tplBtn, lp);
        root.addView(top);

        // date bar
        LinearLayout dateBar = new LinearLayout(this);
        dateBar.setGravity(Gravity.CENTER_VERTICAL);
        dateBar.setPadding(0, dp(14), 0, dp(4));
        Button prev = button("‹", Color.WHITE, INK);
        prev.setMinWidth(dp(48));
        prev.setOnClickListener(v -> { day.add(Calendar.DAY_OF_MONTH, -1); render(); });
        Button next = button("›", Color.WHITE, INK);
        next.setMinWidth(dp(48));
        next.setOnClickListener(v -> { day.add(Calendar.DAY_OF_MONTH, 1); render(); });
        dateTv = text("", 19, INK, true);
        dateTv.setGravity(Gravity.CENTER);
        dateTv.setOnClickListener(v -> { Calendar now = Calendar.getInstance(); day.setTimeInMillis(now.getTimeInMillis()); render(); });
        dateBar.addView(prev, new LinearLayout.LayoutParams(dp(52), dp(44)));
        dateBar.addView(dateTv, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        dateBar.addView(next, new LinearLayout.LayoutParams(dp(52), dp(44)));
        root.addView(dateBar);

        summaryTv = text("", 13, MUTED, false);
        summaryTv.setGravity(Gravity.CENTER);
        summaryTv.setPadding(0, 0, 0, dp(10));
        root.addView(summaryTv);

        HorizontalScrollView hs = new HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        classBar = new LinearLayout(this);
        classBar.setPadding(0, 0, 0, dp(10));
        hs.addView(classBar);
        root.addView(hs);

        selBar = new LinearLayout(this);
        selBar.setGravity(Gravity.CENTER_VERTICAL);
        selBar.setPadding(0, 0, 0, dp(10));
        root.addView(selBar);

        ScrollView scroll = new ScrollView(this);
        listBox = new LinearLayout(this);
        listBox.setOrientation(LinearLayout.VERTICAL);
        listBox.setPadding(0, 0, 0, dp(24));
        scroll.addView(listBox);
        root.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        return root;
    }

    // ------------------------------------------------------------------ data

    private String dayKey(Calendar c) {
        return new SimpleDateFormat("yyyy-MM-dd", Locale.KOREA).format(c.getTime());
    }

    private boolean isToday() {
        return dayKey(day).equals(dayKey(Calendar.getInstance()));
    }

    private List<JSONObject> students() {
        List<JSONObject> out = new ArrayList<>();
        for (JSONObject o : studentCache) {
            try { out.add(new JSONObject(o.toString())); } catch (JSONException ignored) { }
        }
        Collections.sort(out, (x, y) -> {
            int c = classKey(x).compareTo(classKey(y));
            return c != 0 ? c : x.optString("name").compareTo(y.optString("name"));
        });
        return out;
    }

    /** 바뀐 학생만 Firebase 에 쓰고, 목록에서 빠진 학생은 지운다 */
    private void saveStudents(List<JSONObject> list) {
        Map<String, Object> up = new HashMap<>();
        Set<String> keep = new HashSet<>();
        for (JSONObject o : list) {
            String id = o.optString("id");
            if (id.isEmpty()) continue;
            keep.add(id);
            JSONObject old = null;
            for (JSONObject c : studentCache) if (id.equals(c.optString("id"))) old = c;
            if (old != null && old.toString().equals(o.toString())) continue;
            try {
                JSONObject copy = new JSONObject(o.toString());
                copy.remove("id");
                up.put(id, Cloud.toFirebase(copy));
            } catch (JSONException ignored) { }
        }
        for (JSONObject c : studentCache) if (!keep.contains(c.optString("id"))) up.put(c.optString("id"), null);
        if (!up.isEmpty()) Cloud.root().child("students").updateChildren(up);
    }

    private JSONObject record() {
        attachDay();
        try { return new JSONObject(dayCache.toString()); } catch (JSONException e) { return new JSONObject(); }
    }

    /** 이 날짜에서 바뀐 학생 기록만 쓴다 (다른 기기에서 동시에 체크해도 서로 덮어쓰지 않게) */
    private void saveRecord(JSONObject rec) {
        Map<String, Object> up = new HashMap<>();
        Iterator<String> it = rec.keys();
        Set<String> seen = new HashSet<>();
        while (it.hasNext()) {
            String sid = it.next();
            seen.add(sid);
            JSONObject e = rec.optJSONObject(sid);
            JSONObject old = dayCache.optJSONObject(sid);
            if (old != null && e != null && old.toString().equals(e.toString())) continue;
            up.put(sid, e == null || e.length() == 0 ? null : Cloud.toFirebase(e));
        }
        Iterator<String> ot = dayCache.keys();
        while (ot.hasNext()) { String sid = ot.next(); if (!seen.contains(sid)) up.put(sid, null); }
        dayCache = rec;
        if (!up.isEmpty()) Cloud.root().child("days").child(dayKey(day)).updateChildren(up);
    }

    private static String digits(String s) {
        return s == null ? "" : s.replaceAll("[^0-9]", "");
    }

    private static String prettyPhone(String s) {
        String d = digits(s);
        if (d.length() == 11) return d.substring(0, 3) + "-" + d.substring(3, 7) + "-" + d.substring(7);
        if (d.length() == 10) return d.substring(0, 3) + "-" + d.substring(3, 6) + "-" + d.substring(6);
        return s;
    }

    private static String classKey(JSONObject s) {
        String c = s.optString("cls", "").trim();
        return c.isEmpty() ? "\uFFFF" : c;   // students without a class go last
    }

    private List<String> classNames(List<JSONObject> list) {
        List<String> out = new ArrayList<>();
        for (JSONObject o : list) {
            String c = o.optString("cls", "").trim();
            if (!c.isEmpty() && !out.contains(c)) out.add(c);
        }
        return out;
    }

    /** "예봉중학교 2학년" */
    private static String schoolInfo(JSONObject s) {
        String school = s.optString("school", "").trim();
        String grade = s.optString("grade", "").trim();
        if (!grade.isEmpty() && grade.matches("\\d+")) grade = grade + "학년";
        return (school + " " + grade).trim();
    }

    private static boolean validPhone(String s) {
        String d = digits(s);
        if (d.startsWith("010")) return d.length() == 11;
        return d.length() >= 10 && d.length() <= 11 && d.startsWith("01");
    }

    // ------------------------------------------------------------------ main list

    private void render() {
        if (listBox == null) return;
        attachDay();
        String[] week = {"일", "월", "화", "수", "목", "금", "토"};
        dateTv.setText((day.get(Calendar.MONTH) + 1) + "월 " + day.get(Calendar.DAY_OF_MONTH) + "일 ("
                + week[day.get(Calendar.DAY_OF_WEEK) - 1] + ")" + (isToday() ? "  오늘" : ""));

        List<JSONObject> all = students();
        JSONObject rec = record();
        int nIn = 0, nOut = 0;
        listBox.removeAllViews();

        // tabs: 전체 · 지금 등원 중 · 반별
        List<String> classes = classNames(all);
        String f0 = sp.getString("filter", "");
        if (!f0.isEmpty() && !IN_NOW.equals(f0) && !classes.contains(f0)) f0 = "";
        final String filter = f0;
        final JSONObject recNow = rec;
        int inNow = 0;
        for (JSONObject o : all) if (isIn(recNow, o)) inNow++;
        classBar.removeAllViews();
        if (!all.isEmpty()) {
            addChip("전체 " + all.size(), "", filter, false);
            addChip("지금 등원 중 " + inNow, IN_NOW, filter, true);
            for (String c : classes) {
                int cnt = 0;
                for (JSONObject o : all) if (c.equals(o.optString("cls", "").trim())) cnt++;
                addChip(c + " " + cnt, c, filter, false);
            }
        }
        List<JSONObject> list = new ArrayList<>();
        for (JSONObject o : all) {
            if (IN_NOW.equals(filter)) { if (isIn(recNow, o)) list.add(o); }
            else if (filter.isEmpty() || filter.equals(o.optString("cls", "").trim())) list.add(o);
        }
        Set<String> visible = new HashSet<>();
        for (JSONObject o : list) visible.add(o.optString("id"));
        selected.retainAll(visible);
        renderSelBar(list, recNow);

        if (loadError != null) {
            summaryTv.setText("");
            TextView err = text(loadError, 15, 0xFFC0392B, false);
            err.setPadding(dp(8), dp(40), dp(8), dp(12));
            listBox.addView(err);
            return;
        }
        if (list.isEmpty() && !all.isEmpty()) {
            summaryTv.setText("");
            TextView empty = text(IN_NOW.equals(filter) ? "지금 등원 중인 학생이 없어요.\n등원하고 아직 하원하지 않은 학생이 여기 모여요." : "이 반에는 학생이 없어요.", 15, MUTED, false);
            empty.setGravity(Gravity.CENTER);
            empty.setPadding(dp(8), dp(40), dp(8), dp(12));
            listBox.addView(empty);
            return;
        }
        if (list.isEmpty()) {
            summaryTv.setText("");
            TextView empty = text("아직 등록된 학생이 없어요.\n오른쪽 위 '학생'을 눌러 이름과 학부모 번호를 넣어 주세요.", 15, MUTED, false);
            empty.setGravity(Gravity.CENTER);
            empty.setPadding(dp(8), dp(40), dp(8), dp(12));
            listBox.addView(empty);
            Button go = button("학생 추가하기", INK, Color.WHITE);
            go.setOnClickListener(v -> showStudents());
            listBox.addView(go, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)));
            return;
        }

        if (!isToday()) {
            TextView note = text("지난 날짜는 기록만 볼 수 있어요. 날짜를 누르면 오늘로 돌아가요.", 13, MUTED, false);
            note.setPadding(0, 0, 0, dp(8));
            listBox.addView(note);
        }

        String lastClass = null;
        for (JSONObject s : list) {
            String cls = s.optString("cls", "").trim();
            if ((filter.isEmpty() || IN_NOW.equals(filter)) && !classes.isEmpty() && !cls.equals(lastClass)) {
                int cnt = 0;
                for (JSONObject o : list) if (cls.equals(o.optString("cls", "").trim())) cnt++;
                TextView h = text((cls.isEmpty() ? "반 없음" : cls) + " · " + cnt + "명", 14, MUTED, true);
                h.setPadding(dp(2), dp(lastClass == null ? 0 : 10), 0, dp(6));
                listBox.addView(h);
                lastClass = cls;
            }
            final String id = s.optString("id");
            JSONObject e = rec.optJSONObject(id);
            final String tin = e == null ? "" : e.optString("in", "");
            final String tout = e == null ? "" : e.optString("out", "");
            if (!tin.isEmpty()) nIn++;
            if (!tout.isEmpty()) nOut++;

            LinearLayout row = new LinearLayout(this);
            row.setGravity(Gravity.CENTER_VERTICAL);
            int fill = !tout.isEmpty() ? AMBER_SOFT : !tin.isEmpty() ? GREEN_SOFT : Color.WHITE;
            boolean picked = selected.contains(id);
            row.setBackground(box(fill, selMode && picked ? INK : LINE, 12));
            if (selMode) {
                CheckBox cb = new CheckBox(this);
                cb.setChecked(picked);
                cb.setClickable(false);
                row.addView(cb);
                row.setOnClickListener(v -> { if (selected.contains(id)) selected.remove(id); else selected.add(id); render(); });
            }
            row.setPadding(dp(14), dp(10), dp(10), dp(10));

            LinearLayout who = new LinearLayout(this);
            who.setOrientation(LinearLayout.VERTICAL);
            who.addView(text(s.optString("name"), 17, INK, true));
            String info = schoolInfo(s);
            if (!info.isEmpty()) who.addView(text(info, 12, MUTED, false));
            LinearLayout times = new LinearLayout(this);
            if (tin.isEmpty()) times.addView(text("미등원", 13, MUTED, false));
            if (!tin.isEmpty()) {
                TextView t = text("등원 " + tin + "  ", 13, GREEN, true);
                t.setOnClickListener(v -> confirmUndo(s, "in"));
                times.addView(t);
            }
            if (!tout.isEmpty()) {
                TextView t = text("하원 " + tout, 13, AMBER, true);
                t.setOnClickListener(v -> confirmUndo(s, "out"));
                times.addView(t);
            }
            who.addView(times);
            if (selMode) who.setOnClickListener(v -> { if (selected.contains(id)) selected.remove(id); else selected.add(id); render(); });
            else who.setOnClickListener(v -> editStudent(s, this::render));
            row.addView(who, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

            Button b;
            if (tin.isEmpty()) {
                b = button("등원", GREEN, Color.WHITE);
                b.setOnClickListener(v -> check(s, "in"));
            } else if (tout.isEmpty()) {
                b = button("하원", AMBER, Color.WHITE);
                b.setOnClickListener(v -> check(s, "out"));
            } else {
                b = button("완료", LINE, MUTED);
                b.setEnabled(false);
            }
            if (!isToday()) { b.setEnabled(false); b.setAlpha(0.4f); }
            if (!selMode) row.addView(b, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(52)));

            LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            rp.bottomMargin = dp(8);
            listBox.addView(row, rp);
        }
        String fname = IN_NOW.equals(filter) ? "지금 등원 중" : filter;
        summaryTv.setText((filter.isEmpty() ? "" : "총 " + all.size() + "명 중 ") + (filter.isEmpty() ? "총 " : fname + " ") + list.size() + "명" + "   등원 " + nIn + "   하원 " + nOut + "   미등원 " + (list.size() - nIn));
    }

    private static boolean isIn(JSONObject rec, JSONObject s) {
        JSONObject e = rec.optJSONObject(s.optString("id"));
        return e != null && !e.optString("in", "").isEmpty() && e.optString("out", "").isEmpty();
    }

    private void addChip(String label, String value, String current, boolean special) {
        boolean on = value.equals(current);
        int bg = on ? (special ? GREEN : INK) : Color.WHITE;
        int fg = on ? Color.WHITE : (special ? GREEN : INK);
        Button chip = button(label, bg, fg);
        chip.setTextSize(14);
        chip.setMinWidth(dp(56));
        chip.setPadding(dp(14), dp(6), dp(14), dp(6));
        chip.setOnClickListener(v -> { sp.edit().putString("filter", value).apply(); render(); });
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(40));
        cp.rightMargin = dp(6);
        classBar.addView(chip, cp);
    }

    /** 여러 명 선택: 선택 모드 버튼, 전체 선택, 선택한 학생 한꺼번에 등원/하원 */
    private void renderSelBar(List<JSONObject> list, JSONObject rec) {
        selBar.removeAllViews();
        if (list.isEmpty() || !isToday()) { selMode = false; return; }
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(42));
        lp.rightMargin = dp(6);
        if (!selMode) {
            Button go = button("여러 명 선택", Color.WHITE, INK);
            go.setTextSize(14);
            go.setOnClickListener(v -> { selMode = true; selected.clear(); render(); });
            selBar.addView(go, lp);
            return;
        }
        List<JSONObject> canIn = new ArrayList<>(), canOut = new ArrayList<>();
        int picked = 0;
        for (JSONObject s : list) {
            if (!selected.contains(s.optString("id"))) continue;
            picked++;
            JSONObject e = rec.optJSONObject(s.optString("id"));
            String tin = e == null ? "" : e.optString("in", ""), tout = e == null ? "" : e.optString("out", "");
            if (tin.isEmpty()) canIn.add(s);
            else if (tout.isEmpty()) canOut.add(s);
        }
        final boolean allOn = picked == list.size();
        final int pickedN = picked;
        Button all = button(allOn ? "전체 해제" : "전체 선택", Color.WHITE, INK);
        all.setTextSize(14);
        all.setMinWidth(0);
        all.setOnClickListener(v -> {
            if (allOn) selected.clear(); else for (JSONObject s : list) selected.add(s.optString("id"));
            render();
        });
        Button bin = button("등원 " + canIn.size(), GREEN, Color.WHITE);
        bin.setTextSize(14); bin.setMinWidth(0);
        bin.setEnabled(!canIn.isEmpty()); if (canIn.isEmpty()) bin.setAlpha(0.4f);
        bin.setOnClickListener(v -> bulkCheck("in", canIn, pickedN));
        Button bout = button("하원 " + canOut.size(), AMBER, Color.WHITE);
        bout.setTextSize(14); bout.setMinWidth(0);
        bout.setEnabled(!canOut.isEmpty()); if (canOut.isEmpty()) bout.setAlpha(0.4f);
        bout.setOnClickListener(v -> bulkCheck("out", canOut, pickedN));
        Button cancel = button("취소", Color.WHITE, INK);
        cancel.setTextSize(14); cancel.setMinWidth(0);
        cancel.setOnClickListener(v -> { selMode = false; selected.clear(); render(); });
        selBar.addView(all, lp);
        selBar.addView(bin, lp);
        selBar.addView(bout, lp);
        selBar.addView(cancel, lp);
    }

    private void bulkCheck(String kind, List<JSONObject> targets, int pickedCount) {
        String label = "in".equals(kind) ? "등원" : "하원";
        pickTime(targets.size() + "명 " + label + " 시간", now(), time -> {
            StringBuilder names = new StringBuilder();
            for (JSONObject s : targets) names.append(names.length() == 0 ? "" : ", ").append(s.optString("name"));
            int skipped = pickedCount - targets.size();
            new AlertDialog.Builder(this)
                    .setTitle(targets.size() + "명 " + time + " " + label)
                    .setMessage(names + (skipped > 0 ? "\n\n(선택한 학생 중 " + skipped + "명은 이미 " + label + "했거나 대상이 아니라 빠졌어요)" : "")
                            + "\n\n학부모마다 각자 이름이 들어간 문자가 따로 가요.")
                    .setPositiveButton("문자 " + targets.size() + "통 보내기", (d, w) -> doBulk(kind, targets, time, true))
                    .setNeutralButton("문자 없이 기록만", (d, w) -> doBulk(kind, targets, time, false))
                    .setNegativeButton("취소", null)
                    .show();
        });
    }

    private void doBulk(String kind, List<JSONObject> targets, String time, boolean withSms) {
        JSONObject rec = record();
        try {
            for (JSONObject s : targets) {
                JSONObject e = rec.optJSONObject(s.optString("id"));
                if (e == null) e = new JSONObject();
                e.put(kind, time);
                rec.put(s.optString("id"), e);
            }
        } catch (JSONException ignored) { }
        saveRecord(rec);
        if (withSms) for (JSONObject s : targets) sendSms(s.optString("name"), s.optString("phone"), fill(s, kind, time), true);
        selMode = false;
        selected.clear();
        render();
        toast(targets.size() + "명 " + ("in".equals(kind) ? "등원" : "하원") + " " + time + " 기록" + (withSms ? " · 문자 " + targets.size() + "통 보내는 중" : ""));
    }

    /** 등원/하원 버튼: 시간을 고르는 창(기본값 = 지금)을 띄우고, 확인하면 기록 + 문자 */
    private void check(JSONObject s, String kind) {
        if (!isToday()) return;
        pickTime(s.optString("name") + " " + ("in".equals(kind) ? "등원" : "하원") + " 시간", now(), time -> {
            if (sp.getBoolean("confirm", false)) {
                new AlertDialog.Builder(this)
                        .setTitle(s.optString("name") + " " + ("in".equals(kind) ? "등원" : "하원"))
                        .setMessage(fill(s, kind, time))
                        .setPositiveButton("보내기", (d, w) -> doCheck(s, kind, time))
                        .setNegativeButton("취소", null)
                        .show();
            } else {
                doCheck(s, kind, time);
            }
        });
    }

    interface TimeChosen { void on(String hhmm); }

    private void pickTime(String title, String initial, TimeChosen done) {
        int h = 0, m = 0;
        try { h = Integer.parseInt(initial.substring(0, 2)); m = Integer.parseInt(initial.substring(3, 5)); } catch (Exception ignored) { }
        TimePickerDialog dlg = new TimePickerDialog(this, (view, hour, minute) ->
                done.on(String.format(Locale.KOREA, "%02d:%02d", hour, minute)), h, m, true);
        dlg.setTitle(title);
        dlg.show();
    }

    private void doCheck(JSONObject s, String kind, String time) {
        JSONObject rec = record();
        try {
            JSONObject e = rec.optJSONObject(s.optString("id"));
            if (e == null) e = new JSONObject();
            e.put(kind, time);
            rec.put(s.optString("id"), e);
        } catch (JSONException ignored) { }
        saveRecord(rec);
        render();
        sendSms(s.optString("name"), s.optString("phone"), fill(s, kind, time));
    }

    private String now() {
        return new SimpleDateFormat("HH:mm", Locale.KOREA).format(Calendar.getInstance().getTime());
    }

    private String fill(JSONObject s, String kind, String time) {
        String t = settingsCache.optString("in".equals(kind) ? "tplIn" : "tplOut", "");
        if (t.isEmpty()) t = "in".equals(kind) ? DEFAULT_IN : DEFAULT_OUT;
        String date = (day.get(Calendar.MONTH) + 1) + "월 " + day.get(Calendar.DAY_OF_MONTH) + "일";
        return t.replace("{이름}", s.optString("name")).replace("{시간}", time).replace("{날짜}", date);
    }

    /** 문자를 outbox 에 넣는다. 선생님 폰(발송 담당)이 받아서 보낸다. */
    private void sendSms(String name, String phone, String msg) { sendSms(name, phone, msg, false); }

    private void sendSms(String name, String phone, String msg, boolean quiet) {
        DatabaseReference ref = Cloud.root().child("outbox").push();
        Map<String, Object> m = new HashMap<>();
        m.put("to", digits(phone));
        m.put("body", msg);
        m.put("name", name);
        m.put("by", FirebaseAuth.getInstance().getCurrentUser() != null ? FirebaseAuth.getInstance().getCurrentUser().getEmail() : "");
        m.put("createdAt", ServerValue.TIMESTAMP);
        m.put("status", "pending");
        ref.setValue(m);
        if (quiet) return;
        final String who = name + "(" + prettyPhone(phone) + ")";
        final boolean[] done = {false};
        ValueEventListener w = new ValueEventListener() {
            @Override
            public void onDataChange(DataSnapshot snap) {
                Object st = snap.child("status").getValue();
                if ("sent".equals(st)) { done[0] = true; toast(who + " 문자 보냄"); ref.removeEventListener(this); }
                else if ("failed".equals(st) || "expired".equals(st)) {
                    done[0] = true;
                    toast(who + " 문자 실패: " + snap.child("error").getValue());
                    ref.removeEventListener(this);
                }
            }
            @Override public void onCancelled(DatabaseError e) { }
        };
        ref.addValueEventListener(w);
        ui.postDelayed(() -> {
            if (!done[0]) toast(who + " 문자 대기 중 · 선생님 폰이 꺼져 있거나 인터넷이 끊겼을 수 있어요");
        }, 20000);
        ui.postDelayed(() -> ref.removeEventListener(w), 120000);
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        super.onRequestPermissionsResult(code, perms, results);
        if (code != REQ_SMS) return;
        if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) {
            if (pendingAfterPermission != null) pendingAfterPermission.run();
        } else {
            toast("권한을 허용해야 이 폰에서 학부모 문자를 보낼 수 있어요");
        }
        pendingAfterPermission = null;
    }

    private void confirmUndo(JSONObject s, String kind) {
        String what = "in".equals(kind) ? "등원" : "하원";
        new AlertDialog.Builder(this)
                .setTitle(s.optString("name") + " " + what + " 기록")
                .setMessage(("in".equals(kind) ? "등원을 지우면 하원 기록도 함께 지워져요. " : "") + "이미 보낸 문자는 취소되지 않아요.")
                .setPositiveButton("기록 지우기", (d, w) -> {
                    JSONObject rec = record();
                    JSONObject e = rec.optJSONObject(s.optString("id"));
                    if (e != null) {
                        e.remove("out");
                        if ("in".equals(kind)) e.remove("in");
                        try { rec.put(s.optString("id"), e); } catch (JSONException ignored) { }
                        saveRecord(rec);
                    }
                    render();
                })
                .setNeutralButton("시간만 고치기", (d, w) -> {
                    JSONObject rec0 = record();
                    JSONObject e0 = rec0.optJSONObject(s.optString("id"));
                    String cur = e0 == null ? now() : e0.optString(kind, now());
                    pickTime(s.optString("name") + " " + what + " 시간 고치기 (문자는 안 보내요)", cur, time -> {
                        JSONObject rec = record();
                        JSONObject e = rec.optJSONObject(s.optString("id"));
                        if (e == null) e = new JSONObject();
                        try { e.put(kind, time); rec.put(s.optString("id"), e); } catch (JSONException ignored) { }
                        saveRecord(rec);
                        render();
                    });
                })
                .setNegativeButton("그대로 두기", null)
                .show();
    }

    // ------------------------------------------------------------------ students

    private EditText input(String hint, int type) {
        EditText e = new EditText(this);
        e.setHint(hint);
        e.setInputType(type);
        e.setSingleLine(type != (InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE));
        return e;
    }

    private void showStudents() {
        LinearLayout v = new LinearLayout(this);
        v.setOrientation(LinearLayout.VERTICAL);
        v.setPadding(dp(20), dp(8), dp(20), dp(8));

        EditText name = input("학생 이름", InputType.TYPE_CLASS_TEXT);
        EditText phone = input("학부모 전화번호 (문자 받는 번호)", InputType.TYPE_CLASS_PHONE);
        EditText sphone = input("학생 전화번호 (선택)", InputType.TYPE_CLASS_PHONE);
        EditText school = input("학교 (선택, 예: 예봉중학교)", InputType.TYPE_CLASS_TEXT);
        EditText grade = input("학년 (선택, 예: 2)", InputType.TYPE_CLASS_NUMBER);
        EditText clsIn = input("반 (선택, 예: 공수1반)", InputType.TYPE_CLASS_TEXT);
        v.addView(name);
        v.addView(phone);
        v.addView(sphone);
        v.addView(school);
        v.addView(grade);
        v.addView(clsIn);
        Button add = button("추가", INK, Color.WHITE);
        v.addView(add, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)));
        Button bulk = button("여러 명 한 번에 넣기", Color.WHITE, INK);
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48));
        bp.topMargin = dp(8);
        v.addView(bulk, bp);

        LinearLayout listV = new LinearLayout(this);
        listV.setOrientation(LinearLayout.VERTICAL);
        listV.setPadding(0, dp(12), 0, 0);
        v.addView(listV);

        ScrollView sc = new ScrollView(this);
        sc.addView(v);
        AlertDialog dlg = new AlertDialog.Builder(this).setTitle("학생 관리").setView(sc).setPositiveButton("닫기", null).create();

        Runnable refresh = new Runnable() {
            @Override
            public void run() {
                listV.removeAllViews();
                List<JSONObject> list = students();
                listV.addView(text("학생 " + list.size() + "명 (눌러서 수정)", 13, MUTED, false));
                for (JSONObject s : list) {
                    String info = schoolInfo(s);
                    String sp2 = s.optString("studentPhone", "");
                    String c2 = s.optString("cls", "").trim();
                    TextView t = text((c2.isEmpty() ? "" : "[" + c2 + "] ") + s.optString("name") + (info.isEmpty() ? "" : "  (" + info + ")")
                            + "\n학부모 " + prettyPhone(s.optString("phone"))
                            + (sp2.isEmpty() ? "" : "   학생 " + prettyPhone(sp2)), 15, INK, false);
                    t.setPadding(0, dp(10), 0, dp(10));
                    final Runnable self = this;
                    t.setOnClickListener(x -> editStudent(s, self));
                    listV.addView(t);
                }
            }
        };
        refresh.run();

        add.setOnClickListener(x -> {
            String n = name.getText().toString().trim();
            String p = phone.getText().toString();
            String spv = sphone.getText().toString();
            if (n.isEmpty() || !validPhone(p)) { toast("이름과 학부모 전화번호(010-0000-0000)를 확인해 주세요"); return; }
            if (!spv.trim().isEmpty() && !validPhone(spv)) { toast("학생 전화번호를 확인해 주세요"); return; }
            addStudent(n, p, spv, school.getText().toString().trim(), grade.getText().toString().trim(), clsIn.getText().toString().trim());
            name.setText(""); phone.setText(""); sphone.setText(""); school.setText(""); grade.setText("");
            name.requestFocus();
            refresh.run();
            render();
        });
        bulk.setOnClickListener(x -> showBulk(refresh));
        dlg.setOnDismissListener(d -> render());
        dlg.show();
    }

    private void addStudent(String name, String phone, String studentPhone, String school, String grade, String cls) {
        List<JSONObject> list = students();
        try {
            JSONObject o = new JSONObject();
            o.put("id", UUID.randomUUID().toString());
            o.put("name", name);
            o.put("phone", prettyPhone(phone));
            o.put("studentPhone", validPhone(studentPhone) ? prettyPhone(studentPhone) : "");
            o.put("school", school == null ? "" : school);
            o.put("grade", grade == null ? "" : grade.replace("학년", "").trim());
            o.put("cls", cls == null ? "" : cls.trim());
            list.add(o);
        } catch (JSONException ignored) { }
        saveStudents(list);
    }

    /**
     * 한 줄 = 한 명. 이름이 맨 앞, 나머지는 순서 상관없이:
     * 첫 번째 전화번호 = 학부모, 두 번째 = 학생, "~학교"/"~중"/"~고"/"~초" = 학교, 숫자 1~6 또는 "2학년" = 학년.
     * 예) 김지나 010-1234-5678 010-2345-6789 예봉중학교 3
     * 번호 없이 반 이름만 있는 줄(예: "[공수1반]" 또는 "공수1반")은 그 아래 학생들의 반이 된다.
     */
    private boolean addFromLine(String line, String cls) {
        String[] tok = line.trim().split("[\\s,\\t]+");
        if (tok.length < 2) return false;
        String name = tok[0];
        List<String> phones = new ArrayList<>();
        String school = "", grade = "";
        StringBuilder phoneBuf = new StringBuilder();
        for (int i = 1; i < tok.length; i++) {
            String t = tok[i];
            if (t.matches("[0-9\\-]+") && digits(t).length() >= 3 && !t.matches("[1-6]")) {
                phoneBuf.append(digits(t));
                if (phoneBuf.length() >= 10 && (phoneBuf.length() == 11 || !phoneBuf.toString().startsWith("010"))) {
                    phones.add(phoneBuf.toString()); phoneBuf.setLength(0);
                }
                continue;
            }
            if (phoneBuf.length() >= 10) { phones.add(phoneBuf.toString()); phoneBuf.setLength(0); }
            if (t.matches("[1-6]") || t.matches("[1-6]학년")) grade = t.replace("학년", "");
            else if (t.matches("(초|중|고)[1-6]")) { grade = t.substring(1); if (school.isEmpty()) school = t.substring(0, 1); }
            else school = school.isEmpty() ? t : school + " " + t;
        }
        if (phoneBuf.length() >= 10) phones.add(phoneBuf.toString());
        if (phones.isEmpty() || !validPhone(phones.get(0))) return false;
        addStudent(name, phones.get(0), phones.size() > 1 ? phones.get(1) : "", school, grade, cls);
        return true;
    }

    private void showBulk(Runnable after) {
        EditText e = input("[반 이름] 줄 아래에 한 줄에 한 명씩:\n[공수1반]\n이름 학부모번호 학생번호 학교 학년\n김지나 010-1234-5678 010-2345-6789 예봉중학교 3\n(반·학생번호·학교·학년은 빼도 돼요)", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        e.setMinLines(6);
        e.setGravity(Gravity.TOP);
        LinearLayout wrap = new LinearLayout(this);
        wrap.setPadding(dp(20), dp(8), dp(20), 0);
        wrap.addView(e, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        new AlertDialog.Builder(this)
                .setTitle("여러 명 넣기")
                .setView(wrap)
                .setPositiveButton("모두 추가", (d, w) -> {
                    int ok = 0, bad = 0;
                    String cls = "";
                    for (String line : e.getText().toString().split("\n")) {
                        line = line.trim();
                        if (line.isEmpty()) continue;
                        String bare = line.replaceAll("^\\[|\\]$", "").trim();
                        if (!bare.matches(".*\\d{3}.*") && !bare.contains(" ") && (line.startsWith("[") || bare.endsWith("반"))) { cls = bare; continue; }
                        if (addFromLine(line, cls)) ok++;
                        else bad++;
                    }
                    toast(ok + "명 추가했어요" + (bad > 0 ? " (형식이 맞지 않는 " + bad + "줄은 건너뜀)" : ""));
                    after.run();
                    render();
                })
                .setNegativeButton("취소", null)
                .show();
    }

    private void editStudent(JSONObject s, Runnable after) {
        LinearLayout v = new LinearLayout(this);
        v.setOrientation(LinearLayout.VERTICAL);
        v.setPadding(dp(20), dp(8), dp(20), 0);
        EditText name = input("학생 이름", InputType.TYPE_CLASS_TEXT);
        name.setText(s.optString("name"));
        EditText phone = input("학부모 전화번호 (문자 받는 번호)", InputType.TYPE_CLASS_PHONE);
        phone.setText(s.optString("phone"));
        EditText sphone = input("학생 전화번호 (선택)", InputType.TYPE_CLASS_PHONE);
        sphone.setText(s.optString("studentPhone", ""));
        EditText school = input("학교 (선택)", InputType.TYPE_CLASS_TEXT);
        school.setText(s.optString("school", ""));
        EditText grade = input("학년 (선택)", InputType.TYPE_CLASS_NUMBER);
        grade.setText(s.optString("grade", ""));
        EditText clsIn = input("반 (선택)", InputType.TYPE_CLASS_TEXT);
        clsIn.setText(s.optString("cls", ""));
        v.addView(name);
        v.addView(phone);
        v.addView(sphone);
        v.addView(school);
        v.addView(grade);
        v.addView(clsIn);
        new AlertDialog.Builder(this)
                .setTitle("학생 정보 수정")
                .setView(v)
                .setPositiveButton("저장", (d, w) -> {
                    String n = name.getText().toString().trim();
                    String p = phone.getText().toString();
                    String spv = sphone.getText().toString();
                    if (n.isEmpty() || !validPhone(p)) { toast("이름과 학부모 전화번호를 확인해 주세요"); return; }
                    if (!spv.trim().isEmpty() && !validPhone(spv)) { toast("학생 전화번호를 확인해 주세요"); return; }
                    List<JSONObject> list = students();
                    for (JSONObject o : list) {
                        if (o.optString("id").equals(s.optString("id"))) {
                            try {
                                o.put("name", n);
                                o.put("phone", prettyPhone(p));
                                o.put("studentPhone", validPhone(spv) ? prettyPhone(spv) : "");
                                o.put("school", school.getText().toString().trim());
                                o.put("grade", grade.getText().toString().replace("학년", "").trim());
                                o.put("cls", clsIn.getText().toString().trim());
                            } catch (JSONException ignored) { }
                        }
                    }
                    saveStudents(list);
                    after.run();
                    render();
                })
                .setNeutralButton("삭제", (d, w) -> new AlertDialog.Builder(this)
                        .setTitle(s.optString("name") + " 삭제")
                        .setMessage("학생 목록에서 지울까요? 지난 출결 기록은 남아 있어요.")
                        .setPositiveButton("삭제", (d2, w2) -> {
                            List<JSONObject> list = students();
                            List<JSONObject> keep = new ArrayList<>();
                            for (JSONObject o : list) if (!o.optString("id").equals(s.optString("id"))) keep.add(o);
                            saveStudents(keep);
                            after.run();
                            render();
                        })
                        .setNegativeButton("취소", null)
                        .show())
                .setNegativeButton("닫기", null)
                .show();
    }

    // ------------------------------------------------------------------ templates

    private void showTemplates() {
        LinearLayout v = new LinearLayout(this);
        v.setOrientation(LinearLayout.VERTICAL);
        v.setPadding(dp(20), dp(8), dp(20), 0);
        v.addView(text("{이름} {시간} {날짜} 자리에 실제 값이 들어가요. 한글 45자를 넘으면 문자가 여러 통으로 나뉘어 가요.", 13, MUTED, false));
        v.addView(text("등원 문구", 13, INK, true));
        EditText in = input("", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        in.setText(settingsCache.optString("tplIn", DEFAULT_IN));
        v.addView(in);
        v.addView(text("하원 문구", 13, INK, true));
        EditText out = input("", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        out.setText(settingsCache.optString("tplOut", DEFAULT_OUT));
        v.addView(out);
        CheckBox confirm = new CheckBox(this);
        confirm.setText("보내기 전에 한 번 확인하기");
        confirm.setChecked(sp.getBoolean("confirm", false));
        v.addView(confirm);
        CheckBox gw = new CheckBox(this);
        gw.setText("이 폰에서 학부모 문자 보내기 (선생님 폰만 켜기)");
        gw.setChecked(sp.getBoolean("gateway", false));
        v.addView(gw);
        String me = FirebaseAuth.getInstance().getCurrentUser() != null ? FirebaseAuth.getInstance().getCurrentUser().getEmail() : "";
        TextView acct = text("로그인: " + me, 12, MUTED, false);
        acct.setPadding(0, dp(10), 0, 0);
        v.addView(acct);
        Button reup = button("폰에 남은 예전 기록 올리기", Color.WHITE, INK);
        reup.setOnClickListener(x -> migrateLocalData(true));
        v.addView(reup, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(44)));
        Button logout = button("로그아웃", Color.WHITE, INK);
        logout.setOnClickListener(x -> {
            disableGateway();
            FirebaseAuth.getInstance().signOut();
            recreate();
        });
        v.addView(logout, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(44)));
        ScrollView sc = new ScrollView(this);
        sc.addView(v);
        new AlertDialog.Builder(this)
                .setTitle("문자 문구")
                .setView(sc)
                .setPositiveButton("저장", (d, w) -> {
                    String a = in.getText().toString().trim();
                    String b = out.getText().toString().trim();
                    Map<String, Object> t = new HashMap<>();
                    t.put("tplIn", a.isEmpty() ? DEFAULT_IN : a);
                    t.put("tplOut", b.isEmpty() ? DEFAULT_OUT : b);
                    Cloud.root().child("settings").updateChildren(t);
                    sp.edit().putBoolean("confirm", confirm.isChecked()).apply();
                    if (gw.isChecked() && !sp.getBoolean("gateway", false)) enableGateway();
                    else if (!gw.isChecked() && sp.getBoolean("gateway", false)) disableGateway();
                    toast("저장했어요");
                })
                .setNeutralButton("기본값", (d, w) -> {
                    Cloud.root().child("settings").removeValue();
                    toast("기본 문구로 되돌렸어요");
                })
                .setNegativeButton("취소", null)
                .show();
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_LONG).show();
    }
}
