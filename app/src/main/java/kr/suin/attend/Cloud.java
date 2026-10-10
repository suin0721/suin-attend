package kr.suin.attend;

import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/** Firebase 접근과 JSON <-> Firebase 값 변환 */
final class Cloud {
    private Cloud() { }

    static FirebaseDatabase db() { return FirebaseDatabase.getInstance(Config.DB_URL); }

    static DatabaseReference root() { return db().getReference(); }

    static Object toFirebase(Object v) {
        if (v instanceof JSONObject) {
            JSONObject o = (JSONObject) v;
            Map<String, Object> m = new HashMap<>();
            Iterator<String> it = o.keys();
            while (it.hasNext()) {
                String k = it.next();
                m.put(k, toFirebase(o.opt(k)));
            }
            return m;
        }
        if (v == JSONObject.NULL) return null;
        return v;
    }

    static JSONObject toJson(Object v) {
        JSONObject o = new JSONObject();
        if (!(v instanceof Map)) return o;
        for (Map.Entry<?, ?> e : ((Map<?, ?>) v).entrySet()) {
            Object x = e.getValue();
            try { o.put(String.valueOf(e.getKey()), x instanceof Map ? toJson(x) : x); } catch (JSONException ignored) { }
        }
        return o;
    }
}
