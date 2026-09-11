import android.content.Context;
import android.graphics.Bitmap;
import android.media.MediaDescription;
import android.media.session.MediaController;
import android.media.session.MediaSession;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.net.Uri;
import android.os.Looper;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

/** Read-only shell diagnostic; never bundled in Orbit. Does not send playback commands. */
public final class MediaQueueProbe {
    public static void main(String[] args) throws Exception {
        Looper.prepareMainLooper();
        Class<?> thread = Class.forName("android.app.ActivityThread");
        Object instance = thread.getMethod("systemMain").invoke(null);
        Context system = (Context) thread.getMethod("getSystemContext").invoke(instance);
        Context shell = system.createPackageContext("com.android.shell", 0);
        MediaSessionManager manager = shell.getSystemService(MediaSessionManager.class);
        JSONArray sessions = new JSONArray();
        for (MediaController controller : manager.getActiveSessions(null)) {
            if (!"com.music.bitchord".equals(controller.getPackageName())) continue;
            List<MediaSession.QueueItem> queue = controller.getQueue();
            PlaybackState state = controller.getPlaybackState();
            long active = state == null ? -1 : state.getActiveQueueItemId();
            JSONObject session = new JSONObject().put("package",controller.getPackageName()).put("activeQueueId",active).put("queueSize",queue == null ? 0 : queue.size());
            int index = -1;
            if (queue != null) for (int i=0; i<queue.size(); i++) if (queue.get(i).getQueueId()==active) index=i;
            JSONArray entries = new JSONArray();
            if (index>=0) for (int i=index; i<Math.min(queue.size(), index+4); i++) {
                MediaDescription description = queue.get(i).getDescription();
                Uri uri = description.getIconUri();
                Bitmap bitmap = description.getIconBitmap();
                entries.put(new JSONObject().put("offset",i-index).put("queueId",queue.get(i).getQueueId())
                    .put("hasMediaId",description.getMediaId()!=null).put("hasBitmap",bitmap!=null)
                    .put("bitmapWidth",bitmap==null?0:bitmap.getWidth()).put("bitmapHeight",bitmap==null?0:bitmap.getHeight())
                    .put("artScheme",uri==null?"":uri.getScheme()).put("artAuthority",uri==null?"":uri.getAuthority()));
            }
            session.put("upcoming",entries);sessions.put(session);
        }
        System.out.println(sessions.toString(2));
        System.exit(0);
    }
}
