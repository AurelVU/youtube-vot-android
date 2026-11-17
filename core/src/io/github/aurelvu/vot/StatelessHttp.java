package io.github.aurelvu.vot;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import javax.net.ssl.SNIHostName;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

/** Isolated HTTP/1.1 over platform-validated TLS. No CookieHandler, credentials, or redirects. */
public final class StatelessHttp implements VotClient.Transport {
    private static final int CONNECT_TIMEOUT_MS = 15_000;
    private static final int READ_TIMEOUT_MS = 25_000;
    private static final int LINE_LIMIT = 8192;
    private static final int HEADER_LIMIT = 32768;
    private static final int HEADER_COUNT = 128;
    private final Object lock = new Object();
    private boolean cancelled;
    private Socket active;

    @Override public VotClient.Response execute(VotClient.Request request) throws IOException {
        if (request == null) throw VotClient.failure("Invalid network request");
        // Revalidate the immutable header snapshot before touching the network.
        request = new VotClient.Request(request.uri,request.method,request.headers,request.body,request.maxBytes);
        Socket socket = new Socket();
        SSLSocket tls = null;
        try {
            publish(socket);
            String host = request.uri.getHost();
            socket.connect(new InetSocketAddress(host,443),CONNECT_TIMEOUT_MS);
            socket.setSoTimeout(READ_TIMEOUT_MS);
            tls = (SSLSocket) ((SSLSocketFactory)SSLSocketFactory.getDefault()).createSocket(socket,host,443,true);
            publish(tls);
            tls.setSoTimeout(READ_TIMEOUT_MS);
            SSLParameters parameters = tls.getSSLParameters();
            parameters.setEndpointIdentificationAlgorithm("HTTPS");
            parameters.setServerNames(Collections.singletonList(new SNIHostName(host)));
            tls.setSSLParameters(parameters);
            tls.startHandshake();
            OutputStream out = tls.getOutputStream();
            String path = request.uri.getRawPath();
            if (path == null || path.isEmpty()) path = "/";
            if (request.uri.getRawQuery() != null) path += "?" + request.uri.getRawQuery();
            StringBuilder headers = new StringBuilder(request.method).append(' ').append(path)
                    .append(" HTTP/1.1\r\nHost: ").append(host)
                    .append("\r\nConnection: close\r\nAccept-Encoding: identity\r\nContent-Length: ").append(request.body.length).append("\r\n");
            for (Map.Entry<String,String> entry : request.headers.entrySet())
                headers.append(entry.getKey()).append(": ").append(entry.getValue()).append("\r\n");
            headers.append("\r\n");
            out.write(headers.toString().getBytes(StandardCharsets.US_ASCII));
            out.write(request.body);
            out.flush();
            VotClient.Response response = readResponse(new BufferedInputStream(tls.getInputStream()),request.maxBytes);
            synchronized (lock) { if (cancelled) throw VotClient.failure("Translation request cancelled"); }
            return response;
        } catch (Exception error) {
            synchronized (lock) { if (cancelled) throw VotClient.failure("Translation request cancelled"); }
            throw VotClient.failure("HTTPS transport failed");
        } finally {
            close(tls);
            close(socket);
            synchronized (lock) { if (active == socket || active == tls) active = null; }
        }
    }

    private void publish(Socket socket) throws IOException {
        synchronized (lock) {
            if (cancelled) { close(socket); throw VotClient.failure("Translation request cancelled"); }
            if (active != null && active != socket && !(socket instanceof SSLSocket)) {
                close(socket);
                throw VotClient.failure("Concurrent transport requests are unsupported");
            }
            active = socket;
        }
    }

    @Override public void cancel() {
        Socket socket;
        synchronized (lock) { cancelled = true; socket = active; active = null; }
        close(socket);
    }

    private static void close(Socket socket) {
        if (socket != null) try { socket.close(); } catch (IOException ignored) { }
    }

