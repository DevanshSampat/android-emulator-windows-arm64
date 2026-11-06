using System;
using System.Collections.Concurrent;
using System.IO;
using System.IO.MemoryMappedFiles;
using System.Linq;
using System.Net;
using System.Net.Sockets;
using System.Runtime.InteropServices;
using System.Text;
using System.Threading;

// Coalesces renderer replies into socket writes. The renderer hands replies over
// a byte at a time and cannot say how many remain, so sending each byte costs one
// TCP segment: a full-screen rcReadColorBuffer took tens of seconds and froze the
// guest UI. Bytes collect here and leave once the reply stops growing for a moment,
// which also keeps short GL round trips prompt.
class ReplyPump : Stream {
    static readonly long QuietTicks = System.Diagnostics.Stopwatch.Frequency / 25000; // 40 us
    readonly Stream outp;
    readonly byte[] buf = new byte[256 * 1024];
    int len;
    long seq;
    bool closed;

    public ReplyPump(Stream s) {
        outp = s;
        Thread t = new Thread(Run);
        t.IsBackground = true;
        t.Start();
    }

    public override void WriteByte(byte b) {
        lock (buf) {
            buf[len++] = b;
            seq++;
            if (len == buf.Length) Drain();
            else if (len == 1) Monitor.Pulse(buf);
        }
    }

    public override void Write(byte[] data, int offset, int count) {
        lock (buf) {
            Drain();
            outp.Write(data, offset, count);
        }
    }

    public override void Close() {
        lock (buf) {
            closed = true;
            Monitor.Pulse(buf);
        }
    }

    void Drain() {
        if (len == 0) return;
        int n = len;
        len = 0;
        outp.Write(buf, 0, n);
    }

    void Run() {
        try {
            while (true) {
                long s;
                lock (buf) {
                    while (len == 0 && !closed) Monitor.Wait(buf);
                    if (closed) { Drain(); return; }
                    s = seq;
                }
                long t0 = System.Diagnostics.Stopwatch.GetTimestamp();
                while (System.Diagnostics.Stopwatch.GetTimestamp() - t0 < QuietTicks) Thread.SpinWait(20);
                lock (buf) { if (seq == s) Drain(); }
            }
        } catch {
            // Peer gone; the connection's reader and writer see that themselves.
        }
    }

    public override bool CanRead { get { return false; } }
    public override bool CanSeek { get { return false; } }
    public override bool CanWrite { get { return true; } }
    public override long Length { get { throw new NotSupportedException(); } }
    public override long Position { get { throw new NotSupportedException(); } set { throw new NotSupportedException(); } }
    public override void Flush() { lock (buf) Drain(); }
    public override int Read(byte[] b, int o, int c) { throw new NotSupportedException(); }
    public override long Seek(long o, SeekOrigin r) { throw new NotSupportedException(); }
    public override void SetLength(long v) { throw new NotSupportedException(); }
}

// Streams renderer replies to the guest, replacing advertised capability tokens
// the WHPX VM cannot honour with spaces of equal length. Bytes are held back
// only while they form a prefix of a token; Flush() releases them whenever the
// renderer has nothing more to send, so a reply is never stalled.
class ReplyFilter {
    static readonly byte[][] Tokens = {
        Encoding.ASCII.GetBytes("ANDROID_EMU_virtio_gpu_native_sync"),
        // Transports that need virtio-gpu, goldfish DMA, or guest memory the
        // host can see (the guest's address-space emulation is guest-only):
        Encoding.ASCII.GetBytes("ANDROID_EMU_read_color_buffer_dma"),
        Encoding.ASCII.GetBytes("ANDROID_EMU_direct_mem"),
        Encoding.ASCII.GetBytes("ANDROID_EMU_dma_v1"),
        Encoding.ASCII.GetBytes("ANDROID_EMU_dma_v2"),
        Encoding.ASCII.GetBytes("ANDROID_EMU_has_shared_slots_host_memory_allocator"),
    };
    static readonly System.Collections.Concurrent.ConcurrentDictionary<string, bool> Logged = new System.Collections.Concurrent.ConcurrentDictionary<string, bool>();
    readonly Stream outp;
    readonly byte[] pend = new byte[96];
    int n;

    public ReplyFilter(Stream s) { outp = s; }

    // All tokens share this prefix. It is passed through unbuffered, so only bytes
    // AFTER it are ever held back. Holding on a shorter match would stall binary GL
    // replies: a lone 'A' (0x41, e.g. the last byte of float 16.0) would wait for
    // more data that never comes while the guest blocks on the reply.
    static readonly byte[] Prefix = Encoding.ASCII.GetBytes("ANDROID_EMU_");
    int matched;

    bool IsTokenTail(out bool full) {
        full = false;
        bool any = false;
        int len = Prefix.Length + n;
        foreach (byte[] t in Tokens) {
            if (len > t.Length) continue;
            bool ok = true;
            for (int i = 0; i < n && ok; i++) ok = pend[i] == t[Prefix.Length + i];
            if (!ok) continue;
            any = true;
            if (len == t.Length) { full = true; return true; }
        }
        return any;
    }

    public void Put(byte b) {
        if (matched < Prefix.Length) {
            matched = b == Prefix[matched] ? matched + 1 : (b == Prefix[0] ? 1 : 0);
            outp.WriteByte(b);
            return;
        }
        pend[n++] = b;
        bool full;
        if (!IsTokenTail(out full)) {
            // Not a blocked token: release, rescanning in case a new prefix starts here.
            byte[] held = new byte[n];
            Buffer.BlockCopy(pend, 0, held, 0, n);
            n = 0;
            matched = 0;
            foreach (byte h in held) Put(h);
        } else if (full) {
            string tok = "ANDROID_EMU_" + Encoding.ASCII.GetString(pend, 0, n);
            if (Logged.TryAdd(tok, true)) Console.WriteLine("[ReplyFilter] hid unsupported capability " + tok);
            for (int i = 0; i < n; i++) outp.WriteByte((byte)' ');
            n = 0;
            matched = 0;
        }
    }

    public void Flush() {
        if (n == 0) return;
        outp.Write(pend, 0, n);
        n = 0;
        matched = 0;
    }
}

class GfxstreamPipeServer {
    [StructLayout(LayoutKind.Sequential)]
    public struct stream_renderer_param {
        public ulong key;
        public ulong value;
    }

    [StructLayout(LayoutKind.Sequential)]
    public struct stream_renderer_resource_create_args {
        public uint handle;
        public uint target;
        public uint format;
        public uint bind;
        public uint width;
        public uint height;
        public uint depth;
        public uint array_size;
        public uint last_level;
        public uint nr_samples;
        public uint flags;
    }

    [StructLayout(LayoutKind.Sequential)]
    public struct iovec {
        public IntPtr iov_base;
        public UIntPtr iov_len;
    }

    [StructLayout(LayoutKind.Sequential)]
    public struct stream_renderer_box {
        public uint x, y, z, w, h, d;
    }

    [UnmanagedFunctionPointer(CallingConvention.Cdecl)]
    public delegate void FenceCallback(IntPtr userData, ref ulong fenceData);

    [DllImport("kernel32.dll", SetLastError = true)]
    static extern bool SetDllDirectory(string lpPathName);

    [DllImport("kernel32.dll", EntryPoint = "RtlMoveMemory")]
    static extern void CopyMemory(IntPtr dest, IntPtr src, UIntPtr count);

    [DllImport("winmm.dll")]
    static extern uint timeBeginPeriod(uint uPeriod);

    [DllImport("libgfxstream_backend.dll", CallingConvention = CallingConvention.Cdecl)]
    static extern int stream_renderer_init(stream_renderer_param[] renderer_params, ulong num_params);

    [DllImport("libgfxstream_backend.dll", CallingConvention = CallingConvention.Cdecl)]
    static extern int stream_renderer_context_create(uint ctx_id, uint nlen, string name, uint context_init);

    [DllImport("libgfxstream_backend.dll", CallingConvention = CallingConvention.Cdecl)]
    static extern void stream_renderer_context_destroy(uint ctx_id);

    [DllImport("libgfxstream_backend.dll", CallingConvention = CallingConvention.Cdecl)]
    static extern int stream_renderer_resource_create(ref stream_renderer_resource_create_args args, IntPtr iov, uint num_iovs);

    [DllImport("libgfxstream_backend.dll", CallingConvention = CallingConvention.Cdecl)]
    static extern void stream_renderer_resource_unref(uint res_handle);

    [DllImport("libgfxstream_backend.dll", CallingConvention = CallingConvention.Cdecl)]
    static extern void stream_renderer_ctx_attach_resource(int ctx_id, int res_handle);

    [DllImport("libgfxstream_backend.dll", CallingConvention = CallingConvention.Cdecl)]
    static extern void stream_renderer_ctx_detach_resource(int ctx_id, int res_handle);

    [DllImport("libgfxstream_backend.dll", CallingConvention = CallingConvention.Cdecl)]
    static extern int stream_renderer_transfer_write_iov(uint handle, uint ctx_id, int level, uint stride, uint layer_stride, ref stream_renderer_box box, ulong offset, IntPtr iov, int iovec_cnt);

    [DllImport("libgfxstream_backend.dll", CallingConvention = CallingConvention.Cdecl)]
    static extern int stream_renderer_transfer_read_iov(uint handle, uint ctx_id, uint level, uint stride, uint layer_stride, ref stream_renderer_box box, ulong offset, IntPtr iov, int iovec_cnt);

