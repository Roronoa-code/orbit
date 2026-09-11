package com.mani.orbit;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.graphics.BitmapFactory;
import android.os.Bundle;
import android.os.SystemClock;
import android.util.Base64;
import java.lang.reflect.Field;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONObject;

/** Separate, same-signature instrumentation APK. Reads shared media; never sends transport commands. */
public final class MusicRuntimeCheck extends Instrumentation {
    @Override public void onCreate(Bundle arguments) { super.onCreate(arguments); start(); }
    @Override public void onStart() {
        Bundle output = new Bundle();
        MusicSession[] bridge = new MusicSession[1];
        int code = Activity.RESULT_OK;
        try {
            Activity host = startActivitySync(new Intent().setClassName("com.mani.orbit", "com.mani.orbit.MainActivity").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            AtomicInteger events = new AtomicInteger();
            runOnMainSync(() -> { bridge[0] = new MusicSession(host, events::incrementAndGet); bridge[0].resume(); });
            waitForIdleSync();
            int before = events.get();
            long began = SystemClock.elapsedRealtime();
            JSONObject first = new JSONObject(bridge[0].read(""));
            long firstRead = SystemClock.elapsedRealtime() - began;
            if (!"ready".equals(first.optString("status"))) throw new AssertionError("A shared active media session is required: " + first.optString("status"));
            boolean loading = first.getBoolean("artLoading");
            while (loading && events.get() == before && SystemClock.elapsedRealtime() - began < 10000) SystemClock.sleep(20);
            if (loading && events.get() == before) throw new AssertionError("Artwork completion did not send an event");
            JSONObject ready = new JSONObject(bridge[0].read(""));
            if (ready.getBoolean("artLoading")) throw new AssertionError("Artwork still loading after completion callback");
            String art = ready.optString("art");
            if (art.isEmpty()) throw new AssertionError("Current shared artwork is required for this check");
            byte[] bytes = Base64.decode(art.substring(art.indexOf(',') + 1), Base64.DEFAULT);
            BitmapFactory.Options size = new BitmapFactory.Options(); size.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(bytes, 0, bytes.length, size);
            if (size.outWidth <= 0 || size.outHeight <= 0 || Math.max(size.outWidth, size.outHeight) > 2048) throw new AssertionError("Invalid artwork dimensions");
            Field cache = MusicSession.class.getDeclaredField("prefetched"), plan = MusicSession.class.getDeclaredField("queueKey");
            cache.setAccessible(true); plan.setAccessible(true);
            int covers;
            boolean sharedUpcoming;
            synchronized (bridge[0]) { sharedUpcoming = !"[]".equals(plan.get(bridge[0])); }
            long until = SystemClock.elapsedRealtime() + 3500;
            do { synchronized (bridge[0]) { covers = ((Map<?, ?>)cache.get(bridge[0])).size(); } if (covers > 0 || !sharedUpcoming) break; SystemClock.sleep(50); } while (SystemClock.elapsedRealtime() < until);
            if (covers > 4) throw new AssertionError("Artwork cache exceeded its bound");
            output.putString("result", new JSONObject().put("status", "PASS").put("firstReadMs", firstRead)
                .put("artReadyEvent", !loading || events.get() > before).put("artWidth", size.outWidth).put("artHeight", size.outHeight)
                .put("upcomingContentArtworkShared", sharedUpcoming).put("prefetchedCovers", covers).put("transportCommandsSent", 0).toString());
        } catch (Throwable failure) { output.putString("error", failure.toString()); code = Activity.RESULT_CANCELED; }
        finally { if (bridge[0] != null) runOnMainSync(bridge[0]::close); }
        finish(code, output);
    }
}
