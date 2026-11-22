package com.jiyu.movie;
import android.app.Activity;
import android.os.Bundle;
import android.util.Log;
import java.net.*;
import java.io.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.nio.charset.StandardCharsets;

public class ProbeActivity extends Activity {
    @Override public void onCreate(Bundle b) {
        super.onCreate(b);
        new Thread(() -> {
            try {
                for (int i=0;i<100 && !LemonVpn.connected;i++) Thread.sleep(500);
                if (!LemonVpn.connected || LemonVpn.nodes.length()<3) throw new IOException("No nodes");
                CountDownLatch latch=new CountDownLatch(1);
                LemonVpn.choose(this,"fixture-b",ok -> { if(ok) latch.countDown(); });
                if (!latch.await(20,TimeUnit.SECONDS)) throw new IOException("Selection failed");
                try (Socket s=new Socket()) {
                    s.connect(new InetSocketAddress("198.51.100.7",80),10000);
                    s.setSoTimeout(10000);
                    s.getOutputStream().write("GET /probe HTTP/1.1\r\nHost: 198.51.100.7\r\nConnection: close\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
                    BufferedReader r=new BufferedReader(new InputStreamReader(s.getInputStream()));
                    for (String line;(line=r.readLine())!=null;) {
                        if ("fixture-b-ok".equals(line)) { Log.i("LemonSmoke","PASSED"); return; }
                    }
                    throw new IOException("Unexpected proxy response");
                }
            } catch(Exception e) { Log.e("LemonSmoke","FAILED",e); }
        }).start();
    }
}
