import android.content.Context;
import android.os.Looper;
import android.net.NetworkAgent;
import android.net.NetworkCapabilities;
import android.net.LinkProperties;
import android.net.NetworkScore;
import android.net.NetworkAgentConfig;
import android.net.NetworkProvider;
import android.net.Network;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.net.InetAddress;

public class DefaultNetworkAgent {
    public static class CustomAgent extends NetworkAgent {
        public CustomAgent(Context c, Looper l, String tag, NetworkCapabilities nc, LinkProperties lp, NetworkScore ns, NetworkAgentConfig nac, NetworkProvider p) {
            super(c, l, tag, nc, lp, ns, nac, p);
        }

        public void configure() {
            try {
                markConnected();
                explicitlySelected(true, true);
                saveAcceptUnvalidated(true);
                sendNetworkScore(100);
            } catch (Throwable t) {
                t.printStackTrace();
            }
        }
    }

    public static void main(String[] args) {
        try {
            Looper.prepareMainLooper();
            Class<?> atCls = Class.forName("android.app.ActivityThread");
            Object at = atCls.getMethod("systemMain").invoke(null);
            Context ctx = (Context) atCls.getMethod("getSystemContext").invoke(at);

            // 1. Build NetworkCapabilities
            Class<?> ncCls = Class.forName("android.net.NetworkCapabilities");
            Object nc = ncCls.getConstructor().newInstance();
            Method addTransport = ncCls.getMethod("addTransportType", int.class);
            Method addCap = ncCls.getMethod("addCapability", int.class);

            addTransport.invoke(nc, 1); // TRANSPORT_WIFI = 1
            addTransport.invoke(nc, 3); // TRANSPORT_ETHERNET = 3

            int[] caps = {11, 12, 13, 14, 15, 16, 18, 19, 20, 21, 28};
            for (int cap : caps) {
                try {
                    addCap.invoke(nc, cap);
                } catch (Throwable ignored) {}
            }

            try {
                ncCls.getMethod("setLinkUpstreamBandwidthKbps", int.class).invoke(nc, 1000000);
                ncCls.getMethod("setLinkDownstreamBandwidthKbps", int.class).invoke(nc, 1000000);
            } catch (Throwable ignored) {}

            // 2. Build LinkProperties
            Class<?> lpCls = Class.forName("android.net.LinkProperties");
            Object lp = lpCls.getConstructor().newInstance();
            lpCls.getMethod("setInterfaceName", String.class).invoke(lp, "eth0");

            Class<?> laCls = Class.forName("android.net.LinkAddress");
            Constructor<?> laCtor = laCls.getConstructor(InetAddress.class, int.class);
            Object la = laCtor.newInstance(InetAddress.getByName("10.0.2.15"), 24);
            lpCls.getMethod("addLinkAddress", laCls).invoke(lp, la);

            Class<?> prefixCls = Class.forName("android.net.IpPrefix");
            Constructor<?> prefixCtor = prefixCls.getConstructor(InetAddress.class, int.class);
            Object defaultPrefix = prefixCtor.newInstance(InetAddress.getByName("0.0.0.0"), 0);

            Class<?> routeCls = Class.forName("android.net.RouteInfo");
            Constructor<?> routeCtor = routeCls.getConstructor(prefixCls, InetAddress.class, String.class);
            Object defaultRoute = routeCtor.newInstance(defaultPrefix, InetAddress.getByName("10.0.2.2"), "eth0");
            lpCls.getMethod("addRoute", routeCls).invoke(lp, defaultRoute);

            Method addDns = lpCls.getMethod("addDnsServer", InetAddress.class);
            addDns.invoke(lp, InetAddress.getByName("8.8.8.8"));
            addDns.invoke(lp, InetAddress.getByName("1.1.1.1"));
            addDns.invoke(lp, InetAddress.getByName("10.0.2.3"));

            // 3. Build NetworkScore
            Class<?> nsBldCls = Class.forName("android.net.NetworkScore$Builder");
            Object nsBld = nsBldCls.getConstructor().newInstance();
            try { nsBldCls.getMethod("setLegacyInt", int.class).invoke(nsBld, 100); } catch (Throwable ignored) {}
            try { nsBldCls.getMethod("setTransportPrimary", boolean.class).invoke(nsBld, true); } catch (Throwable ignored) {}
            try { nsBldCls.getMethod("setKeepConnectedReason", int.class).invoke(nsBld, 1); } catch (Throwable ignored) {}
            Object score = nsBldCls.getMethod("build").invoke(nsBld);

            // 4. Build NetworkAgentConfig
            Class<?> nacBldCls = Class.forName("android.net.NetworkAgentConfig$Builder");
            Object nacBld = nacBldCls.getConstructor().newInstance();
            try { nacBldCls.getMethod("setExplicitlySelected", boolean.class).invoke(nacBld, true); } catch (Throwable ignored) {}
            try { nacBldCls.getMethod("setUnvalidatedConnectivityAcceptable", boolean.class).invoke(nacBld, true); } catch (Throwable ignored) {}
            try { nacBldCls.getMethod("setPartialConnectivityAcceptable", boolean.class).invoke(nacBld, true); } catch (Throwable ignored) {}
            try { nacBldCls.getMethod("setLegacyType", int.class).invoke(nacBld, 1); } catch (Throwable ignored) {} // TYPE_WIFI = 1
            try { nacBldCls.getMethod("setLegacyTypeName", String.class).invoke(nacBld, "WIFI"); } catch (Throwable ignored) {}
            Object nac = nacBldCls.getMethod("build").invoke(nacBld);

            System.out.println("Instantiating CustomAgent with full capabilities...");
            final CustomAgent agent = new CustomAgent(
                ctx,
                Looper.myLooper(),
                "WifiAgent",
                (NetworkCapabilities) nc,
                (LinkProperties) lp,
                (NetworkScore) score,
                (NetworkAgentConfig) nac,
                null
            );

            System.out.println("Calling agent.register()...");
            Network net = agent.register();
            System.out.println("Registered network: " + net);

            System.out.println("Configuring agent...");
            agent.configure();
            agent.sendNetworkCapabilities((NetworkCapabilities) nc);

            // Keep-alive heartbeat thread: keeps score 100 and status connected
            Thread keepAlive = new Thread(new Runnable() {
                @Override
                public void run() {
                    while (true) {
                        try {
                            Thread.sleep(10000);
                            agent.sendNetworkScore(100);
                            agent.markConnected();
                        } catch (Throwable ignored) {}
                    }
                }
            }, "NetAgentHeartbeat");
            keepAlive.setDaemon(true);
            keepAlive.start();

            System.out.println("Configured! Waiting for network events...");

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