    [DllImport("libgfxstream_backend.dll", CallingConvention = CallingConvention.Cdecl)]
    static extern void stream_renderer_flush(uint res_handle);

    [DllImport("libgfxstream_backend.dll", CallingConvention = CallingConvention.Cdecl)]
    static extern void gfxstream_backend_setup_window(IntPtr native_window_handle, int window_x, int window_y, int window_width, int window_height, int fb_width, int fb_height);

    [DllImport("libgfxstream_backend.dll", CallingConvention = CallingConvention.Cdecl)]
    static extern void stream_renderer_teardown();

    static ConcurrentQueue<uint> idPool = new ConcurrentQueue<uint>(Enumerable.Range(10, 50).Select(x => (uint)x));
    static int nextId = 70;
    static volatile int activeDisplayCb = 0;
    static volatile int masterComposeCb = 0;
    // Ops are seen before the renderer runs them, and the buffers are reused in a cycle, so a
    // read that starts at once can return the frame that buffer held last time round.
    const int ComposeSettleMs = 5;
    static long lastComposeStamp = 0;
    static volatile int lastComposeTick = 0;
    static volatile bool hasSeenRealDisplayPost = false;
    static volatile int lastPostTick = 0;
    static volatile int lastDisplayPostTick = 0;
    static int frameDirtyCounter = 0;
    static int lcdWidth = 1280;
    static int lcdHeight = 2856;
    static string frameOutputPath = Path.Combine(AppDomain.CurrentDomain.BaseDirectory, "whpx_frame.dat");
    static FileStream sFrameFs = null;
    static MemoryMappedFile sFrameMmf = null;
    static MemoryMappedViewAccessor sFrameAccessor = null;
    static unsafe byte* sFramePtr = null;

    // A full-size read is the slowest step of the display path (25 ms for 1280x2856), and the
    // viewer usually shows far fewer pixels. The bridge writes the size it wants after the frame
    // slots; the GPU then scales into a buffer of that size and only that is read back.
    static unsafe bool CaptureRequest(out int reqW, out int reqH) {
        reqW = reqH = 0;
        if (sFramePtr == null) return false;
        long tail = 16L + lcdWidth * lcdHeight * 4L * 2;
        reqW = *(int*)(sFramePtr + tail);
        reqH = *(int*)(sFramePtr + tail + 4);
        return reqW >= 16 && reqH >= 16 && reqW < lcdWidth && reqH < lcdHeight;
    }

    // rcCreateColorBuffer on the capture connection, closing the buffer of the old size.
    static int CreateScaleBuffer(uint capId, IntPtr buf, IntPtr riovPtr, IntPtr rbuf, int oldCb, int w, int h) {
        int off = 0;
        if (oldCb > 0) {
            Marshal.WriteInt32(buf, 0, 10014); // rcCloseColorBuffer, no reply
            Marshal.WriteInt32(buf, 4, 12);
            Marshal.WriteInt32(buf, 8, oldCb);
            off = 12;
        }
        Marshal.WriteInt32(buf, off, 10012); // rcCreateColorBuffer
        Marshal.WriteInt32(buf, off + 4, 20);
        Marshal.WriteInt32(buf, off + 8, w);
        Marshal.WriteInt32(buf, off + 12, h);
        Marshal.WriteInt32(buf, off + 16, 0x1908); // GL_RGBA
        stream_renderer_box box = new stream_renderer_box { x = 0, y = 0, z = 0, w = (uint)(off + 20), h = 1, d = 1 };
        stream_renderer_transfer_write_iov(capId, capId, 0, 0, 0, ref box, 0, IntPtr.Zero, 0);
        return ReadReplyInt(capId, riovPtr, rbuf);
    }

    static int ReadReplyInt(uint capId, IntPtr riovPtr, IntPtr rbuf) {
        stream_renderer_box one = new stream_renderer_box { x = 0, y = 0, z = 0, w = 1, h = 1, d = 1 };
        byte[] b = new byte[4];
        for (int i = 0; i < 4; i++) {
            int retries = 0;
            while (stream_renderer_transfer_read_iov(capId, capId, 0, 0, 0, ref one, 0, riovPtr, 1) != 0) {
                if (++retries > 20000) return 0;
                Thread.SpinWait(80);
                if (retries % 100 == 0) Thread.Sleep(1);
            }
            b[i] = Marshal.ReadByte(rbuf, 0);
        }
        return BitConverter.ToInt32(b, 0);
    }

    // rcComposeWithoutPost: one full-screen layer scaled into target. It is never shown.
    // The renderer keeps the viewport at display size, so the layer rectangle is in target pixels.
    static void WriteScaleCompose(IntPtr buf, int src, int target, int w, int h) {
        int[] v = { 10060, 88, 72, 72, 2, 0, target, 1,
                    src, 2 /* DEVICE */, 0, 0, w, h };
        for (int i = 0; i < v.Length; i++) Marshal.WriteInt32(buf, i * 4, v[i]);
        float[] crop = { 0, 0, lcdWidth, lcdHeight };
        for (int i = 0; i < 4; i++) Marshal.WriteInt32(buf, 56 + i * 4, BitConverter.ToInt32(BitConverter.GetBytes(crop[i]), 0));
        Marshal.WriteInt32(buf, 72, 1); // HWC2_BLEND_MODE_NONE
        Marshal.WriteInt32(buf, 76, BitConverter.ToInt32(BitConverter.GetBytes(1f), 0)); // alpha
        Marshal.WriteInt32(buf, 80, 0);
        Marshal.WriteInt32(buf, 84, 0);
    }
    static int sFrameSeq = 0;
    static FenceCallback sFenceCallback;
    static ConcurrentDictionary<int, int[]> cbDims = new ConcurrentDictionary<int, int[]>();
    static ConcurrentDictionary<int, bool> cbPinned = new ConcurrentDictionary<int, bool>();
    static ConcurrentDictionary<int, int> winPinnedCb = new ConcurrentDictionary<int, int>();

    // Colour buffer lifetime. Host references only ever grow while a buffer is in use
    // (see KeepColorBufferAlive), so they are counted here and all dropped together when
    // the guest reports that the last process holding the buffer has closed it. A buffer
    // still on screen or composed within the grace period is not touched.
    const int CbReleaseGraceMs = 10000;
    static bool cbReleaseShadow = Environment.GetEnvironmentVariable("WHPX_CB_RELEASE") == "shadow";
    static ConcurrentDictionary<int, int> cbHostRefs = new ConcurrentDictionary<int, int>();
    static ConcurrentDictionary<int, int> cbLastUse = new ConcurrentDictionary<int, int>();
    static ConcurrentDictionary<int, bool> cbReleased = new ConcurrentDictionary<int, bool>();
    static ConcurrentQueue<int[]> cbReleaseQueue = new ConcurrentQueue<int[]>();
    static int cbReportsTotal = 0;
    static int cbReleasedTotal = 0;
    static int cbUseAfterRelease = 0;

    static void CountHostRefs(int handle, int count) {
        cbHostRefs.AddOrUpdate(handle, count, (k, v) => v + count);
    }

    static void NoteCbUse(int cb, int op) {
        if (cb <= 0 || cb >= 100000) return;
        cbLastUse[cb] = Environment.TickCount;
        if (cbReleased.ContainsKey(cb)) {
            int n = Interlocked.Increment(ref cbUseAfterRelease);
            if (n <= 50) Console.WriteLine("[CB] USE AFTER RELEASE cb=" + cb + " op=" + op + " (#" + n + ")");
        }
    }

    static void NoteBufferGone(int handle) {
        if (handle <= 0 || handle >= 100000) return;
        Interlocked.Increment(ref cbReportsTotal);
        cbReleaseQueue.Enqueue(new int[] { handle, Environment.TickCount });
    }

    static void CbReleaseLoop() {
        while (true) {
            Thread.Sleep(500);
            int[] item;
            while (cbReleaseQueue.TryPeek(out item) && unchecked(Environment.TickCount - item[1]) >= CbReleaseGraceMs) {
                if (!cbReleaseQueue.TryDequeue(out item)) break;
                int handle = item[0];
                int used;
                bool recent = cbLastUse.TryGetValue(handle, out used) && unchecked(Environment.TickCount - used) < CbReleaseGraceMs;
                if (recent || handle == activeDisplayCb || handle == masterComposeCb) {
                    cbReleaseQueue.Enqueue(new int[] { handle, Environment.TickCount });
                    continue;
                }
                try { ReleaseColorBuffer(handle); }
                catch (Exception ex) { Console.WriteLine("[CB] release failed cb=" + handle + ": " + ex.Message); }
            }
        }
    }

    static void ReleaseColorBuffer(int handle) {
        int refs;
        cbHostRefs.TryRemove(handle, out refs);
        cbReleased[handle] = true;
        if (!cbReleaseShadow) {
            bool pinned;
            int[] dims;
            int last;
            cbPinned.TryRemove(handle, out pinned);
            cbDims.TryRemove(handle, out dims);
            cbLastUse.TryRemove(handle, out last);
            // Closing a handle already gone is harmless, so err towards one too many.
            for (int left = refs + 1; left > 0; left -= 32) SendColorBufferOps(10014, handle, Math.Min(left, 32));
        }
        int done = Interlocked.Increment(ref cbReleasedTotal);
        if (done % 50 == 1) {
            Console.WriteLine("[CB] " + (cbReleaseShadow ? "shadow " : "") + "released=" + done + " reported=" + cbReportsTotal +
                " live=" + cbHostRefs.Count + " useAfterRelease=" + cbUseAfterRelease);
        }
    }
    static ConcurrentDictionary<uint, int[]> ctxPendingCreate = new ConcurrentDictionary<uint, int[]>();
    static int lastCreatedW = 0;
    static int lastCreatedH = 0;
    static readonly object gpuLock = new object();
    static IntPtr keepAliveBuf = IntPtr.Zero;
    static IntPtr keepAliveIov = IntPtr.Zero;
    static IntPtr keepAliveRbuf = IntPtr.Zero;
    static IntPtr keepAliveRiov = IntPtr.Zero;

