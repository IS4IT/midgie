/*
 * Copyright (c) 2026 IS4IT
 *
 * SPDX-License-Identifier: EUPL-1.2
 */
package de.is4it.midpoint.diff;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * A forward proxy, just real enough to test against: it speaks absolute-form requests and {@code CONNECT},
 * demands Basic credentials when asked to, and records every request line it saw.
 *
 * <p>A stub rather than a mock because the thing under test is whether the JDK's HTTP client — and midgie's
 * own {@code CONNECT} handshake — put the right bytes on the wire. Anything that asserted on midgie's own
 * calls instead would pass while the wire format was wrong.
 */
final class StubProxy implements AutoCloseable {

    private final ServerSocket socket;
    private final String expectedCredentials;
    private final List<String> requests = Collections.synchronizedList(new ArrayList<>());
    private volatile boolean running = true;

    private StubProxy(String user, String password) throws IOException {
        this.socket = new ServerSocket(0, 50, java.net.InetAddress.getByName("127.0.0.1"));
        this.expectedCredentials = (user == null) ? null : Base64.getEncoder()
                .encodeToString((user + ":" + password).getBytes(StandardCharsets.UTF_8));
        Thread accepting = new Thread(this::acceptLoop, "stub-proxy");
        accepting.setDaemon(true);
        accepting.start();
    }

    static StubProxy open() throws IOException {
        return new StubProxy(null, null);
    }

    /** A proxy that answers 407 until it sees exactly these credentials. */
    static StubProxy requiringAuth(String user, String password) throws IOException {
        return new StubProxy(user, password);
    }

    URI uri() {
        return URI.create("http://127.0.0.1:" + socket.getLocalPort());
    }

    /** Every request line the proxy received, in order. */
    List<String> requests() {
        return List.copyOf(requests);
    }

    long countAuthenticated() {
        return requests.stream().filter(line -> line.endsWith(" +auth")).count();
    }

    @Override
    public void close() throws IOException {
        running = false;
        socket.close();
    }

    private void acceptLoop() {
        while (running) {
            final Socket client;
            try {
                client = socket.accept();
            } catch (IOException e) {
                return;   // closed
            }
            Thread worker = new Thread(() -> serve(client), "stub-proxy-connection");
            worker.setDaemon(true);
            worker.start();
        }
    }

    private void serve(Socket client) {
        try (client) {
            String requestLine = readLine(client.getInputStream());
            if (requestLine == null) {
                return;
            }
            String credentials = null;
            String header;
            while ((header = readLine(client.getInputStream())) != null && !header.isEmpty()) {
                if (header.toLowerCase(Locale.ROOT).startsWith("proxy-authorization: basic ")) {
                    credentials = header.substring("proxy-authorization: basic ".length()).strip();
                }
            }
            requests.add(requestLine + (credentials != null ? " +auth" : ""));

            if (expectedCredentials != null && !expectedCredentials.equals(credentials)) {
                write(client, "HTTP/1.1 407 Proxy Authentication Required\r\n"
                        + "Proxy-Authenticate: Basic realm=\"stub\"\r\n"
                        + "Content-Length: 0\r\n"
                        + "Connection: close\r\n\r\n");
                return;
            }
            if (requestLine.startsWith("CONNECT ")) {
                tunnel(client, requestLine.split(" ")[1]);
            } else {
                forward(client, requestLine);
            }
        } catch (IOException e) {
            // A client that hangs up mid-exchange is normal here; the assertions live in the test.
        }
    }

    /** {@code CONNECT host:port} — answer 200 and then be a pipe in both directions. */
    private void tunnel(Socket client, String target) throws IOException {
        int colon = target.lastIndexOf(':');
        try (Socket upstream = new Socket()) {
            upstream.connect(new InetSocketAddress(target.substring(0, colon),
                    Integer.parseInt(target.substring(colon + 1))), 5000);
            write(client, "HTTP/1.1 200 Connection Established\r\n\r\n");
            pipeBothWays(client, upstream);
        }
    }

    /**
     * An absolute-form request ({@code GET http://host/path HTTP/1.1}) — rewrite the request line to
     * origin-form, replay it upstream with the headers the client sent, and pipe the answer back.
     */
    private void forward(Socket client, String requestLine) throws IOException {
        String[] parts = requestLine.split(" ");
        URI absolute = URI.create(parts[1]);
        int port = (absolute.getPort() > 0) ? absolute.getPort() : 80;
        String path = (absolute.getRawPath() == null || absolute.getRawPath().isEmpty()) ? "/" : absolute.getRawPath();
        if (absolute.getRawQuery() != null) {
            path = path + "?" + absolute.getRawQuery();
        }
        try (Socket upstream = new Socket()) {
            upstream.connect(new InetSocketAddress(absolute.getHost(), port), 5000);
            // The headers were drained to find the credentials, so they are replayed rather than relayed:
            // enough of them for the stub server on the other side, which reads the method, path and auth.
            write(upstream, parts[0] + " " + path + " " + parts[2] + "\r\n"
                    + "Host: " + absolute.getHost() + ":" + port + "\r\n"
                    + "Connection: close\r\n\r\n");
            pipeBothWays(client, upstream);
        }
    }

    private static void pipeBothWays(Socket one, Socket other) throws IOException {
        Thread forward = new Thread(() -> copy(one, other), "stub-proxy-pipe");
        forward.setDaemon(true);
        forward.start();
        copy(other, one);
        try {
            forward.join(5000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void copy(Socket from, Socket to) {
        byte[] buffer = new byte[8192];
        try {
            InputStream in = from.getInputStream();
            OutputStream out = to.getOutputStream();
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
                out.flush();
            }
        } catch (IOException e) {
            // Either end closing is how a tunnel ends.
        }
        try {
            to.shutdownOutput();
        } catch (IOException e) {
            // Already closed.
        }
    }

    private static void write(Socket socket, String text) throws IOException {
        socket.getOutputStream().write(text.getBytes(StandardCharsets.US_ASCII));
        socket.getOutputStream().flush();
    }

    /** Byte at a time, so the bytes after the headers stay on the socket for the tunnel to carry. */
    private static String readLine(InputStream in) throws IOException {
        StringBuilder line = new StringBuilder();
        int read;
        while ((read = in.read()) != -1) {
            if (read == '\n') {
                int end = line.length();
                if (end > 0 && line.charAt(end - 1) == '\r') {
                    line.setLength(end - 1);
                }
                return line.toString();
            }
            line.append((char) read);
        }
        return line.length() > 0 ? line.toString() : null;
    }
}
