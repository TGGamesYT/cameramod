package dev.tggamesyt.cameramod.client;

import dev.tggamesyt.cameramod.Cameramod;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.MemoryCacheImageOutputStream;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferByte;
import java.io.*;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

public class CameraStreamServer {

    private static final int PORT = 7236;
    private static final String BOUNDARY = "mjpegframe";
    private static ServerSocket serverSocket;
    private static final Set<OutputStream> clients = Collections.synchronizedSet(new LinkedHashSet<>());

    // Single background thread handles JPEG encoding + socket writes so the render thread isn't blocked.
    // SynchronousQueue (0 capacity) + AbortPolicy: if the encoder thread is already busy the new frame
    // task is rejected (execute() throws RejectedExecutionException) rather than queued, so pushFrame()
    // can recycle the frame buffer instead of leaking it.  This prevents the unbounded queue growth
    // that caused the OOM crash and the multi-second stream delay when production outpaced encoding.
    private static final ExecutorService pushExecutor = new ThreadPoolExecutor(
            1, 1, 0L, TimeUnit.MILLISECONDS,
            new SynchronousQueue<>(),
            r -> { Thread t = new Thread(r, "CameraStream-push"); t.setDaemon(true); return t; },
            new ThreadPoolExecutor.AbortPolicy()
    );

    // Reusable frame-copy buffers. pushFrame() must give the encoder thread its own copy of the
    // render buffer (the render thread overwrites the source in place every frame). Allocating
    // that copy fresh each frame churned multi-MB "humongous" arrays that fragmented the G1 heap
    // until a copy allocation OOM'd — pooling keeps a tiny fixed set of buffers alive instead.
    private static final ArrayDeque<byte[]> framePool = new ArrayDeque<>();
    private static final int FRAME_POOL_MAX = 3;

    // Reused image buffer (encoder thread only — no concurrent access).
    // TYPE_3BYTE_BGR matches the captured frame layout exactly, so we can
    // System.arraycopy straight in instead of looping per pixel to swizzle
    // bytes into a packed int.  At 1080p that loop alone was several ms
    // per frame and was the encoder's bottleneck — arraycopy is one
    // memcpy.
    private static BufferedImage streamImage;
    private static byte[] streamBytes;

    // Cached JPEG encoder + reusable byte buffer.  ImageIO.write() does a
    // ServiceLoader lookup for the codec on every call and allocates a fresh
    // ByteArrayOutputStream — both add up at high frame rates.  Reusing them
    // measurably increases throughput (encoder-bound previously dropped frames
    // and capped effective stream FPS).
    private static ImageWriter jpegWriter;
    private static ImageWriteParam jpegParams;
    private static final ByteArrayOutputStream jpegBuffer = new ByteArrayOutputStream(1 << 16);

    // Most-recently-pushed JPEG. New clients receive it immediately so the browser
    // never stalls waiting for the first frame. Volatile: written on push thread, read on accept thread.
    private static volatile byte[] lastFrame;
    private static volatile long lastFrameTime = 0;

    // Placeholder black JPEG sent until the first real frame arrives.
    static {
        try {
            BufferedImage black = new BufferedImage(16, 9, BufferedImage.TYPE_INT_RGB);
            ByteArrayOutputStream baos = new ByteArrayOutputStream(256);
            ImageIO.write(black, "jpeg", baos);
            lastFrame = baos.toByteArray();
        } catch (Exception ignored) {}
    }

    public static void start() {
        // Heartbeat: push lastFrame every second when no real frame has been sent recently,
        // so the browser <img> tag never goes stale/broken while the stream is connected.
        Thread heartbeat = new Thread(() -> {
            while (true) {
                try {
                    Thread.sleep(1000);
                    if (!clients.isEmpty() && System.currentTimeMillis() - lastFrameTime > 1500) {
                        byte[] lf = lastFrame;
                        if (lf != null) pushJpegToAll(lf);
                    }
                } catch (InterruptedException e) { break; }
            }
        }, "CameraStream-heartbeat");
        heartbeat.setDaemon(true);
        heartbeat.start();

        Thread acceptThread = new Thread(() -> {
            try {
                serverSocket = new ServerSocket(PORT, 50, InetAddress.getLoopbackAddress());
                Cameramod.LOGGER.info("Camera MJPEG stream at http://127.0.0.1:{}/", PORT);
                while (!serverSocket.isClosed()) {
                    try {
                        Socket client = serverSocket.accept();
                        Thread t = new Thread(() -> handleClient(client), "CameraStream-client");
                        t.setDaemon(true);
                        t.start();
                    } catch (IOException ignored) {}
                }
            } catch (Exception e) {
                Cameramod.LOGGER.warn("CameraStreamServer failed to start on port {}: {}", PORT, e.getMessage());
            }
        }, "CameraStream-accept");
        acceptThread.setDaemon(true);
        acceptThread.start();
    }

