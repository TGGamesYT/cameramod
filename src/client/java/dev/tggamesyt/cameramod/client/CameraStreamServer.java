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

    public static void start() {
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

    private static final String INDEX_HTML =
        "<!DOCTYPE html><html><head><meta charset='utf-8'><title>Camera Stream</title>" +
        "<style>*{margin:0;padding:0}body{background:#000;width:100vw;height:100vh;display:flex;" +
        "align-items:center;justify-content:center}img{max-width:100%;max-height:100vh}" +
        "#status{position:fixed;top:8px;left:50%;transform:translateX(-50%);color:#fff;" +
        "background:rgba(0,0,0,.6);padding:2px 8px;border-radius:4px;font-family:sans-serif;" +
        "font-size:13px;display:none}</style></head><body>" +
        "<div id='status'></div><img id='s'>" +
        "<script>" +
        "var delay=500,max=8000,t,s=document.getElementById('s'),st=document.getElementById('status');" +
        "function show(m){st.textContent=m;st.style.display='block';}" +
        "function hide(){st.style.display='none';}" +
        "function load(){clearTimeout(t);s.src='/stream?'+Date.now();}" +
        "s.onload=function(){delay=500;hide();};" +
        "s.onerror=function(){show('Reconnecting…');delay=Math.min(delay*2,max);t=setTimeout(load,delay);};" +
        "load();" +
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
        String header = "--" + BOUNDARY + "\r\nContent-Type: image/jpeg\r\nContent-Length: " + jpeg.length + "\r\n\r\n";
        byte[] headerBytes = header.getBytes();
        byte[] trailer = "\r\n".getBytes();
        synchronized (clients) {
            Iterator<OutputStream> it = clients.iterator();
            while (it.hasNext()) {
                OutputStream out = it.next();
                try {
                    out.write(headerBytes);
                    out.write(jpeg);
                    out.write(trailer);
                    out.flush();
                } catch (IOException e) {
                    it.remove();
                }
            }
        }
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
                int srcRow = y;
                int srcBase = srcRow * w * 3;
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