    static long nextProcessUid = 1000;

    static void InitKeepAlivePipe() {
        uint kaId = 3;
        lock (gpuLock) {
            CreatePipeResource(kaId, 4096, out keepAliveBuf, out keepAliveIov);
            keepAliveRbuf = Marshal.AllocHGlobal(64);
            iovec riov = new iovec { iov_base = keepAliveRbuf, iov_len = (UIntPtr)64 };
            keepAliveRiov = Marshal.AllocHGlobal(Marshal.SizeOf(typeof(iovec)));
            Marshal.StructureToPtr(riov, keepAliveRiov, false);

            byte[] openMsg = Encoding.ASCII.GetBytes("pipe:opengles\0");
            Marshal.Copy(openMsg, 0, keepAliveBuf, openMsg.Length);
            stream_renderer_box obox = new stream_renderer_box { x = 0, y = 0, z = 0, w = (uint)openMsg.Length, h = 1, d = 1 };
            stream_renderer_transfer_write_iov(kaId, kaId, 0, 0, 0, ref obox, 0, IntPtr.Zero, 0);

            Marshal.WriteInt32(keepAliveBuf, 0, 0); // clientFlags = 0
            Marshal.WriteInt32(keepAliveBuf, 4, 10000); // rcGetRendererVersion
            Marshal.WriteInt32(keepAliveBuf, 8, 8);
            stream_renderer_box fbox = new stream_renderer_box { x = 0, y = 0, z = 0, w = 12, h = 1, d = 1 };
            stream_renderer_transfer_write_iov(kaId, kaId, 0, 0, 0, ref fbox, 0, IntPtr.Zero, 0);

            stream_renderer_box rbox1 = new stream_renderer_box { x = 0, y = 0, z = 0, w = 1, h = 1, d = 1 };
            for (int i = 0; i < 4; i++) {
                int retries = 0;
                while (stream_renderer_transfer_read_iov(kaId, kaId, 0, 0, 0, ref rbox1, 0, keepAliveRiov, 1) != 0) {
                    if (++retries > 2000) break;
                    Thread.SpinWait(200);
                    if (retries % 20 == 0) Thread.Sleep(1);
                }
            }
            Console.WriteLine("[KeepAlive] KeepAlive RenderThread (ctx=3) active!");
        }
    }

    static void KeepColorBufferAlive(int handle, int openCount = 0) {
        if (handle <= 0 || handle >= 100000) return;
        if (lastCreatedW > 0 && lastCreatedH > 0 && !cbDims.ContainsKey(handle)) {
            cbDims[handle] = new int[] { lastCreatedW, lastCreatedH };
        }
        if (openCount > 0) {
            CountHostRefs(handle, openCount);
            SendColorBufferOps(10013, handle, openCount);
        }
    }

    // op: OP_rcOpenColorBuffer (10013) or OP_rcCloseColorBuffer (10014), both len=12.
    static void SendColorBufferOps(int op, int handle, int count) {
        lock (gpuLock) {
            if (keepAliveBuf != IntPtr.Zero) {
                int off = 0;
                for (int i = 0; i < count; i++) {
                    Marshal.WriteInt32(keepAliveBuf, off + 0, op);
                    Marshal.WriteInt32(keepAliveBuf, off + 4, 12);
                    Marshal.WriteInt32(keepAliveBuf, off + 8, handle);
                    off += 12;
                }
                // Follow with OP_rcGetRendererVersion (10000, len=8) to flush RenderThread 3 synchronously
                Marshal.WriteInt32(keepAliveBuf, off + 0, 10000);
                Marshal.WriteInt32(keepAliveBuf, off + 4, 8);
                off += 8;

                stream_renderer_box box = new stream_renderer_box { x = 0, y = 0, z = 0, w = (uint)off, h = 1, d = 1 };
                stream_renderer_transfer_write_iov(3, 3, 0, 0, 0, ref box, 0, IntPtr.Zero, 0);

                stream_renderer_box rbox4 = new stream_renderer_box { x = 0, y = 0, z = 0, w = 4, h = 1, d = 1 };
                int retries = 0;
                while (stream_renderer_transfer_read_iov(3, 3, 0, 0, 0, ref rbox4, 0, keepAliveRiov, 1) != 0) {
                    if (++retries > 1000) break;
                    Thread.SpinWait(100);
                    if (retries % 50 == 0) Thread.Sleep(1);
                }
            }
        }
    }

    static void OnFence(IntPtr userData, ref ulong fenceData) {}

    static uint CreatePipeResource(uint id, uint bufSize, out IntPtr buf, out IntPtr iovPtr) {
        stream_renderer_context_create(id, 8, "opengles", 0);
        buf = Marshal.AllocHGlobal((int)bufSize);
        iovec iov = new iovec { iov_base = buf, iov_len = (UIntPtr)bufSize };
        iovPtr = Marshal.AllocHGlobal(Marshal.SizeOf(typeof(iovec)));
        Marshal.StructureToPtr(iov, iovPtr, false);
        stream_renderer_resource_create_args rargs = new stream_renderer_resource_create_args {
            handle = id,
            target = 0, // PIPE
            format = 1,
            bind = 0x20000, // VIRGL_BIND_CUSTOM
            width = bufSize,
            height = 1,
            depth = 1,
            array_size = 1
        };
        stream_renderer_resource_create(ref rargs, iovPtr, 1);
        stream_renderer_ctx_attach_resource((int)id, (int)id);
        return id;
    }

