package io.github.aurelvu.vot;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.LinkedHashMap;

public final class StatelessHttpTest {
    private static int assertions;
    public static void main(String[] args) throws Exception {
        body("HTTP/1.1 200 OK\r\nContent-Length: 3\r\n\r\nabc", 3, "abc");
        body("HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n2;x=y\r\nab\r\n1\r\nc\r\n0\r\nX-Note: fine\r\n\r\n", 3, "abc");
        body("HTTP/1.0 200 OK\r\n\r\nabc", 3, "abc");
        body("HTTP/1.1 200 OK\r\nSet-Cookie: first=private\r\nSet-Cookie: second=private\r\nContent-Length: 3\r\n\r\nabc", 3, "abc");
        String[] malformed = {
            "HTTP/1.1 200 OK\nContent-Length: 0\n\n",
            "HTTP/1.1 200 OK\r\nContent-Length: -1\r\n\r\n",
            "HTTP/1.1 200 OK\r\nContent-Length: +1\r\n\r\na",
            "HTTP/1.1 200 OK\r\nContent-Length: 1\r\nContent-Length: 1\r\n\r\na",
            "HTTP/1.1 200 OK\r\nContent-Length: 1\r\nTransfer-Encoding: chunked\r\n\r\n",
            "HTTP/1.1 200 OK\r\nContent-Encoding: gzip\r\n\r\n",
            "HTTP/1.1 200 OK\r\nTransfer-Encoding: gzip, chunked\r\n\r\n",
            "HTTP/1.1 200 OK\r\nContent-Length: 4\r\n\r\nabc",
            "HTTP/1.1 200 OK\r\nContent-Length: 3\r\n\r\nab",
            "HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n4\r\nabcd\r\n0\r\n\r\n",
            "HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n+1\r\na\r\n0\r\n\r\n",
            "HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n1\r\na!\n0\r\n\r\n",
            "HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n1\r\na\r\n0\r\nContent-Length: 9\r\n\r\n",
            "HTTP/1.1 200 OK\r\n Bad: folded\r\n\r\n",
            "HTTP/1.1 200 OK\r\nX: " + "z".repeat(8192) + "\r\n\r\n",
            "HTTP/1.1 200 OK\r\n" + "X: a\r\n".repeat(129) + "\r\n",
            "HTTP/1.1 200 OK\r\n\r\nabcd"
        };
        for (String value : malformed) reject(() -> StatelessHttp.readResponse(stream(value), 3), "malformed HTTP rejected");
        reject(() -> StatelessHttp.readResponse(stream("HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n" + ("1;" + "x".repeat(8100) + "\r\na\r\n").repeat(130) + "0\r\n\r\n"), 1024), "chunk framing is cumulatively bounded");
        VotClient.Response redirect = StatelessHttp.readResponse(stream("HTTP/1.1 302 Found\r\nLocation: https://other.example/\r\nContent-Length: 0\r\n\r\n"), 3);
        check(redirect.status == 302, "transport reports redirect without following it");
        reject(() -> request("http://api.browser.yandex.ru/video-translation/translate", Map.of()), "HTTP forbidden");
        reject(() -> request("https://user:password@api.browser.yandex.ru/video-translation/translate", Map.of()), "userinfo forbidden");
        reject(() -> request("https://api.browser.yandex.ru:444/video-translation/translate", Map.of()), "nonstandard port forbidden");
        reject(() -> request("https://api.browser.yandex.ru/video-translation/translate#fragment", Map.of()), "fragment forbidden");
        reject(() -> request("https://vot-worker.toil.cc/video-translation/translate", Map.of("Cookie", "Session_id=private")), "worker never receives cookie");
        reject(() -> request("https://api.browser.yandex.ru/session/create", Map.of("cookie", "Session_id=private")), "helper never receives cookie");
        reject(() -> request("https://subtitles.example/subs.json", Map.of("Authorization", "private")), "authorization forbidden");
        reject(() -> request("https://api.browser.yandex.ru/video-translation/translate", Map.of("X-Test", "x\r\nCookie: private")), "header injection forbidden");
        reject(() -> request("https://api.browser.yandex.ru/video-translation/translate", Map.of("Host", "evil.example")), "caller cannot override routing");
        Map<String,String> manyHeaders = new LinkedHashMap<>();
        for (int i=0; i<129; i++) manyHeaders.put("X-" + i,"a");
        reject(() -> request("https://api.browser.yandex.ru/video-translation/translate", manyHeaders), "request header count bounded");
        reject(() -> request("https://api.browser.yandex.ru/video-translation/translate", Map.of("X-Large", "a".repeat(32768))), "total request header size bounded");
        request("https://api.browser.yandex.ru:443/video-translation/translate", Map.of("Cookie", "Session_id=private"));
        check(true, "exact HTTPS translation endpoint accepts explicit cookie");
        System.out.println("StatelessHttpTest passed (" + assertions + " assertions)");
    }
    private static void request(String uri, Map<String,String> headers) throws IOException {
        new VotClient.Request(URI.create(uri), "POST", headers, new byte[0], 1024);
    }
    private static void body(String raw, int limit, String expected) throws IOException {
        VotClient.Response response = StatelessHttp.readResponse(stream(raw), limit);
        check(response.status == 200 && expected.equals(new String(response.body, StandardCharsets.US_ASCII)), "bounded HTTP body decoded");
    }
    private static ByteArrayInputStream stream(String value) { return new ByteArrayInputStream(value.getBytes(StandardCharsets.US_ASCII)); }
    private interface Operation { void run() throws Exception; }
    private static void reject(Operation op, String message) throws Exception {
        try { op.run(); throw new AssertionError(message); }
        catch (IOException expected) { check(!expected.toString().contains("private"), "error hides header secrets"); assertions++; }
    }
    private static void check(boolean condition, String message) { assertions++; if (!condition) throw new AssertionError(message); }
}
