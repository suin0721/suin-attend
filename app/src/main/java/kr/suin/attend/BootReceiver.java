package kr.suin.attend;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** 폰을 다시 켜면 문자 발송 담당 기능도 다시 켠다 */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context c, Intent i) {
        if (c.getSharedPreferences("attend", Context.MODE_PRIVATE).getBoolean("gateway", false)) {
            SmsGatewayService.start(c);
        }
    }
}