    static ConcurrentDictionary<string, bool> CapDiag = new ConcurrentDictionary<string, bool>();
    static void FrameCaptureLoop() {
        try {
            uint capId = 2;
            uint maxBytes = (uint)(lcdWidth * lcdHeight * 4 + 4096);
            IntPtr buf, iovPtr;
            IntPtr rbuf = Marshal.AllocHGlobal((int)maxBytes);
            iovec riov = new iovec { iov_base = rbuf, iov_len = (UIntPtr)maxBytes };
            IntPtr riovPtr = Marshal.AllocHGlobal(Marshal.SizeOf(typeof(iovec)));
            Marshal.StructureToPtr(riov, riovPtr, false);
            stream_renderer_box rbox1 = new stream_renderer_box { x = 0, y = 0, z = 0, w = 1, h = 1, d = 1 };
            stream_renderer_box rbox4 = new stream_renderer_box { x = 0, y = 0, z = 0, w = 4, h = 1, d = 1 };
            stream_renderer_box rbox64 = new stream_renderer_box { x = 0, y = 0, z = 0, w = 64, h = 1, d = 1 };
            stream_renderer_box rbox256 = new stream_renderer_box { x = 0, y = 0, z = 0, w = 256, h = 1, d = 1 };
            stream_renderer_box rboxChunk = new stream_renderer_box { x = 0, y = 0, z = 0, w = 0, h = 1, d = 1 };

            byte[] verBytes = new byte[4];
            lock (gpuLock) {
                CreatePipeResource(capId, maxBytes, out buf, out iovPtr);

                byte[] openMsg = Encoding.ASCII.GetBytes("pipe:opengles\0");
                Marshal.Copy(openMsg, 0, buf, openMsg.Length);
                stream_renderer_box obox = new stream_renderer_box { x = 0, y = 0, z = 0, w = (uint)openMsg.Length, h = 1, d = 1 };
                stream_renderer_transfer_write_iov(capId, capId, 0, 0, 0, ref obox, 0, IntPtr.Zero, 0);

                // Send clientFlags = 0 + rcGetRendererVersion (10000, len=8) in one write
                Marshal.WriteInt32(buf, 0, 0);
                Marshal.WriteInt32(buf, 4, 10000);
                Marshal.WriteInt32(buf, 8, 8);
                stream_renderer_box fbox = new stream_renderer_box { x = 0, y = 0, z = 0, w = 12, h = 1, d = 1 };
                stream_renderer_transfer_write_iov(capId, capId, 0, 0, 0, ref fbox, 0, IntPtr.Zero, 0);

                for (int i = 0; i < 4; i++) {
                    int retries = 0;
                    while (stream_renderer_transfer_read_iov(capId, capId, 0, 0, 0, ref rbox1, 0, riovPtr, 1) != 0) {
                        if (++retries > 2000) break;
                        Thread.SpinWait(200);
                        if (retries % 20 == 0) Thread.Sleep(1);
                    }
                    verBytes[i] = Marshal.ReadByte(rbuf, 0);
                }
            }
            Console.WriteLine("[Capture] FrameCaptureLoop RenderThread active! RendererVersion=" + BitConverter.ToInt32(verBytes, 0));

            int lastCapturedSeq = 0;
            int currentSlot = 0;
            int scaleCb = 0, scaleW = 0, scaleH = 0;
            int lastReqW = -1, lastReqH = -1;
            byte[] outBytes = new byte[8 + lcdWidth * lcdHeight * 4];
            byte[] rowTemp = new byte[lcdWidth * 4];

            while (true) {
                int seq = frameDirtyCounter;
                int targetCb = activeDisplayCb;
                int reqW, reqH;
                bool wantScaled = CaptureRequest(out reqW, out reqH);
                if (targetCb <= 0 || (seq == lastCapturedSeq && reqW == lastReqW && reqH == lastReqH)) {
                    Thread.Sleep(2);
                    continue;
                }
                lastCapturedSeq = seq;
                lastReqW = reqW;
                lastReqH = reqH;
                long sinceComposeMs = (System.Diagnostics.Stopwatch.GetTimestamp() - Interlocked.Read(ref lastComposeStamp)) * 1000 / System.Diagnostics.Stopwatch.Frequency;
                if (sinceComposeMs >= 0 && sinceComposeMs < ComposeSettleMs) Thread.Sleep(ComposeSettleMs - (int)sinceComposeMs);

                int[] dims;
                int w = lcdWidth;
                int h = lcdHeight;
                if (cbDims.TryGetValue(targetCb, out dims)) {
                    w = dims[0];
                    h = dims[1];
                }
                if (w != lcdWidth || h != lcdHeight) {
                    if (CapDiag.TryAdd("dims" + targetCb, true)) Console.WriteLine("[Capture] skip cb=" + targetCb + " recorded " + w + "x" + h + " != lcd " + lcdWidth + "x" + lcdHeight);
                    Thread.Sleep(2);
                    continue;
                }
                int pixBytes = w * h * 4;
                int writeSlot = 1 - currentSlot;
                int slotOffset = 16 + writeSlot * pixBytes;
                if (wantScaled) {
                    if (scaleCb <= 0 || scaleW != reqW || scaleH != reqH) {
                        scaleCb = CreateScaleBuffer(capId, buf, riovPtr, rbuf, scaleCb, reqW, reqH);
                        scaleW = reqW;
                        scaleH = reqH;
                        Console.WriteLine("[Capture] scaled capture " + reqW + "x" + reqH + " cb=" + scaleCb);
                    }
                    wantScaled = scaleCb > 0;
                }
                if (wantScaled) {
                    w = scaleW;
                    h = scaleH;
                }

                if (outBytes.Length != 8 + pixBytes) outBytes = new byte[8 + pixBytes];
                Buffer.BlockCopy(BitConverter.GetBytes(w), 0, outBytes, 0, 4);
                Buffer.BlockCopy(BitConverter.GetBytes(h), 0, outBytes, 4, 4);

                System.Diagnostics.Stopwatch sw = System.Diagnostics.Stopwatch.StartNew();
                bool ok = true;

                int sliceHeight = h; // Full frame in 1 slice
                int rowBytes = w * 4;

                for (int y = 0; y < h; y += sliceHeight) {
                    int curH = Math.Min(sliceHeight, h - y);
                    int sliceBytes = w * curH * 4;
                    int baseIdx = y * rowBytes;

                    int pre = 0;
                    if (wantScaled) {
                        WriteScaleCompose(buf, targetCb, scaleCb, scaleW, scaleH);
                        pre = 88;
                    }
                    Marshal.WriteInt32(buf, pre + 0, 10023); // OP_rcReadColorBuffer
                    Marshal.WriteInt32(buf, pre + 4, 40);
                    Marshal.WriteInt32(buf, pre + 8, wantScaled ? scaleCb : targetCb);
                    Marshal.WriteInt32(buf, pre + 12, 0);
                    Marshal.WriteInt32(buf, pre + 16, y);
                    Marshal.WriteInt32(buf, pre + 20, w);
                    Marshal.WriteInt32(buf, pre + 24, curH);
                    Marshal.WriteInt32(buf, pre + 28, 0x1908); // GL_RGBA
                    Marshal.WriteInt32(buf, pre + 32, 0x1401); // GL_UNSIGNED_BYTE
                    Marshal.WriteInt32(buf, pre + 36, sliceBytes);
                    Marshal.WriteInt32(buf, pre + 40, 10000); // OP_rcGetRendererVersion piggyback (flushes RenderThread)
                    Marshal.WriteInt32(buf, pre + 44, 8);

                    stream_renderer_box wbox = new stream_renderer_box { x = 0, y = 0, z = 0, w = (uint)(pre + 48), h = 1, d = 1 };
                    stream_renderer_transfer_write_iov(capId, capId, 0, 0, 0, ref wbox, 0, IntPtr.Zero, 0);
                    if (wantScaled) ReadReplyInt(capId, riovPtr, rbuf);

                    int totalRead = 0;
                    int needed = sliceBytes + 4;
                    int retries = 0;
                    while (totalRead < needed) {
                        int rem = needed - totalRead;
                        // A transfer of n bytes needs 4n of iov and fails only after consuming the pipe
                        // data, so small displays (frames under 8 MB) must use smaller chunks.
                        int chunk = Math.Min(Math.Min(rem, 2097152), (int)(maxBytes / 4));
                        rboxChunk.w = (uint)chunk;
                        int rrc = stream_renderer_transfer_read_iov(capId, capId, 0, 0, 0, ref rboxChunk, 0, riovPtr, 1);
                        if (rrc != 0) {
                            retries++;
                            if (retries > 300) {
                                ok = false;
                                break;
                            }
                            Thread.SpinWait(80);
                            continue;
                        }
                        retries = 0;
                        if (totalRead < sliceBytes) {
                            int copyLen = Math.Min(chunk, sliceBytes - totalRead);
                            unsafe {
                                if (sFramePtr != null) {
                                    IntPtr dst = (IntPtr)(sFramePtr + slotOffset + baseIdx + totalRead);
                                    CopyMemory(dst, rbuf, (UIntPtr)copyLen);
                                }
                            }
                        }
                        totalRead += chunk;
                    }
                    if (!ok) { if (CapDiag.TryAdd("read" + targetCb, true)) Console.WriteLine("[Capture] read stalled cb=" + targetCb + " got " + totalRead + "/" + needed); break; }
                }
                sw.Stop();

                if (ok) {
                    unsafe {
                        if (sFramePtr != null) {
                            int nextSeq = Interlocked.Increment(ref sFrameSeq);
                            *(int*)(sFramePtr + 4) = w;
                            *(int*)(sFramePtr + 8) = h;
                            *(int*)(sFramePtr + 12) = writeSlot;
                            Thread.MemoryBarrier();
                            *(int*)(sFramePtr + 0) = nextSeq;
                            currentSlot = writeSlot;
                            if (nextSeq % 60 == 1) {
                                Console.WriteLine("[GfxstreamPipeServer] MMF frame " + nextSeq + " (slot=" + writeSlot + ") targetCb=" + targetCb + " written (" + w + "x" + h + ", read=" + sw.ElapsedMilliseconds + "ms)");
                            }
                        }
                    }
                }
                int elapsedMs = (int)sw.ElapsedMilliseconds;
                if (elapsedMs < 7) Thread.Sleep(7 - elapsedMs);
            }
        } catch (Exception ex) {
            Console.WriteLine("[Capture] Error: " + ex);
        }
    }

    // glReadPixels replies are pixels too. Android copies hardware bitmaps this way with
    // the window manager lock held (a full-screen copy on app switches), so the byte path
    // stalls the whole UI for tens of seconds. GL commands share the stream unframed, so
    // only the write's last packet is checked, and its size must match its dimensions.
    static void NoteGlReadPixels(uint ctxId, byte[] buf, int len) {
        const int Pkt = 36;
        const int MinBulk = 64 * 1024;
        const int GL_RGB = 0x1907, GL_RGBA = 0x1908, GL_BGRA = 0x80E1, GL_UNSIGNED_BYTE = 0x1401;
        if (len < Pkt) return;
        int off = len - Pkt;
        int op = BitConverter.ToInt32(buf, off);
        if (op < 1024 || op > 4096 || BitConverter.ToInt32(buf, off + 4) != Pkt) return;
        int w = BitConverter.ToInt32(buf, off + 16);
        int h = BitConverter.ToInt32(buf, off + 20);
        int format = BitConverter.ToInt32(buf, off + 24);
        int type = BitConverter.ToInt32(buf, off + 28);
        int size = BitConverter.ToInt32(buf, off + 32);
        if (w <= 0 || h <= 0 || w > 16384 || h > 16384 || type != GL_UNSIGNED_BYTE) return;
        int bpp = (format == GL_RGBA || format == GL_BGRA) ? 4 : (format == GL_RGB ? 3 : 0);
        if (bpp == 0 || size < MinBulk) return;
        long tight = (long)w * h * bpp;
        long aligned = (long)(((long)w * bpp + 3) & ~3L) * h;
        if (size != tight && size != aligned) return;
        ctxPendingBulk[ctxId] = size;
        if (CapDiag.TryAdd("readpixels", true)) Console.WriteLine("[PIPE] bulk glReadPixels op=" + op + " " + w + "x" + h + " size=" + size);
    }

    static ConcurrentDictionary<int, bool> SeenOps = new ConcurrentDictionary<int, bool>();
    // ctx -> pixel bytes the renderer will return for an rcReadColorBuffer in flight.
    static ConcurrentDictionary<uint, int> ctxPendingBulk = new ConcurrentDictionary<uint, int>();

