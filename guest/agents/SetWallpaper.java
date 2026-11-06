import java.io.FileInputStream;
import java.io.InputStream;
import java.lang.reflect.Method;

public class SetWallpaper {
    public static void main(String[] args) {
        try {
            Class<?> looperClass = Class.forName("android.os.Looper");
            Method prepareMain = looperClass.getMethod("prepareMainLooper");
            prepareMain.invoke(null);

            Class<?> atClass = Class.forName("android.app.ActivityThread");
            Method systemMain = atClass.getMethod("systemMain");
            Object at = systemMain.invoke(null);
            Method getSystemContext = atClass.getMethod("getSystemContext");
            Object context = getSystemContext.invoke(at);

            Class<?> wmClass = Class.forName("android.app.WallpaperManager");
            Method getInstance = wmClass.getMethod("getInstance", Class.forName("android.content.Context"));
            Object wm = getInstance.invoke(null, context);

            if (args.length > 0) {
                FileInputStream fis = new FileInputStream(args[0]);
                Method setStream = wmClass.getMethod("setStream", InputStream.class);
                setStream.invoke(wm, fis);
                fis.close();
                System.out.println("WALLPAPER_SET_SUCCESS");
            } else {
                Method clear = wmClass.getMethod("clear");
                clear.invoke(wm);
                System.out.println("WALLPAPER_CLEARED_SUCCESS");
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
