import android.content.Context;
import android.net.LinkProperties;
import android.net.NetworkAgent;
import android.net.NetworkCapabilities;
import android.net.NetworkInfo;
import android.os.Looper;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.net.InetAddress;

/**
 * DefaultNetworkAgent for Android 8 to 11 (API 26 to 30), which lack the Android 12
 * NetworkScore / NetworkAgentConfig API. These releases construct an agent with a
 * NetworkInfo and an int score, and the constructor registers it with
 * ConnectivityService. Without a registered network, apps have no default network and
 * DNS fails, though eth0 itself works.
 *
 * Built against stubs of the API 26 signatures (only the constructor and unwanted()
 * are linked); everything else goes through reflection, so one class serves 26 to 30.
 * Same network as DefaultNetworkAgent: eth0 10.0.2.15/24 via 10.0.2.2.
 */
public class DefaultNetworkAgentLegacy {
    static final int TYPE_ETHERNET = 9;

    static class Agent extends NetworkAgent {
        Agent(Looper l, Context c, NetworkInfo ni, NetworkCapabilities nc, LinkProperties lp) {
            super(l, c, "WhpxEthernet", ni, nc, lp, 100);
        }

        @Override
        protected void unwanted() {}
    }

    static Object call(Object target, String name, Class<?>[] types, Object... args) throws Exception {
        Method m = target.getClass().getMethod(name, types);
        return m.invoke(target, args);
    }

    public static void main(String[] args) {
        try {
            Looper.prepareMainLooper();
            Class<?> atCls = Class.forName("android.app.ActivityThread");
            Object at = atCls.getMethod("systemMain").invoke(null);
            Context ctx = (Context) atCls.getMethod("getSystemContext").invoke(at);

            Class<?> niCls = Class.forName("android.net.NetworkInfo");
            Object ni = niCls.getConstructor(int.class, int.class, String.class, String.class)
                    .newInstance(TYPE_ETHERNET, 0, "ETHERNET", "");
            Class<?> dsCls = Class.forName("android.net.NetworkInfo$DetailedState");
            Object connected = dsCls.getField("CONNECTED").get(null);
            niCls.getMethod("setDetailedState", dsCls, String.class, String.class).invoke(ni, connected, null, null);
            niCls.getMethod("setIsAvailable", boolean.class).invoke(ni, true);

            Class<?> ncCls = Class.forName("android.net.NetworkCapabilities");
            Object nc = ncCls.getConstructor().newInstance();
            ncCls.getMethod("addTransportType", int.class).invoke(nc, 3); // TRANSPORT_ETHERNET
            // NOT_METERED, INTERNET, NOT_RESTRICTED, TRUSTED, NOT_VPN
            for (int cap : new int[] {11, 12, 13, 14, 15}) {
                try { ncCls.getMethod("addCapability", int.class).invoke(nc, cap); } catch (Throwable ignored) {}
            }
            try {
                ncCls.getMethod("setLinkUpstreamBandwidthKbps", int.class).invoke(nc, 1000000);
                ncCls.getMethod("setLinkDownstreamBandwidthKbps", int.class).invoke(nc, 1000000);
            } catch (Throwable ignored) {}

            Class<?> lpCls = Class.forName("android.net.LinkProperties");
            Object lp = lpCls.getConstructor().newInstance();
            lpCls.getMethod("setInterfaceName", String.class).invoke(lp, "eth0");
            Class<?> laCls = Class.forName("android.net.LinkAddress");
            Object la = laCls.getConstructor(InetAddress.class, int.class)
                    .newInstance(InetAddress.getByName("10.0.2.15"), 24);
            lpCls.getMethod("addLinkAddress", laCls).invoke(lp, la);
            Class<?> prefixCls = Class.forName("android.net.IpPrefix");
            Object any = prefixCls.getConstructor(InetAddress.class, int.class)
                    .newInstance(InetAddress.getByName("0.0.0.0"), 0);
            Class<?> routeCls = Class.forName("android.net.RouteInfo");
            Constructor<?> routeCtor = routeCls.getConstructor(prefixCls, InetAddress.class, String.class);
            lpCls.getMethod("addRoute", routeCls)
                    .invoke(lp, routeCtor.newInstance(any, InetAddress.getByName("10.0.2.2"), "eth0"));
            Method addDns = lpCls.getMethod("addDnsServer", InetAddress.class);
            addDns.invoke(lp, InetAddress.getByName("10.0.2.3"));
            addDns.invoke(lp, InetAddress.getByName("8.8.8.8"));

            System.out.println("Registering legacy ethernet agent...");
            final Agent agent = new Agent(Looper.myLooper(), ctx, (NetworkInfo) ni,
                    (NetworkCapabilities) nc, (LinkProperties) lp);
            // Keep it even though ConnectivityService cannot validate it (no captive portal check).
            try { call(agent, "explicitlySelected", new Class<?>[] {boolean.class}, true); } catch (Throwable ignored) {}
            System.out.println("Legacy ethernet agent registered");

            while (true) {
                try {
                    Looper.loop();
                } catch (Throwable t) {
                    t.printStackTrace();
                    try { Thread.sleep(500); } catch (Exception ignored) {}
                }
            }
        } catch (Throwable t) {
            t.printStackTrace();
        }
    }
}