    // A read for more bytes than are coming blocks forever, so only trust a packet
    // that ends the guest's write (it must, to wait for the reply) and describes a
    // real colour buffer. Anything else takes the slower byte path, which is always safe.
    static void NoteColorBufferRead(uint ctxId, byte[] buf, int off, int len) {
        const int MinBulk = 64 * 1024;
        if (off + 40 != len) return;
        int cb = BitConverter.ToInt32(buf, off + 8);
        int w = BitConverter.ToInt32(buf, off + 20);
        int h = BitConverter.ToInt32(buf, off + 24);
        int size = BitConverter.ToInt32(buf, off + 36);
        int[] dims;
        if (!cbDims.TryGetValue(cb, out dims)) return;
        if (w <= 0 || h <= 0 || w > dims[0] || h > dims[1]) return;
        if (size < MinBulk || size > (long)w * h * 8 || size % h != 0) return;
        ctxPendingBulk[ctxId] = size;
    }

    // rcReadColorBufferYUV: [op, len, cb, x, y, w, h, __size_pixels, pixels_size]. A frame of
    // video per call, so it takes the bulk reply path like rcReadColorBuffer.
    const int RcReadColorBufferYuv = 10046;

    static void NoteYuvRead(uint ctxId, byte[] buf, int off, int len) {
        if (off + 36 != len) return;
        int cb = BitConverter.ToInt32(buf, off + 8);
        int w = BitConverter.ToInt32(buf, off + 20);
        int h = BitConverter.ToInt32(buf, off + 24);
        int size = BitConverter.ToInt32(buf, off + 32);
        int[] dims;
        if (!cbDims.TryGetValue(cb, out dims) || w <= 0 || h <= 0 || w > dims[0] || h > dims[1]) return;
        if (size < 64 * 1024 || size > (long)w * h * 4) return;
        ctxPendingBulk[ctxId] = size;
        if (CapDiag.TryAdd("yuvread", true)) Console.WriteLine("[RC ctx=" + ctxId + "] YUV read " + w + "x" + h + " size=" + size);
    }

    // rcUpdateColorBuffer: [op, len, cb, x, y, w, h, format, type, __size, pixels...]. The
    // guest's rc_update stub sends a YUV frame as `size` bytes (w=size, h=1, GL_LUMINANCE)
    // with the real dimensions in x and y. Pixels span many reads, so only the header is
    // here. It must be restored before the renderer sees it; the length stays as sent.
    static void RestoreYuvUpload(byte[] buf, int off) {
        const int GL_RGBA = 0x1908, GL_LUMINANCE = 0x1909, GL_UNSIGNED_BYTE = 0x1401;
        if (BitConverter.ToInt32(buf, off + 28) != GL_LUMINANCE) return;
        if (BitConverter.ToInt32(buf, off + 32) != GL_UNSIGNED_BYTE) return;
        if (BitConverter.ToInt32(buf, off + 24) != 1) return;
        int size = BitConverter.ToInt32(buf, off + 36);
        if (BitConverter.ToInt32(buf, off + 20) != size) return;
        int cb = BitConverter.ToInt32(buf, off + 8);
        int w = BitConverter.ToInt32(buf, off + 12);
        int h = BitConverter.ToInt32(buf, off + 16);
        int[] dims;
        if (!cbDims.TryGetValue(cb, out dims) || dims[0] != w || dims[1] != h) return;
        Buffer.BlockCopy(BitConverter.GetBytes(0), 0, buf, off + 12, 4);
        Buffer.BlockCopy(BitConverter.GetBytes(0), 0, buf, off + 16, 4);
        Buffer.BlockCopy(BitConverter.GetBytes(w), 0, buf, off + 20, 4);
        Buffer.BlockCopy(BitConverter.GetBytes(h), 0, buf, off + 24, 4);
        Buffer.BlockCopy(BitConverter.GetBytes(GL_RGBA), 0, buf, off + 28, 4);
        if (CapDiag.TryAdd("yuvup", true)) Console.WriteLine("[RC] YUV upload " + w + "x" + h + " size=" + size);
    }

    // rcChooseConfig: [op, len, __size_attribs, attribs..., ...]. EGL_DONT_CARE means ignore
    // the attribute, but the renderer compares these bitmasks with it (-1, every bit set)
    // and matches nothing. Chromium asks for surfaceless contexts that way, so WebView
    // finds no config, gets no GL context and aborts on an empty GL_VERSION: Google sign-in
    // crashes as soon as its page opens. A zero mask matches everything, which is the same.
    static void FixChooseConfig(byte[] buf, int off, int pktLen) {
        const int EGL_SURFACE_TYPE = 0x3033, EGL_RENDERABLE_TYPE = 0x3040, EGL_CONFORMANT = 0x3042, EGL_NONE = 0x3038;
        int size = BitConverter.ToInt32(buf, off + 8);
        if (size < 8 || size > pktLen - 12) return;
        for (int p = off + 12; p + 8 <= off + 12 + size; p += 8) {
            int attr = BitConverter.ToInt32(buf, p);
            if (attr == EGL_NONE) return;
            if (BitConverter.ToInt32(buf, p + 4) != -1) continue;
            if (attr == EGL_SURFACE_TYPE || attr == EGL_RENDERABLE_TYPE || attr == EGL_CONFORMANT) {
                Buffer.BlockCopy(BitConverter.GetBytes(0), 0, buf, p + 4, 4);
            }
        }
    }