    /** Parse a bounded HTTP response, exposed for deterministic host fixtures. Never follows redirects. */
    public static VotClient.Response readResponse(InputStream in, int maxBytes) throws IOException {
        if (in == null || maxBytes <= 0 || maxBytes > VotClient.SUBTITLE_LIMIT) throw malformed();
        Budget budget = new Budget();
        String statusLine = line(in,budget);
        if (!statusLine.matches("HTTP/1\\.[01] [1-5][0-9]{2}( [\\x20-\\x7e]*)?")) throw malformed();
        int status = Integer.parseInt(statusLine.substring(9,12));
        if (status < 200) throw malformed();
        Map<String,String> headers = headers(in,budget,false);
        String encoding = headers.get("content-encoding");
        if (encoding != null && !"identity".equalsIgnoreCase(encoding)) throw malformed();
        String transfer = headers.get("transfer-encoding"), length = headers.get("content-length");
        if (transfer != null && length != null) throw malformed();
        byte[] body;
        if (transfer != null) {
            if (!"chunked".equalsIgnoreCase(transfer)) throw malformed();
            body = chunked(in,maxBytes);
        } else if (length != null) {
            if (!length.matches("[0-9]{1,10}")) throw malformed();
            long count;
            try { count = Long.parseLong(length); } catch (NumberFormatException error) { throw malformed(); }
            if (count > maxBytes) throw malformed();
            body = exact(in,(int)count);
        } else {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int count;
            while ((count = in.read(buffer)) != -1) {
                if (count == 0) continue;
                if (count > maxBytes - out.size()) throw malformed();
                out.write(buffer,0,count);
            }
            body = out.toByteArray();
        }
        return new VotClient.Response(status,body);
    }

    private static byte[] chunked(InputStream in, int maxBytes) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int chunks = 0;
        Budget framing = new Budget(1024 * 1024);
        while (true) {
            // Bound framing too: tiny chunks must not permit an unbounded wire response.
            if (++chunks > 131072) throw malformed();
            String chunk = line(in,framing);
            int semicolon = chunk.indexOf(';');
            String size = semicolon < 0 ? chunk : chunk.substring(0,semicolon);
            if (!size.matches("[a-fA-F0-9]{1,8}")) throw malformed();
            long length;
            try { length = Long.parseLong(size,16); } catch (NumberFormatException error) { throw malformed(); }
            if (length > maxBytes - out.size()) throw malformed();
            if (length == 0) {
                headers(in,new Budget(),true);
                return out.toByteArray();
            }
            byte[] part = exact(in,(int)length);
            out.write(part,0,part.length);
            if (in.read() != '\r' || in.read() != '\n') throw malformed();
        }
    }

    private static byte[] exact(InputStream in, int length) throws IOException {
        byte[] result = new byte[length];
        int offset = 0;
        while (offset < length) {
            int count = in.read(result,offset,length - offset);
            if (count < 0) throw malformed();
            if (count == 0) continue;
            offset += count;
        }
        return result;
    }

    private static final class Budget {
        int bytes;
        final int max;
        Budget() { this(HEADER_LIMIT); }
        Budget(int max) { this.max = max; }
    }
    private static String line(InputStream in, Budget budget) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        while (true) {
            int value = in.read();
            if (value < 0 || ++budget.bytes > budget.max) throw malformed();
            if (value == '\r') {
                if (in.read() != '\n' || ++budget.bytes > budget.max) throw malformed();
                return new String(out.toByteArray(),StandardCharsets.US_ASCII);
            }
            if (value < 32 || value > 126 || out.size() >= LINE_LIMIT) throw malformed();
            out.write(value);
        }
    }

    private static Map<String,String> headers(InputStream in, Budget budget, boolean trailers) throws IOException {
        Map<String,String> headers = new LinkedHashMap<>();
        for (int count = 0; ; count++) {
            String value = line(in,budget);
            if (value.isEmpty()) return headers;
            if (count >= HEADER_COUNT) throw malformed();
            int colon = value.indexOf(':');
            if (colon <= 0 || !VotClient.headerName(value.substring(0,colon))) throw malformed();
            String name = value.substring(0,colon).toLowerCase(Locale.ROOT);
            boolean framingHeader = name.equals("content-length") || name.equals("transfer-encoding") || name.equals("content-encoding");
            if ((headers.containsKey(name) && framingHeader) || (trailers && framingHeader)) throw malformed();
            headers.put(name,value.substring(colon+1).trim());
        }
    }

    private static IOException malformed() { return VotClient.failure("Malformed or oversized HTTP response"); }
}
