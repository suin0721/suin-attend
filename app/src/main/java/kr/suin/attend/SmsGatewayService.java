package kr.suin.attend;

import android.Manifest;
import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.telephony.SmsManager;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.database.ChildEventListener;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.MutableData;
import com.google.firebase.database.Query;
import com.google.firebase.database.ServerValue;
import com.google.firebase.database.Transaction;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

/**
 * 선생님 폰에서만 켜는 "문자 발송 담당".
 * 어느 기기(노트북·조교 폰·이 폰)에서든 등원/하원을 누르면 outbox 에 문자가 쌓이고,
 * 이 서비스가 그것을 받아 이 폰의 번호로 학부모에게 보낸다.
 */
public class SmsGatewayService extends Service {
    private static final String CHANNEL = "gateway";
    private static final String ACTION_SENT = "kr.suin.attend.GATEWAY_SENT";
    private static final long MAX_AGE_MS = 12L * 3600 * 1000;

    private Query query;
    private ChildEventListener listener;
    private BroadcastReceiver receiver;
    private int requestCode = 1000;

    static void start(Context c) {
        Intent i = new Intent(c, SmsGatewayService.class);
        if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i); else c.startService(i);
    }

    static void stop(Context c) {
        c.stopService(new Intent(c, SmsGatewayService.class));
    }

    @Override
    public void onCreate() {
        super.onCreate();
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(CHANNEL, "출결 문자 발송", NotificationManager.IMPORTANCE_LOW));
        PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
        Notification n = new Notification.Builder(this, CHANNEL)
                .setContentTitle("수인T 출결")
                .setContentText("학부모 문자 발송 담당 · 대기 중")
                .setSmallIcon(R.drawable.ic_stat)
                .setContentIntent(open)
                .setOngoing(true)
                .build();
        if (Build.VERSION.SDK_INT >= 34) startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_REMOTE_MESSAGING);
        else startForeground(1, n);

        receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context c, Intent i) {
                String key = i.getStringExtra("key");
                if (key == null) return;
                if (getResultCode() == Activity.RESULT_OK) mark(key, "sent", null);
                else mark(key, "failed", "통신사 발송 실패 (코드 " + getResultCode() + ")");
            }
        };
        IntentFilter f = new IntentFilter(ACTION_SENT);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(receiver, f, Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(receiver, f);

        if (FirebaseAuth.getInstance().getCurrentUser() == null) { stopSelf(); return; }
        query = Cloud.root().child("outbox").orderByChild("status").equalTo("pending");
        listener = new ChildEventListener() {
            @Override public void onChildAdded(DataSnapshot s, String prev) { claim(s); }
            @Override public void onChildChanged(DataSnapshot s, String prev) { }
            @Override public void onChildRemoved(DataSnapshot s) { }
            @Override public void onChildMoved(DataSnapshot s, String prev) { }
            @Override public void onCancelled(DatabaseError e) { }
        };
        query.addChildEventListener(listener);
    }

    /** pending -> sending 으로 바꾼 쪽만 보낸다 (중복 발송 방지) */
    private void claim(DataSnapshot snap) {
        final String key = snap.getKey();
        snap.getRef().runTransaction(new Transaction.Handler() {
            @Override
            public Transaction.Result doTransaction(MutableData d) {
                Object st = d.child("status").getValue();
                if (st == null) return Transaction.success(d);   // not loaded yet; server will retry with real data
                if (!"pending".equals(st)) return Transaction.abort();
                d.child("status").setValue("sending");
                return Transaction.success(d);
            }

            @Override
            public void onComplete(DatabaseError err, boolean committed, DataSnapshot cur) {
                if (committed && cur != null && "sending".equals(cur.child("status").getValue())) send(key, cur);
            }
        });
    }

    private void send(String key, DataSnapshot s) {
        String to = String.valueOf(s.child("to").getValue());
        String body = String.valueOf(s.child("body").getValue());
        Object created = s.child("createdAt").getValue();
        if (created instanceof Long && System.currentTimeMillis() - (Long) created > MAX_AGE_MS) {
            mark(key, "expired", "12시간이 지나서 보내지 않았어요");
            return;
        }
        if (checkSelfPermission(Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED) {
            mark(key, "failed", "선생님 폰에 문자 권한이 꺼져 있어요");
            return;
        }
        try {
            SmsManager sm = Build.VERSION.SDK_INT >= 31 ? getSystemService(SmsManager.class) : SmsManager.getDefault();
            ArrayList<String> parts = sm.divideMessage(body);
            ArrayList<PendingIntent> sent = new ArrayList<>();
            for (int i = 0; i < parts.size(); i++) {
                if (i == 0) {
                    Intent it = new Intent(ACTION_SENT).setPackage(getPackageName()).putExtra("key", key);
                    sent.add(PendingIntent.getBroadcast(this, requestCode++, it,
                            PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT));
                } else {
                    sent.add(null);
                }
            }
            sm.sendMultipartTextMessage(to, null, parts, sent, null);
        } catch (Exception e) {
            mark(key, "failed", String.valueOf(e.getMessage()));
        }
    }

    private void mark(String key, String status, String error) {
        Map<String, Object> u = new HashMap<>();
        u.put("status", status);
        u.put("error", error);
        u.put("sentAt", ServerValue.TIMESTAMP);
        Cloud.root().child("outbox").child(key).updateChildren(u);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) { return START_STICKY; }

    @Override
    public void onDestroy() {
        if (query != null && listener != null) query.removeEventListener(listener);
        try { unregisterReceiver(receiver); } catch (Exception ignored) { }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }
}