    static void InspectOpcodes(uint ctxId, byte[] buf, int len) {
        for (int off = 0; off <= len - 8; off += 4) {
            if (buf[off + 1] != 0x27 || buf[off + 2] != 0 || buf[off + 3] != 0) continue;
            int op = BitConverter.ToInt32(buf, off);
            if (op < 10000 || op > 10080) continue;
            if (op == 10024 && len - off >= 40) RestoreYuvUpload(buf, off);
            int pktLen = BitConverter.ToInt32(buf, off + 4);
            if (pktLen < 8 || pktLen > 16 * 1024 * 1024 || off + pktLen > len) continue;

            if (op == 10006) FixChooseConfig(buf, off, pktLen);
            if (op == 10023 && pktLen == 40) NoteColorBufferRead(ctxId, buf, off, len);
            if (op == RcReadColorBufferYuv && pktLen == 36) NoteYuvRead(ctxId, buf, off, len);

            // Every op naming a colour buffer, so that one still in use is never released.
            if (pktLen >= 12 && (op == 10013 || op == 10014 || op == 10018 || op == 10020 || op == 10021 ||
                                 op == 10023 || op == 10024 || op == 10025 || op == 10034 || op == 10064)) {
                NoteCbUse(BitConverter.ToInt32(buf, off + 8), op);
            } else if (pktLen >= 16 && (op == 10015 || op == 10054 || op == 10040)) {
                NoteCbUse(BitConverter.ToInt32(buf, off + 12), op);
            } else if (pktLen >= 32 && (op == 10037 || op == 10038) && BitConverter.ToInt32(buf, off + 16) == 2) {
                // compose_device_v2 { version, displayId, targetHandle, numHwLayers, layers[] }
                NoteCbUse(BitConverter.ToInt32(buf, off + 24), op);
                int layers = BitConverter.ToInt32(buf, off + 28);
                for (int l = 0; l < layers && l < 64 && 32 + (l + 1) * 56 <= pktLen; l++) {
                    NoteCbUse(BitConverter.ToInt32(buf, off + 32 + l * 56), op);
                }
            }
            if (pktLen == 12 && (op == 10013 || op == 10025)) CountHostRefs(BitConverter.ToInt32(buf, off + 8), 1);

            if (op == 10012 || op == 10035) { // rcCreateColorBuffer (20) / rcCreateColorBufferDMA (24)
                if (pktLen == 20 || pktLen == 24) {
                    int w = BitConverter.ToInt32(buf, off + 8);
                    int h = BitConverter.ToInt32(buf, off + 12);
                    if (w > 0 && w <= 8192 && h > 0 && h <= 8192) {
                        lastCreatedW = w;
                        lastCreatedH = h;
                        ctxPendingCreate[ctxId] = new int[] { w, h, 0, 0 };
                        Console.WriteLine("[RC ctx=" + ctxId + "] op=" + op + " CreateColorBuffer(" + w + "x" + h + ")");
                    }
                }
            } else if (op == 10020) { // rcBindTexture(cb)
                if (pktLen == 12) {
                    int cb = BitConverter.ToInt32(buf, off + 8);
                    if (cb > 0 && cb < 100000 && cbPinned.TryAdd(cb, true)) {
                        KeepColorBufferAlive(cb, 2);
                    }
                }
            } else if (op == 10014) { // rcCloseColorBuffer(cb) -> replace in-place with 12-byte rcOpenColorBuffer (10013)
                if (pktLen == 12) {
                    int cb = BitConverter.ToInt32(buf, off + 8);
                    if (cb > 0 && cb < 100000) {
                        byte[] openOp = BitConverter.GetBytes(10013);
                        Buffer.BlockCopy(openOp, 0, buf, off, 4);
                        CountHostRefs(cb, 1);
                        Console.WriteLine("[RC ctx=" + ctxId + "] Turned rcCloseColorBuffer(cb=" + cb + ") into rcOpenColorBuffer");
                    }
                }
            } else if (op == 10015 || op == 10054) { // rcSetWindowColorBuffer / Async (win, cb)
                if (pktLen == 16) {
                    int win = BitConverter.ToInt32(buf, off + 8);
                    int cb = BitConverter.ToInt32(buf, off + 12);
                    if (win > 0 && win < 100000 && cb > 0 && cb < 100000) {
                        // Host-side colour buffers must outlive the guest handles that name
                        // them. Releasing these destroys buffers still on screen (flicker,
                        // "Failed to find ColorBuffer"), so the growth is accepted for now.
                        int extra = cbPinned.TryAdd(cb, true) ? 3 : 1;
                        KeepColorBufferAlive(cb, extra);
                        winPinnedCb[win] = cb;
                        int[] cd;
                        bool composerActive = (masterComposeCb > 0) && (unchecked(Environment.TickCount - lastComposeTick) < 1500);
                        if (!composerActive && !hasSeenRealDisplayPost && cbDims.TryGetValue(cb, out cd) && cd[0] == lcdWidth && cd[1] == lcdHeight) {
                            activeDisplayCb = cb;
                            lastPostTick = Environment.TickCount;
                            Interlocked.Increment(ref frameDirtyCounter);
                        }
                    }
                }
            } else if (op == 10018) { // rcFBPost(cb) - TRUE DISPLAY PRESENT
                if (pktLen == 12) {
                    int cb = BitConverter.ToInt32(buf, off + 8);
                    int[] cd;
                    { int[] dd; string ds = cbDims.TryGetValue(cb, out dd) ? dd[0] + "x" + dd[1] : "unknown"; if (CapDiag.TryAdd("post" + cb, true)) Console.WriteLine("[Capture] FBPost cb=" + cb + " dims=" + ds); }
                    if (!cbDims.TryGetValue(cb, out cd) || (cd[0] == lcdWidth && cd[1] == lcdHeight)) {
                        hasSeenRealDisplayPost = true;
                        KeepColorBufferAlive(cb, cbPinned.TryAdd(cb, true) ? 2 : 0);
                        bool composerActive = (masterComposeCb > 0) && (unchecked(Environment.TickCount - lastComposeTick) < 1500);
                        if (!composerActive || cb == masterComposeCb) {
                            activeDisplayCb = cb;
                            lastDisplayPostTick = Environment.TickCount;
                            lastPostTick = Environment.TickCount;
                            Interlocked.Increment(ref frameDirtyCounter);
                        }
                    }
                }
            } else if (op == 10016 || op == 10031 || op == 10055) { // rcFlushWindowColorBuffer* (win)
                if (pktLen >= 12) {
                    int win = BitConverter.ToInt32(buf, off + 8);
                    int cb;
                    if (winPinnedCb.TryGetValue(win, out cb) && cb > 0) {
                        int[] cd;
                        bool composerActive = (masterComposeCb > 0) && (unchecked(Environment.TickCount - lastComposeTick) < 1500);
                        if (!composerActive && !hasSeenRealDisplayPost && (!cbDims.TryGetValue(cb, out cd) || (cd[0] == lcdWidth && cd[1] == lcdHeight))) {
                            KeepColorBufferAlive(cb, cbPinned.TryAdd(cb, true) ? 2 : 0);
                            activeDisplayCb = cb;
                            lastPostTick = Environment.TickCount;
                            Interlocked.Increment(ref frameDirtyCounter);
                        }
                    }
                }
            } else if (op == 10040) { // rcSetDisplayColorBuffer(disp, cb)
                if (pktLen == 16) {
                    int cb = BitConverter.ToInt32(buf, off + 12);
                    int[] cd;
                    if (!cbDims.TryGetValue(cb, out cd) || (cd[0] == lcdWidth && cd[1] == lcdHeight)) {
                        hasSeenRealDisplayPost = true;
                        KeepColorBufferAlive(cb, cbPinned.TryAdd(cb, true) ? 2 : 0);
                        masterComposeCb = cb;
                        lastComposeTick = Environment.TickCount;
                        lastComposeStamp = System.Diagnostics.Stopwatch.GetTimestamp();
                        activeDisplayCb = cb;
                        lastDisplayPostTick = Environment.TickCount;
                        lastPostTick = Environment.TickCount;
                        Interlocked.Increment(ref frameDirtyCounter);
                    }
                }
            } else if (op == 10037 || op == 10038 || op == 10058 || op == 10059 || op == 10060 || op == 10061) { // rcCompose* (includes rcComposeAsync=10038 used by composer3.ranchu)
                if (pktLen >= 20) {
                    // [op, len, size, __size, compose_device]: { version, target, ... } for v1 and
                    // { version, displayId, target, ... } for v2.
                    int ver = BitConverter.ToInt32(buf, off + 16);
                    int targetOff = (ver >= 2) ? 24 : 20;
                    int targetCb = (pktLen >= targetOff + 4) ? BitConverter.ToInt32(buf, off + targetOff) : 0;
                    int[] cd;
                    if (targetCb > 0) {
                        if (cbDims.TryGetValue(targetCb, out cd) && cd[0] == lcdWidth && cd[1] == lcdHeight) {
                            KeepColorBufferAlive(targetCb, cbPinned.TryAdd(targetCb, true) ? 2 : 0);
                            masterComposeCb = targetCb;
                            lastComposeTick = Environment.TickCount;
                            lastComposeStamp = System.Diagnostics.Stopwatch.GetTimestamp();
                            activeDisplayCb = targetCb;
                            lastDisplayPostTick = Environment.TickCount;
                            lastPostTick = Environment.TickCount;
                            Interlocked.Increment(ref frameDirtyCounter);
                        }
                    }
                }
            }
        }
    }

    // Host services this renderer does not implement (QemuMiscPipe, qemud:camera, ...)
    // must stay open and silent. goldfish qemu_pipe_read_fully() retries forever on a
    // zero-length read, so closing makes each client spin at 100% of a vCPU.
    static void DrainUntilClosed(NetworkStream ns) {
        byte[] sink = new byte[4096];
        while (ns.Read(sink, 0, sink.Length) > 0) { }
    }

    // Android's GNSS service reads NMEA from pipe:qemud:gps. The stock emulator feeds
    // that from its own UI; nothing here did, so location never had a fix on any AVD.
    static double gpsLat = 37.4220, gpsLon = -122.0841, gpsAlt = 5.0;
    static readonly ConcurrentDictionary<NetworkStream, bool> gpsClients = new ConcurrentDictionary<NetworkStream, bool>();

    static bool TryNum(string s, out double v) {
        return double.TryParse(s, System.Globalization.NumberStyles.Float, System.Globalization.CultureInfo.InvariantCulture, out v);
    }

    static string NmeaCoord(double value, int degDigits) {
        double a = Math.Abs(value);
        int deg = (int)a;
        int tenThousandths = (int)Math.Round((a - deg) * 60.0 * 10000.0);
        if (tenThousandths >= 600000) { deg++; tenThousandths = 0; }
        return deg.ToString(new string('0', degDigits)) + (tenThousandths / 10000).ToString("00") + "." + (tenThousandths % 10000).ToString("0000");
    }

    static string NmeaLine(string body) {
        int cs = 0;
        foreach (char c in body) cs ^= c;
        return "$" + body + "*" + cs.ToString("X2") + "\n";
    }

    static void SendGpsFix(NetworkStream ns) {
        DateTime t = DateTime.UtcNow;
        var inv = System.Globalization.CultureInfo.InvariantCulture;
        string hms = t.ToString("HHmmss", inv);
        string lat = NmeaCoord(gpsLat, 2) + (gpsLat >= 0 ? ",N" : ",S");
        string lon = NmeaCoord(gpsLon, 3) + (gpsLon >= 0 ? ",E" : ",W");
        string gga = NmeaLine("GPGGA," + hms + "," + lat + "," + lon + ",1,08,1.0," + gpsAlt.ToString("0.0", inv) + ",M,0.0,M,,");
        string rmc = NmeaLine("GPRMC," + hms + ",A," + lat + "," + lon + ",0.0,0.0," + t.ToString("ddMMyy", inv) + ",0.0,W");
        byte[] b = Encoding.ASCII.GetBytes(gga + rmc);
        lock (ns) ns.Write(b, 0, b.Length);
    }

    static void GpsLoop() {
        while (true) {
            Thread.Sleep(1000);
            foreach (NetworkStream ns in gpsClients.Keys) {
                try { SendGpsFix(ns); } catch { bool gone; gpsClients.TryRemove(ns, out gone); }
            }
        }
    }

