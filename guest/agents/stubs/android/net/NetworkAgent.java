package android.net;
public abstract class NetworkAgent {
    public NetworkAgent(android.os.Looper looper, android.content.Context context, String logTag,
            NetworkInfo ni, NetworkCapabilities nc, LinkProperties lp, int score) {}
    protected abstract void unwanted();
}
