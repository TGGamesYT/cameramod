package dev.tggamesyt.cameramod.client;

import dev.tggamesyt.cameramod.Cameramod;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.io.*;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class CameraStreamServer {

    private static final int PORT = 7236;
    private static final String BOUNDARY = "mjpegframe";
    private static ServerSocket serverSocket;
    private static final Set<OutputStream> clients = Collections.synchronizedSet(new LinkedHashSet<>());

    // Single background thread handles JPEG encoding + socket writes so the render thread isn't blocked.
    private static final ExecutorService pushExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "CameraStream-push");
        t.setDaemon(true);
        return t;
    });

    // Reused image buffer (encoder thread only — no concurrent access).
    private static BufferedImage streamImage;
    private static int[] streamPixels;

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

    // Poll /ping every 1s — independent of the long-running /stream connection.
    // When ping fails (game/server restart), we know to reload the <img> src so
    // the browser drops its stale connection and reconnects to the new server.
    // The <img> onerror alone is unreliable: MJPEG connections can hang in a
    // "headers received but no new frames" state forever after a server crash.
    private static final String INDEX_HTML =
        "<!DOCTYPE html><html><head><meta charset='utf-8'><title>Camera Stream</title>" +
        "<style>*{margin:0;padding:0}body{background:#000;width:100vw;height:100vh;display:flex;" +
        "align-items:center;justify-content:center}img{max-width:100%;max-height:100vh}" +
        "#status{position:fixed;top:8px;left:50%;transform:translateX(-50%);color:#fff;" +
        "background:rgba(0,0,0,.6);padding:2px 8px;border-radius:4px;font-family:sans-serif;" +
        "font-size:13px;display:none}</style></head><body>" +
        "<div id='status'></div><img id='s'>" +
        "<script>" +
        "var s=document.getElementById('s'),st=document.getElementById('status');" +
        "var serverUp=null;" + // null=unknown, true=up, false=down
        "function show(m){st.textContent=m;st.style.display='block';}" +
        "function hide(){st.style.display='none';}" +
        "function reload(){s.src='/stream?'+Date.now();}" +
        "function ping(){" +
        "  fetch('/ping?'+Date.now(),{cache:'no-store'}).then(function(r){" +
        "    if(!r.ok)throw 0;" +
        "    if(serverUp!==true){serverUp=true;hide();reload();}" +
        "  }).catch(function(){" +
        "    if(serverUp!==false){serverUp=false;show('Reconnecting…');}" +
        "  });" +
        "}" +
        "s.onerror=function(){show('Reconnecting…');setTimeout(reload,1000);};" +
        "reload();ping();setInterval(ping,1000);" +
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
                // Liveness probe — short response, immediate close. The wrapper
                // polls this so a dead server is detected even if the long-lived
                // /stream connection appears "still open" to the browser.
                String resp = "HTTP/1.0 200 OK\r\n" +
                        "Content-Type: text/plain\r\n" +
                        "Content-Length: 2\r\n" +
                        "Cache-Control: no-cache\r\n" +
                        "Access-Control-Allow-Origin: *\r\n" +
                        "Connection: close\r\n\r\nok";
                out.write(resp.getBytes());
                out.flush();
                return;
            }

            if (!path.equals("/stream")) {
                // Serve the HTML wrapper page
                byte[] body = INDEX_HTML.getBytes(java.nio.charset.StandardCharsets.UTF_8);
                String resp = "HTTP/1.0 200 OK\r\n" +
                        "Content-Type: text/html; charset=utf-8\r\n" +
                        "Content-Length: " + body.length + "\r\n" +
                        "Cache-Control: no-cache\r\n" +
                        "Connection: close\r\n\r\n";
                out.write(resp.getBytes());
                out.write(body);
                out.flush();
                return;
            }

            // Serve MJPEG stream
            String response = "HTTP/1.0 200 OK\r\n" +
                    "Content-Type: multipart/x-mixed-replace; boundary=" + BOUNDARY + "\r\n" +
                    "Cache-Control: no-cache\r\n" +
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
        byte[] copy = Arrays.copyOf(bgr, bgr.length);
        pushExecutor.execute(() -> pushFrameAsync(copy, w, h));
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
     * Convert BGR24 bottom-to-top frame to JPEG (top-to-bottom RGB).
     * Reuses a static BufferedImage so allocation is amortized.
     */
    private static byte[] bgrToJpeg(byte[] bgr, int w, int h) {
        try {
            if (streamImage == null || streamImage.getWidth() != w || streamImage.getHeight() != h) {
                streamImage = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
                streamPixels = ((DataBufferInt) streamImage.getRaster().getDataBuffer()).getData();
            }
            for (int y = 0; y < h; y++) {
                int srcBase = y * w * 3;
                int dstBase = y * w;
                for (int x = 0; x < w; x++) {
                    int si = srcBase + x * 3;
                    int b = bgr[si] & 0xFF;
                    int g = bgr[si + 1] & 0xFF;
                    int r = bgr[si + 2] & 0xFF;
                    streamPixels[dstBase + x] = (r << 16) | (g << 8) | b;
                }
            }
            ByteArrayOutputStream baos = new ByteArrayOutputStream(w * h / 4);
            ImageIO.write(streamImage, "jpeg", baos);
            return baos.toByteArray();
        } catch (Exception e) {
            return null;
        }
    }
}