    static void HandleClient(TcpClient client) {
        client.NoDelay = true;
        client.ReceiveBufferSize = 4 * 1024 * 1024;
        client.SendBufferSize = 4 * 1024 * 1024;
        NetworkStream ns = client.GetStream();
        try {
            MemoryStream ms = new MemoryStream();
            int b;
            while ((b = ns.ReadByte()) != -1) {
                ms.WriteByte((byte)b);
                if (b == 0) break;
            }
            byte[] hdrBytes = ms.ToArray();
            if (hdrBytes.Length == 0) return;
            string hdr = Encoding.ASCII.GetString(hdrBytes, 0, hdrBytes.Length - 1);
            Console.WriteLine("[PIPE] Connected: '" + hdr + "'");

            if (hdr.StartsWith("pipe:GLProcessPipe")) {
                ulong puid = (ulong)Interlocked.Increment(ref nextProcessUid);
                byte[] resp = BitConverter.GetBytes(puid);
                ns.Write(resp, 0, 8);
                ns.Flush();
                Console.WriteLine("[PIPE] Direct GLProcessPipe -> puid=" + puid);
                Thread.Sleep(50);
                client.LingerState = new LingerOption(true, 0);
                return;
            } else if (hdr.StartsWith("pipe:opengles")) {
                uint id;
                if (!idPool.TryDequeue(out id)) {
                    id = (uint)Interlocked.Increment(ref nextId);
                }
                uint bufSize = 4 * 1024 * 1024; // per connection and mirrored by the renderer, so keep it small: 60+ stay open. Its 4-byte-per-pixel format means a transfer of w bytes needs 4*w here, or it fails after consuming data
                IntPtr buf, iovPtr;
                bool running = true;
                int lastWriteTick = Environment.TickCount;

                lock (gpuLock) {
                    CreatePipeResource(id, bufSize, out buf, out iovPtr);
                    Marshal.Copy(hdrBytes, 0, buf, hdrBytes.Length);
                    stream_renderer_box box = new stream_renderer_box { x = 0, y = 0, z = 0, w = (uint)hdrBytes.Length, h = 1, d = 1 };
                    stream_renderer_transfer_write_iov(id, id, 0, 0, 0, ref box, 0, IntPtr.Zero, 0);
                }

                // The guest has no virtio-gpu and no /dev/goldfish_sync, so it
                // must not be offered native fence sync: newer guest EGL
                // (API 36+) segfaults in createNativeSync_virtioGpu, older
                // ones fail every eglCreateSyncKHR. This gfxstream build forces
                // VirtioGpuNativeSync on regardless of ANDROID_EMULATOR_FEATURES,
                // so blank the tokens (same length, framing unchanged) in the
                // extension strings flowing back to the guest.
                ReplyPump pump = new ReplyPump(ns);
                ReplyFilter filter = new ReplyFilter(pump);
                Thread reader = new Thread(() => {
                    const int BulkChunk = 1024 * 1024; // at most bufSize/4
                    IntPtr rbuf = Marshal.AllocHGlobal(4096);
                    iovec riov = new iovec { iov_base = rbuf, iov_len = (UIntPtr)4096 };
                    IntPtr riovPtr = Marshal.AllocHGlobal(Marshal.SizeOf(typeof(iovec)));
                    Marshal.StructureToPtr(riov, riovPtr, false);
                    stream_renderer_box rbox = new stream_renderer_box { x = 0, y = 0, z = 0, w = 1, h = 1, d = 1 };
                    byte[] bulk = null;
                    IntPtr bulkBuf = IntPtr.Zero, bulkIovPtr = IntPtr.Zero;
                    try {
                        while (running) {
                            rbox.w = 1;
                            int rc = stream_renderer_transfer_read_iov(id, id, 0, 0, 0, ref rbox, 0, riovPtr, 1);
                            if (!running) break;
                            if (rc != 0) {
                                filter.Flush();  // never hold back the tail of a reply
                                Thread.Sleep(2);
                                continue;
                            }
                            byte rb = Marshal.ReadByte(rbuf, 0);
                            int bulkLeft;
                            if (ctxPendingBulk.TryRemove(id, out bulkLeft)) {
                                // Pixels: length is known, so skip the per-byte path. The
                                // byte in hand counts towards it, so this can never over-read.
                                filter.Put(rb);
                                filter.Flush();
                                bulkLeft--;
                                if (bulk == null) {
                                    // The renderer refuses (-EINVAL) an output iov no larger
                                    // than the transfer, so match the resource's own size.
                                    bulk = new byte[BulkChunk];
                                    bulkBuf = Marshal.AllocHGlobal((int)bufSize);
                                    iovec biov = new iovec { iov_base = bulkBuf, iov_len = (UIntPtr)bufSize };
                                    bulkIovPtr = Marshal.AllocHGlobal(Marshal.SizeOf(typeof(iovec)));
                                    Marshal.StructureToPtr(biov, bulkIovPtr, false);
                                }
                                while (bulkLeft > 0 && running) {
                                    int chunk = Math.Min(bulkLeft, BulkChunk);
                                    rbox.w = (uint)chunk;
                                    int brc = stream_renderer_transfer_read_iov(id, id, 0, 0, 0, ref rbox, 0, bulkIovPtr, 1);
                                    if (brc != 0) {
                                        // The pipe data may already be consumed: retrying would
                                        // lose it silently. Say so and hand back to the byte path.
                                        Console.WriteLine("[PIPE ctx=" + id + "] bulk read failed rc=" + brc + ", " + bulkLeft + " bytes left");
                                        break;
                                    }
                                    Marshal.Copy(bulkBuf, bulk, 0, chunk);
                                    pump.Write(bulk, 0, chunk);
                                    bulkLeft -= chunk;
                                }
                                continue;
                            }
                            int[] pend;
                            if (ctxPendingCreate.TryGetValue(id, out pend) && pend[2] < 4) {
                                pend[3] |= ((int)rb) << (pend[2] * 8);
                                pend[2]++;
                                if (pend[2] == 4) {
                                    int handle = pend[3];
                                    int[] dummy;
                                    ctxPendingCreate.TryRemove(id, out dummy);
                                    if (handle > 0 && handle < 100000) {
                                        cbDims[handle] = new int[] { pend[0], pend[1] };
                                        CountHostRefs(handle, 1);
                                        Console.WriteLine("[RC ctx=" + id + "] CreateColorBuffer(" + pend[0] + "x" + pend[1] + ") -> handle=" + handle);
                                        if (cbPinned.TryAdd(handle, true)) {
                                            ThreadPool.QueueUserWorkItem(_ => KeepColorBufferAlive(handle, 2));
                                        }
                                    }
                                }
                            }
                            filter.Put(rb);
                        }
                    } catch {
                    } finally {
                        if (bulkBuf != IntPtr.Zero) Marshal.FreeHGlobal(bulkBuf);
                        if (bulkIovPtr != IntPtr.Zero) Marshal.FreeHGlobal(bulkIovPtr);
                        if (rbuf != IntPtr.Zero) Marshal.FreeHGlobal(rbuf);
                        if (riovPtr != IntPtr.Zero) Marshal.FreeHGlobal(riovPtr);
                    }
                });
                reader.IsBackground = true;
                reader.Start();

                try {
                    byte[] wbytes = new byte[1024 * 1024]; // at most bufSize/4 (see above); larger writes just arrive in pieces
                    int n;
                    while ((n = ns.Read(wbytes, 0, wbytes.Length)) > 0) {
                        lock (gpuLock) {
                            InspectOpcodes(id, wbytes, n);
                            NoteGlReadPixels(id, wbytes, n);
                            Marshal.Copy(wbytes, 0, buf, n);
                            stream_renderer_box wbox = new stream_renderer_box { x = 0, y = 0, z = 0, w = (uint)n, h = 1, d = 1 };
                            int wrc = stream_renderer_transfer_write_iov(id, id, 0, 0, 0, ref wbox, 0, IntPtr.Zero, 0);
                            if (wrc != 0) Console.WriteLine("[PIPE ctx=" + id + "] write failed rc=" + wrc + " n=" + n);
                        }
                    }
                } finally {
                    running = false;
                    // The reader is normally blocked inside a native read on this pipe, and
                    // destroying anything under it crashes the whole renderer (0xc0000005)
                    // when that read returns. Every app exit lands here. Closing the context
                    // does not wake it, but a reply does: ask for one, wait for the reader
                    // to leave, and only then tear down.
                    lock (gpuLock) {
                        try {
                            Marshal.WriteInt32(buf, 0, 10000); // OP_rcGetRendererVersion
                            Marshal.WriteInt32(buf, 4, 8);
                            stream_renderer_box nudge = new stream_renderer_box { x = 0, y = 0, z = 0, w = 8, h = 1, d = 1 };
                            stream_renderer_transfer_write_iov(id, id, 0, 0, 0, ref nudge, 0, IntPtr.Zero, 0);
                        } catch {}
                    }
                    bool readerDone = false;
                    try { readerDone = reader.Join(1500); } catch {}
                    pump.Close();
                    int staleBulk;
                    ctxPendingBulk.TryRemove(id, out staleBulk);
                    Console.WriteLine("[PIPE] Disconnected opengles ctx=" + id);
                    if (readerDone) {
                        lock (gpuLock) {
                            try {
                                stream_renderer_ctx_detach_resource((int)id, (int)id);
                                stream_renderer_resource_unref(id);
                                stream_renderer_context_destroy(id);
                            } catch {}
                        }
                        if (buf != IntPtr.Zero) { Marshal.FreeHGlobal(buf); buf = IntPtr.Zero; }
                        if (iovPtr != IntPtr.Zero) { Marshal.FreeHGlobal(iovPtr); iovPtr = IntPtr.Zero; }
                        if (id <= 60) idPool.Enqueue(id);
                    } else {
                        // Still inside the renderer: leak this slot rather than crash.
                        Console.WriteLine("[PIPE ctx=" + id + "] reader did not stop; keeping its resources");
                    }
                }
            } else if (hdr.StartsWith("pipe:refcount")) {
                byte[] hb = new byte[4];
                int r = ns.Read(hb, 0, 4);
                int handle = (r == 4) ? BitConverter.ToInt32(hb, 0) : 0;
                Console.WriteLine("[PIPE] refcount connected for ColorBuffer handle=" + handle);
                if (handle > 0) {
                    KeepColorBufferAlive(handle, 2);
                }
                // Immediately send TCP RST to free QEMU SLIRP socket slot while host ColorBuffer is pinned via KeepColorBufferAlive!
                client.LingerState = new LingerOption(true, 0);
                return;
            } else if (hdr == "whpx:refs") {
                // gfx_forwarder: one uint32 handle per colour buffer the guest has finished with.
                byte[] hb = new byte[4];
                int got = 0, r;
                while ((r = ns.Read(hb, got, 4 - got)) > 0) {
                    got += r;
                    if (got == 4) { NoteBufferGone(BitConverter.ToInt32(hb, 0)); got = 0; }
                }
                return;
            } else if (hdr.StartsWith("pipe:qemud:gps")) {
                gpsClients[ns] = true;
                SendGpsFix(ns);
                try { DrainUntilClosed(ns); } finally { bool gone; gpsClients.TryRemove(ns, out gone); }
                return;
            } else if (hdr == "whpx:gps") {
                // Bridge control channel: one "lat lon altitude" line per update.
                using (StreamReader r = new StreamReader(ns, Encoding.ASCII)) {
                    string line;
                    while ((line = r.ReadLine()) != null) {
                        string[] f = line.Trim().Split(' ');
                        double lat, lon, alt;
                        if (f.Length == 3 && TryNum(f[0], out lat) && TryNum(f[1], out lon) && TryNum(f[2], out alt)
                                && Math.Abs(lat) <= 90 && Math.Abs(lon) <= 180) {
                            gpsLat = lat; gpsLon = lon; gpsAlt = alt;
                            Console.WriteLine("[GPS] position " + line.Trim());
                        }
                    }
                }
                return;
            } else if (hdr.StartsWith("pipe:qemud")) {
                byte[] lenBuf = new byte[4];
                while (true) {
                    int r = 0;
                    while (r < 4) {
                        int k = ns.Read(lenBuf, r, 4 - r);
                        if (k <= 0) return;
                        r += k;
                    }
                    string hex = Encoding.ASCII.GetString(lenBuf, 0, 4);
                    int msgLen = 0;
                    if (!int.TryParse(hex, System.Globalization.NumberStyles.HexNumber, null, out msgLen) || msgLen <= 0 || msgLen > 65536) { DrainUntilClosed(ns); return; }
                    byte[] payload = new byte[msgLen];
                    int pr = 0;
                    while (pr < msgLen) {
                        int k = ns.Read(payload, pr, msgLen - pr);
                        if (k <= 0) return;
                        pr += k;
                    }
                    string cmd = Encoding.ASCII.GetString(payload, 0, msgLen);
                    Console.WriteLine("[QEMUD] " + hdr + " cmd='" + cmd + "'");
                    if (cmd.StartsWith("list-sensors")) {
                        byte[] reply = Encoding.ASCII.GetBytes("00011");
                        ns.Write(reply, 0, 5);
                        ns.Flush();
                    }
                }
            } else {
                DrainUntilClosed(ns);
                return;
            }
        } catch (Exception ex) {
            Console.WriteLine("[PIPE] Error: " + ex.Message);
        } finally {
            try { client.Close(); } catch {}
        }
    }

