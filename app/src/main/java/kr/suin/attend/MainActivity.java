package kr.suin.attend;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.PendingIntent;
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
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * 수인T 출결: 등원/하원 버튼을 누르면 시간을 기록하고 학부모에게 이 폰의 번호로 문자를 바로 보낸다.
 * 데이터는 모두 이 폰 안(SharedPreferences)에만 저장된다.
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
    private Runnable pendingAfterPermission;
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
        setContentView(buildUi());

        IntentFilter filter = new IntentFilter(ACTION_SENT);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(sentReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(sentReceiver, filter);
        }
        render();
    }

    @Override
    protected void onResume() {
        super.onResume();
        render();
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
        try {
            JSONArray a = new JSONArray(sp.getString("students", "[]"));
            for (int i = 0; i < a.length(); i++) out.add(a.getJSONObject(i));
        } catch (JSONException ignored) { }
        Collections.sort(out, (x, y) -> x.optString("name").compareTo(y.optString("name")));
        return out;
    }

    private void saveStudents(List<JSONObject> list) {
        JSONArray a = new JSONArray();
        for (JSONObject o : list) a.put(o);
        sp.edit().putString("students", a.toString()).apply();
    }

    private JSONObject record() {
        try {
            return new JSONObject(sp.getString("rec_" + dayKey(day), "{}"));
        } catch (JSONException e) {
            return new JSONObject();
        }
    }

    private void saveRecord(JSONObject rec) {
        sp.edit().putString("rec_" + dayKey(day), rec.toString()).apply();
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

    private static boolean validPhone(String s) {
        String d = digits(s);
        return d.length() >= 10 && d.length() <= 11 && d.startsWith("0");
    }

    // ------------------------------------------------------------------ main list

    private void render() {
        if (listBox == null) return;
        String[] week = {"일", "월", "화", "수", "목", "금", "토"};
        dateTv.setText((day.get(Calendar.MONTH) + 1) + "월 " + day.get(Calendar.DAY_OF_MONTH) + "일 ("
                + week[day.get(Calendar.DAY_OF_WEEK) - 1] + ")" + (isToday() ? "  오늘" : ""));

        List<JSONObject> list = students();
        JSONObject rec = record();
        int nIn = 0, nOut = 0;
        listBox.removeAllViews();

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

        for (JSONObject s : list) {
            final String id = s.optString("id");
            JSONObject e = rec.optJSONObject(id);
            final String tin = e == null ? "" : e.optString("in", "");
            final String tout = e == null ? "" : e.optString("out", "");
            if (!tin.isEmpty()) nIn++;
            if (!tout.isEmpty()) nOut++;

            LinearLayout row = new LinearLayout(this);
            row.setGravity(Gravity.CENTER_VERTICAL);
            int fill = !tout.isEmpty() ? AMBER_SOFT : !tin.isEmpty() ? GREEN_SOFT : Color.WHITE;
            row.setBackground(box(fill, LINE, 12));
            row.setPadding(dp(14), dp(10), dp(10), dp(10));

            LinearLayout who = new LinearLayout(this);
            who.setOrientation(LinearLayout.VERTICAL);
            who.addView(text(s.optString("name"), 17, INK, true));
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
            row.addView(b, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(52)));

            LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            rp.bottomMargin = dp(8);
            listBox.addView(row, rp);
        }
        summaryTv.setText("전체 " + list.size() + "   등원 " + nIn + "   하원 " + nOut + "   미등원 " + (list.size() - nIn));
    }

    private void check(JSONObject s, String kind) {
        if (!isToday()) return;
        boolean confirm = sp.getBoolean("confirm", false);
        Runnable go = () -> doCheck(s, kind);
        if (confirm) {
            new AlertDialog.Builder(this)
                    .setTitle(s.optString("name") + " " + ("in".equals(kind) ? "등원" : "하원"))
                    .setMessage(fill(s, kind, now()))
                    .setPositiveButton("보내기", (d, w) -> go.run())
                    .setNegativeButton("취소", null)
                    .show();
        } else {
            go.run();
        }
    }

    private void doCheck(JSONObject s, String kind) {
        if (checkSelfPermission(Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED) {
            pendingAfterPermission = () -> doCheck(s, kind);
            requestPermissions(new String[]{Manifest.permission.SEND_SMS}, REQ_SMS);
            return;
        }
        String time = now();
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
        String t = sp.getString("in".equals(kind) ? "tplIn" : "tplOut", "in".equals(kind) ? DEFAULT_IN : DEFAULT_OUT);
        String date = (day.get(Calendar.MONTH) + 1) + "월 " + day.get(Calendar.DAY_OF_MONTH) + "일";
        return t.replace("{이름}", s.optString("name")).replace("{시간}", time).replace("{날짜}", date);
    }

    private void sendSms(String name, String phone, String msg) {
        try {
            SmsManager sm = Build.VERSION.SDK_INT >= 31 ? getSystemService(SmsManager.class) : SmsManager.getDefault();
            ArrayList<String> parts = sm.divideMessage(msg);
            ArrayList<PendingIntent> sent = new ArrayList<>();
            for (int i = 0; i < parts.size(); i++) {
                if (i == 0) {
                    Intent it = new Intent(ACTION_SENT).setPackage(getPackageName()).putExtra("name", name);
                    sent.add(PendingIntent.getBroadcast(this, requestCode++, it,
                            PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT));
                } else {
                    sent.add(null);
                }
            }
            sm.sendMultipartTextMessage(digits(phone), null, parts, sent, null);
        } catch (Exception e) {
            toast(name + " 문자를 보내지 못했어요: " + e.getMessage());
        }
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        super.onRequestPermissionsResult(code, perms, results);
        if (code != REQ_SMS) return;
        if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) {
            if (pendingAfterPermission != null) pendingAfterPermission.run();
        } else {
            toast("문자 권한이 있어야 학부모에게 문자를 보낼 수 있어요");
        }
        pendingAfterPermission = null;
    }

    private void confirmUndo(JSONObject s, String kind) {
        String what = "in".equals(kind) ? "등원" : "하원";
        new AlertDialog.Builder(this)
                .setTitle(s.optString("name") + " " + what + " 기록 지우기")
                .setMessage(("in".equals(kind) ? "등원을 지우면 하원 기록도 함께 지워져요. " : "") + "이미 보낸 문자는 취소되지 않아요.")
                .setPositiveButton("지우기", (d, w) -> {
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
        EditText phone = input("학부모 전화번호 (010-0000-0000)", InputType.TYPE_CLASS_PHONE);
        v.addView(name);
        v.addView(phone);
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
                    TextView t = text(s.optString("name") + "   " + prettyPhone(s.optString("phone")), 16, INK, false);
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
            if (n.isEmpty() || !validPhone(p)) { toast("이름과 전화번호(010-0000-0000)를 확인해 주세요"); return; }
            addStudent(n, p);
            name.setText(""); phone.setText("");
            name.requestFocus();
            refresh.run();
            render();
        });
        bulk.setOnClickListener(x -> showBulk(refresh));
        dlg.setOnDismissListener(d -> render());
        dlg.show();
    }

    private void addStudent(String name, String phone) {
        List<JSONObject> list = students();
        try {
            JSONObject o = new JSONObject();
            o.put("id", UUID.randomUUID().toString());
            o.put("name", name);
            o.put("phone", prettyPhone(phone));
            list.add(o);
        } catch (JSONException ignored) { }
        saveStudents(list);
    }

    private void showBulk(Runnable after) {
        EditText e = input("한 줄에 한 명씩: 이름 전화번호\n김지나 010-1234-5678", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
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
                    for (String line : e.getText().toString().split("\n")) {
                        line = line.trim();
                        if (line.isEmpty()) continue;
                        java.util.regex.Matcher m = java.util.regex.Pattern.compile("^(.+?)[\\s,\\t]+([0-9\\-\\s]{9,})$").matcher(line);
                        if (m.find() && validPhone(m.group(2))) { addStudent(m.group(1).trim(), m.group(2)); ok++; }
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
        EditText phone = input("학부모 전화번호", InputType.TYPE_CLASS_PHONE);
        phone.setText(s.optString("phone"));
        v.addView(name);
        v.addView(phone);
        new AlertDialog.Builder(this)
                .setTitle("학생 정보 수정")
                .setView(v)
                .setPositiveButton("저장", (d, w) -> {
                    String n = name.getText().toString().trim();
                    String p = phone.getText().toString();
                    if (n.isEmpty() || !validPhone(p)) { toast("이름과 전화번호를 확인해 주세요"); return; }
                    List<JSONObject> list = students();
                    for (JSONObject o : list) {
                        if (o.optString("id").equals(s.optString("id"))) {
                            try { o.put("name", n); o.put("phone", prettyPhone(p)); } catch (JSONException ignored) { }
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
        in.setText(sp.getString("tplIn", DEFAULT_IN));
        v.addView(in);
        v.addView(text("하원 문구", 13, INK, true));
        EditText out = input("", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        out.setText(sp.getString("tplOut", DEFAULT_OUT));
        v.addView(out);
        CheckBox confirm = new CheckBox(this);
        confirm.setText("보내기 전에 한 번 확인하기");
        confirm.setChecked(sp.getBoolean("confirm", false));
        v.addView(confirm);
        ScrollView sc = new ScrollView(this);
        sc.addView(v);
        new AlertDialog.Builder(this)
                .setTitle("문자 문구")
                .setView(sc)
                .setPositiveButton("저장", (d, w) -> {
                    String a = in.getText().toString().trim();
                    String b = out.getText().toString().trim();
                    sp.edit()
                            .putString("tplIn", a.isEmpty() ? DEFAULT_IN : a)
                            .putString("tplOut", b.isEmpty() ? DEFAULT_OUT : b)
                            .putBoolean("confirm", confirm.isChecked())
                            .apply();
                    toast("저장했어요");
                })
                .setNeutralButton("기본값", (d, w) -> {
                    sp.edit().remove("tplIn").remove("tplOut").apply();
                    toast("기본 문구로 되돌렸어요");
                })
                .setNegativeButton("취소", null)
                .show();
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_LONG).show();
    }
}