    public static boolean hasClients() {
        return !clients.isEmpty();
    }

    // Simple wrapper that works in both regular browsers AND OBS Studio's CEF
    // browser source. Avoid fetch() / setInterval() based health-check: CEF
    // sometimes blocks fetch under its security model, which broke the page
    // entirely in OBS.
    //
    // Reconnection strategy:
    //  - onerror: the connection was rejected or closed. Reload after 1s.
    //  - Watchdog ping via XMLHttpRequest to /ping every 3s. If the ping
    //    fails or times out twice in a row, reload the stream. Same-origin
    //    XHR is allowed under CEF; this catches the case where the MJPEG
    //    connection technically stays open but the server has hung or the
    //    proxy in between dropped data.
    private static final String INDEX_HTML =
        "<!DOCTYPE html><html><head><meta charset='utf-8'><title>Camera Stream</title>" +
        "<style>html,body{margin:0;padding:0;background:#000;width:100%;height:100%;overflow:hidden}" +
        "img{width:100%;height:100%;object-fit:contain;display:block}</style>" +
        "</head><body><img id='s' src='/stream'>" +
        "<script>" +
        "var s=document.getElementById('s');" +
        "var failures=0;" +
        "function reload(){failures=0;s.src='/stream?'+Date.now();}" +
        "s.onerror=function(){setTimeout(reload,1000);};" +
        "function ping(){" +
          "try{" +
            "var x=new XMLHttpRequest();" +
            "x.open('GET','/ping?'+Date.now(),true);" +
            "x.timeout=2500;" +
            "x.onload=function(){failures=0;setTimeout(ping,3000);};" +
            "x.onerror=function(){failures++;if(failures>=2)reload();setTimeout(ping,3000);};" +
            "x.ontimeout=x.onerror;" +
            "x.send();" +
          "}catch(e){setTimeout(ping,3000);}" +
        "}" +
        "setTimeout(ping,3000);" +
        "</script></body></html>";

    private static void handleClient(Socket socket) {
        OutputStream out = null;
        try {
            socket.setTcpNoDelay(true);
            InputStream in = socket.getInputStream();
            // Read HTTP request headers until blank line
            StringBuilder headers = new StringBuilder();
            byte[] buf = new byte[4096];
            while (!headers.toString().contains("\r\n\r\n") && !headers.toString().contains("\n\n")) {
                int n = in.read(buf);
                if (n <= 0) return;
                headers.append(new String(buf, 0, n, java.nio.charset.StandardCharsets.ISO_8859_1));
            }
            // Extract request path from first line (e.g. "GET /stream HTTP/1.1")
            String firstLine = headers.toString().split("\r?\n")[0];
            String path = "/";
            String[] parts = firstLine.split(" ");
            if (parts.length >= 2) path = parts[1].split("\\?")[0];

            out = socket.getOutputStream();

            if (path.equals("/ping")) {
                // Liveness probe — kept for any tooling still polling it, but
                // the bundled wrapper page no longer uses it.
                String resp = "HTTP/1.1 200 OK\r\n" +
                        "Content-Type: text/plain\r\n" +
                        "Content-Length: 2\r\n" +
                        "Cache-Control: no-cache, no-store, must-revalidate\r\n" +
                        "Pragma: no-cache\r\n" +
                        "Access-Control-Allow-Origin: *\r\n" +
                        "Connection: close\r\n\r\nok";
                out.write(resp.getBytes());
                out.flush();
                return;
            }

            if (!path.equals("/stream")) {
                byte[] body = INDEX_HTML.getBytes(java.nio.charset.StandardCharsets.UTF_8);
                String resp = "HTTP/1.1 200 OK\r\n" +
                        "Content-Type: text/html; charset=utf-8\r\n" +
                        "Content-Length: " + body.length + "\r\n" +
                        "Cache-Control: no-cache, no-store, must-revalidate\r\n" +
                        "Pragma: no-cache\r\n" +
                        "Access-Control-Allow-Origin: *\r\n" +
                        "X-Content-Type-Options: nosniff\r\n" +
                        "Connection: close\r\n\r\n";
                out.write(resp.getBytes());
                out.write(body);
                out.flush();
                return;
            }

            // Serve MJPEG stream — HTTP/1.1 + thorough no-cache + CORS so OBS
            // Studio's CEF browser source picks it up. Some CEF builds choke
            // on HTTP/1.0 multipart and silently render only the first frame.
            String response = "HTTP/1.1 200 OK\r\n" +
                    "Content-Type: multipart/x-mixed-replace; boundary=" + BOUNDARY + "\r\n" +
                    "Cache-Control: no-cache, no-store, must-revalidate, private\r\n" +
                    "Pragma: no-cache\r\n" +
                    "Expires: 0\r\n" +
                    "Access-Control-Allow-Origin: *\r\n" +
                    "X-Content-Type-Options: nosniff\r\n" +
                    "Connection: close\r\n\r\n";
            out.write(response.getBytes());
            out.flush();

            // Send the last known frame immediately so the browser shows something
            // rather than waiting for the next real frame (which may be seconds away).
            byte[] lf = lastFrame;
            if (lf != null) {
                writeMjpegPart(out, lf);
                out.flush();
            }

            clients.add(out);
            // Block until client disconnects
            while (in.read() != -1) { /* wait */ }
        } catch (Exception ignored) {
        } finally {
            if (out != null) clients.remove(out);
            try { socket.close(); } catch (Exception ignored) {}
        }
    }