    static int Main(string[] args) {
        timeBeginPeriod(1);
        if (args.Length >= 2) {
            int.TryParse(args[0], out lcdWidth);
            int.TryParse(args[1], out lcdHeight);
        }
        int port = 5000;
        if (args.Length >= 3) int.TryParse(args[2], out port);
        if (args.Length >= 4) frameOutputPath = args[3];
        // There is no machine-wide cleanup any more, so never outlive the bridge.
        int parentPid;
        if (args.Length >= 5 && int.TryParse(args[4], out parentPid)) {
            Thread parentWatch = new Thread(() => {
                while (true) {
                    Thread.Sleep(2000);
                    try { if (System.Diagnostics.Process.GetProcessById(parentPid).HasExited) Environment.Exit(0); }
                    catch (ArgumentException) { Environment.Exit(0); }
                    catch { }
                }
            });
            parentWatch.IsBackground = true;
            parentWatch.Start();
        }

        // This exe lives in <sdk>\emulator\whpx_bridge.
        string emuDir = Path.GetDirectoryName(AppDomain.CurrentDomain.BaseDirectory.TrimEnd(Path.DirectorySeparatorChar));
        string lib64 = Path.Combine(emuDir, "lib64");
        Environment.SetEnvironmentVariable("PATH", lib64 + ";" + Path.Combine(lib64, "gles_angle") + ";" + Path.Combine(lib64, "vulkan") + ";" + Environment.GetEnvironmentVariable("PATH"));
        // ANGLE's D3D11 and D3D9 backends crash on gfxstream's render threads here.
        Environment.SetEnvironmentVariable("ANGLE_DEFAULT_PLATFORM", "vulkan");
        Environment.SetEnvironmentVariable("ANDROID_EMULATOR_FEATURES", "VirtioGpuNativeSync:disabled,VirtioGpuNext:disabled,RefCountPipe:enabled,NoDelayCloseColorBuffer:disabled,GlDirectMem:disabled,GlDma:disabled,GlDma2:disabled,ReadColorBufferDma:disabled,HasSharedSlotsHostMemoryAllocator:disabled");
        SetDllDirectory(lib64);

        sFenceCallback = new FenceCallback(OnFence);
        IntPtr fcPtr = Marshal.GetFunctionPointerForDelegate(sFenceCallback);

        stream_renderer_param[] p = new stream_renderer_param[] {
            new stream_renderer_param { key = 1, value = 0 },
            new stream_renderer_param { key = 2, value = (1UL << 0) | (1UL << 1) | (1UL << 4) }, // USE_EGL | USE_GLES | USE_SURFACELESS
            new stream_renderer_param { key = 3, value = (ulong)fcPtr.ToInt64() },
            new stream_renderer_param { key = 4, value = (ulong)lcdWidth },
            new stream_renderer_param { key = 5, value = (ulong)lcdHeight },
            // Feature overrides (key 11 in this build; 7 and 10 crash). Buffers are closed from the keep-alive
            // thread. With the virtio default of "no delay" that destroys them there, racing
            // other threads' buffer creation for the shared GL context. Delayed, the creating
            // thread destroys them under the framebuffer lock, as on the stock emulator.
            // YuvCache: without it the guest mapper reads YUV buffers (every decoded video
            // frame) into a width*height*3 heap buffer with a 4 byte per pixel read and
            // overruns it, crashing media.swcodec on the first frame.
            new stream_renderer_param { key = 11, value = (ulong)Marshal.StringToHGlobalAnsi("NoDelayCloseColorBuffer:disabled,YuvCache:enabled").ToInt64() }
        };

        int rc = stream_renderer_init(p, (ulong)p.Length);
        Console.WriteLine("[GfxstreamPipeServer] stream_renderer_init(" + lcdWidth + "x" + lcdHeight + ") rc = " + rc);
        if (rc != 0) return 1;

        // Header, two frame slots, then the size the viewer wants (see CaptureRequest).
        int totalFileSize = 16 + lcdWidth * lcdHeight * 4 * 2 + 8;
        try {
            sFrameFs = new FileStream(frameOutputPath, FileMode.OpenOrCreate, FileAccess.ReadWrite, FileShare.ReadWrite, 65536, FileOptions.RandomAccess);
            try {
                if (sFrameFs.Length != totalFileSize) sFrameFs.SetLength(totalFileSize);
            } catch {}
            try {
                sFrameFs.Position = 0;
                sFrameFs.Write(new byte[16], 0, 16);
                sFrameFs.Flush();
            } catch {}
            AppDomain.CurrentDomain.ProcessExit += (s, e) => {
                try {
                    if (sFrameFs != null && sFrameFs.CanWrite) {
                        sFrameFs.Position = 0;
                        sFrameFs.Write(new byte[16], 0, 16);
                        sFrameFs.Flush();
                    }
                } catch {}
            };
            sFrameMmf = MemoryMappedFile.CreateFromFile(sFrameFs, null, totalFileSize, MemoryMappedFileAccess.ReadWrite, HandleInheritability.None, false);
            sFrameAccessor = sFrameMmf.CreateViewAccessor(0, totalFileSize, MemoryMappedFileAccess.ReadWrite);
            unsafe {
                byte* pView = null;
                sFrameAccessor.SafeMemoryMappedViewHandle.AcquirePointer(ref pView);
                sFramePtr = pView;
            }
            Console.WriteLine("[GfxstreamPipeServer] MMF initialized for zero-latency frame sharing (" + totalFileSize + " bytes)");
        } catch (Exception ex) {
            Console.WriteLine("[GfxstreamPipeServer] MMF init error: " + ex.Message);
        }

        InitKeepAlivePipe();

        Thread gpsThread = new Thread(GpsLoop);
        gpsThread.IsBackground = true;
        gpsThread.Start();

        Thread cbThread = new Thread(CbReleaseLoop);
        cbThread.IsBackground = true;
        cbThread.Start();

        Thread capThread = new Thread(FrameCaptureLoop);
        capThread.IsBackground = true;
        capThread.Start();

        TcpListener listener = new TcpListener(IPAddress.Any, port);
        listener.Server.SetSocketOption(SocketOptionLevel.Socket, SocketOptionName.ReuseAddress, true);
        listener.ExclusiveAddressUse = false;
        for (int attempt = 0; attempt < 20; attempt++) {
            try {
                listener.Start();
                break;
            } catch (SocketException) {
                if (attempt == 19) throw;
                Thread.Sleep(200);
            }
        }
        Console.WriteLine("[GfxstreamPipeServer] Listening on 0.0.0.0:" + port);

        while (true) {
            try {
                TcpClient client = listener.AcceptTcpClient();
                Thread t = new Thread(() => HandleClient(client));
                t.IsBackground = true;
                t.Start();
            } catch (Exception ex) {
                Console.WriteLine("[GfxstreamPipeServer] Accept error: " + ex.Message);
                Thread.Sleep(100);
            }
        }
    }
}