    /**
     * Called from the render thread. Copies the frame and offloads encoding + sending
     * to the background executor so the render thread isn't stalled.
     * bgr: BGR24, rows stored bottom-to-top (same layout as SoftCam / BMP).
     */
    public static void pushFrame(byte[] bgr, int w, int h) {
        if (clients.isEmpty()) return;
        byte[] copy = acquireFrameBuffer(bgr.length);
        System.arraycopy(bgr, 0, copy, 0, bgr.length);
        try {
            pushExecutor.execute(() -> {
                try { pushFrameAsync(copy, w, h); }
                finally { releaseFrameBuffer(copy); }
            });
        } catch (RejectedExecutionException busy) {
            // Encoder thread still working on the previous frame — drop this
            // frame and recycle the buffer immediately.
            releaseFrameBuffer(copy);
        }
    }

    private static byte[] acquireFrameBuffer(int len) {
        synchronized (framePool) {
            byte[] b;
            while ((b = framePool.poll()) != null) {
                if (b.length == len) return b;
                // Wrong size (resolution changed) — drop it and keep looking.
            }
        }
        return new byte[len];
    }

    private static void releaseFrameBuffer(byte[] b) {
        synchronized (framePool) {
            if (framePool.size() < FRAME_POOL_MAX) framePool.offer(b);
        }
    }

    private static void pushFrameAsync(byte[] bgr, int w, int h) {
        byte[] jpeg = bgrToJpeg(bgr, w, h);
        if (jpeg == null) return;
        lastFrame = jpeg;
        lastFrameTime = System.currentTimeMillis();
        pushJpegToAll(jpeg);
    }

    private static void pushJpegToAll(byte[] jpeg) {
        synchronized (clients) {
            Iterator<OutputStream> it = clients.iterator();
            while (it.hasNext()) {
                OutputStream out = it.next();
                try {
                    writeMjpegPart(out, jpeg);
                    out.flush();
                } catch (IOException e) {
                    it.remove();
                }
            }
        }
    }

    private static void writeMjpegPart(OutputStream out, byte[] jpeg) throws IOException {
        String header = "--" + BOUNDARY + "\r\nContent-Type: image/jpeg\r\nContent-Length: " + jpeg.length + "\r\n\r\n";
        out.write(header.getBytes());
        out.write(jpeg);
        out.write("\r\n".getBytes());
    }

    /**
     * Encode a BGR24 top-down frame as JPEG.  Reuses a static BufferedImage
     * and a cached ImageWriter so neither the framebuffer nor the codec
     * lookup is re-allocated per frame.
     */
    private static byte[] bgrToJpeg(byte[] bgr, int w, int h) {
        try {
            if (streamImage == null || streamImage.getWidth() != w || streamImage.getHeight() != h) {
                streamImage = new BufferedImage(w, h, BufferedImage.TYPE_3BYTE_BGR);
                streamBytes = ((DataBufferByte) streamImage.getRaster().getDataBuffer()).getData();
            }
            int needed = w * h * 3;
            int copyLen = Math.min(bgr.length, Math.min(streamBytes.length, needed));
            System.arraycopy(bgr, 0, streamBytes, 0, copyLen);
            if (jpegWriter == null) {
                java.util.Iterator<ImageWriter> it = ImageIO.getImageWritersByFormatName("jpeg");
                if (!it.hasNext()) return null;
                jpegWriter = it.next();
                jpegParams = jpegWriter.getDefaultWriteParam();
                jpegParams.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
                // 0.75 is the ImageIO default; bumping slightly trades a few
                // percent more bytes per frame for noticeably less encode time
                // and crisper text in the stream.
                jpegParams.setCompressionQuality(0.80f);
            }
            jpegBuffer.reset();
            MemoryCacheImageOutputStream ios = new MemoryCacheImageOutputStream(jpegBuffer);
            try {
                jpegWriter.setOutput(ios);
                jpegWriter.write(null, new IIOImage(streamImage, null, null), jpegParams);
            } finally {
                ios.close();
                jpegWriter.setOutput(null);
            }
            return jpegBuffer.toByteArray();
        } catch (Exception e) {
            return null;
        }
    }
}
