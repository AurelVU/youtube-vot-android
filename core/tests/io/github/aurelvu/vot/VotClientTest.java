package io.github.aurelvu.vot;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import org.json.JSONObject;

public final class VotClientTest {
    private static int assertions;
    private static final String ID = "jNQXAC9IVRw";
    private static final byte[] SESSION = {10, 3, 'k', 'e', 'y', 16, (byte) 0xb0, 9};
    private interface Handler { VotClient.Response reply(VotClient.Request request) throws IOException; }
    private static final class Fake implements VotClient.Transport {
        final List<VotClient.Request> requests = new ArrayList<>();
        Handler handler;
        boolean cancelled;
        Fake(Handler handler) { this.handler = handler; }
        public VotClient.Response execute(VotClient.Request r) throws IOException { requests.add(r); return handler.reply(r); }
        public void cancel() { cancelled = true; }
    }
    public static void main(String[] args) throws Exception {
        sessionAndAuthScope(); workerFallback(); authenticationFailure(); audioFallback(); cancellation(); subtitles(); invalidArguments();
        System.out.println("VotClientTest passed (" + assertions + " assertions)");
    }
    private static void sessionAndAuthScope() throws Exception {
        Fake fake = new Fake(r -> response(r.uri.getPath().equals("/session/create") ? SESSION : ready()));
        VotClient client = new VotClient(fake);
        check(client.translate(ID, 19, "de", "en", true, "Session_id=private", true).status == 1, "authenticated ready translation");
        client.translate(ID, 19, "de", "en", true, "Session_id=private", false);
        check(fake.requests.size() == 3, "session reused across polling");
        check(!fake.requests.get(0).headers.containsKey("Cookie"), "session creation is unauthenticated");
        VotClient.Request translate = fake.requests.get(1);
        check("Session_id=private".equals(translate.headers.get("Cookie")), "only lively translation receives explicit cookie");
        check(translate.headers.get("Vtrans-Signature").equals(VotProtocol.signature(translate.body)), "request signature covers exact body");
        check(translate.headers.get("Sec-Vtrans-Sk").equals("key"), "session key becomes signed protocol metadata");
        Map<Integer,Object> fields = VotProtocol.decodeFields(translate.body);
        check("en".equals(new String((byte[]) fields.get(14), StandardCharsets.UTF_8)) && fields.get(18).equals(1L), "target and lively options encoded");
        client.translate(ID, 19, "de", "ru", false, "Session_id=private", false);
        check(!fake.requests.get(3).headers.containsKey("Cookie"), "classic translation ignores saved cookie");
    }
    private static void workerFallback() throws Exception {
        Fake fake = new Fake(r -> {
            if (r.uri.getHost().equals("api.browser.yandex.ru") && r.uri.getPath().contains("translate")) return new VotClient.Response(503, bytes("private server failure"));
            return response(r.uri.getPath().equals("/session/create") ? SESSION : ready());
        });
        VotClient client = new VotClient(fake);
        check(client.translate(ID, 19, "en", "ru", false, null, true).status == 1, "unauthenticated direct failure retries worker once");
        check(fake.requests.size() == 4, "worker fallback recreates session");
        for (VotClient.Request r : fake.requests) if (r.uri.getHost().equals("vot-worker.toil.cc")) {
            JSONObject embedded = new JSONObject(new String(Base64.getDecoder().decode(r.headers.get("X-VOT-Headers")), StandardCharsets.UTF_8));
            check(!r.headers.containsKey("Cookie") && !embedded.has("Cookie"), "worker header envelope has no cookie");
            check(embedded.getString("Vtrans-Signature").equals(VotProtocol.signature(r.body)), "worker retains upstream signature");
            check("vot.js/3.2.3".equals(r.headers.get("User-Agent")), "worker receives expected user agent");
        }
        int beforeAuth = fake.requests.size();
        reject(() -> client.translate(ID,19,"en","ru",true,"Session_id=private",true), "authenticated operation leaves prior worker route");
        check(fake.requests.size() == beforeAuth + 2 && fake.requests.subList(beforeAuth,fake.requests.size()).stream().allMatch(r -> r.uri.getHost().equals("api.browser.yandex.ru")), "authenticated retry recreates direct session and never returns to worker");
        Fake fails = new Fake(r -> r.uri.getPath().equals("/session/create") ? response(SESSION) : new VotClient.Response(503, bytes("private")));
        reject(() -> new VotClient(fails).translate(ID, 19, "en", "ru", false, null, true), "worker retry bounded");
        check(fails.requests.size() == 4, "worker error cannot loop");
    }
    private static void authenticationFailure() throws Exception {
        for (boolean failSession : new boolean[] {false, true}) {
            Fake fake = new Fake(r -> !failSession && r.uri.getPath().equals("/session/create") ? response(SESSION) : new VotClient.Response(400, bytes("{\"message\":\"private Session_id token\"}")));
            reject(() -> new VotClient(fake).translate(ID, 19, "en", "ru", true, "Session_id=private", true), "authenticated failure is sanitized");
            check(fake.requests.size() == (failSession ? 1 : 2), "authenticated failure never reaches worker");
            check(fake.requests.stream().allMatch(r -> r.uri.getHost().equals("api.browser.yandex.ru")), "authenticated routing stays direct");
        }
    }
    private static void audioFallback() throws Exception {
        Fake fake = new Fake(r -> {
            switch (r.uri.getPath()) {
                case "/session/create": return response(SESSION);
                case "/video-translation/translate": return response(join(new byte[] {32,6}, field(7, bytes("translation-id"))));
                case "/video-translation/fail-audio-js": return response(bytes("{\"status\":1}"));
                case "/video-translation/audio": return response(new byte[] {8,1});
                default: throw new IOException("Unexpected fixture path");
            }
        });
        VotClient client = new VotClient(fake);
        check(client.translate(ID, 19, "en", "ru", true, "Session_id=private", true).status == 6, "status six triggers audio retrieval");
        check(fake.requests.size() == 4, "audio fallback sends two helper requests");
        for (int i : new int[] {2,3}) check(!fake.requests.get(i).headers.containsKey("Cookie"), "audio helpers never receive session cookies");
        check(fake.requests.get(2).method.equals("PUT") && fake.requests.get(3).method.equals("PUT"), "audio retrieval uses PUT");
        check(new JSONObject(new String(fake.requests.get(2).body, StandardCharsets.UTF_8)).getString("video_url").equals("https://youtu.be/" + ID), "fallback JSON uses canonical video URL");
        client.translate(ID, 19, "en", "ru", true, "Session_id=private", false);
        check(fake.requests.size() == 5, "audio fallback runs once per translation ID");
    }
    private static void cancellation() throws Exception {
        VotClient[] ref = new VotClient[1];
        Fake fake = new Fake(r -> { ref[0].cancel(); return response(SESSION); });
        ref[0] = new VotClient(fake);
        reject(() -> ref[0].translate(ID, 19, "en", "ru", false, null, true), "cancelled response cannot be delivered");
        check(fake.cancelled && fake.requests.size() == 1, "cancel closes transport and does not fall back");
        reject(() -> ref[0].translate(ID, 19, "en", "ru", false, null, true), "cancel is permanent");
        check(fake.requests.size() == 1, "cancelled client cannot issue more requests");
    }
    private static void subtitles() throws Exception {
        byte[] track = join(field(1,bytes("en")),field(2,bytes("https://subtitles.example/en")),field(4,bytes("ru")),field(5,bytes("https://subtitles.example/ru")));
        Fake fake = new Fake(r -> {
            if (r.uri.getPath().equals("/session/create")) return response(SESSION);
            if (r.uri.getPath().equals("/video-subtitles/get-subtitles")) {
                check(r.headers.containsKey("Vsubs-Signature"), "subtitle API uses Vsubs signature family");
                return response(field(2,track));
            }
            check(r.uri.toString().equals("https://subtitles.example/ru"), "translated target track selected");
            check(!r.headers.containsKey("Cookie") && !r.headers.containsKey("Sec-Vsubs-Sk"), "CDN gets neither cookies nor protocol key");
            return response(bytes("{\"subtitles\":[{\"text\":\"second\",\"startMs\":2000,\"durationMs\":500},{\"text\":\"first\",\"startMs\":1000,\"durationMs\":1000}]}"));
        });
        List<VotClient.Cue> cues = new VotClient(fake).subtitles(ID,"en","ru");
        check(cues.size() == 2 && cues.get(0).text.equals("first") && cues.get(1).text.equals("second"), "cues sorted by start");
        check(cues.get(0).start == 1 && cues.get(0).end == 2 && cues.get(1).end == 2.5, "cue boundaries converted from milliseconds");
        reject(() -> VotClient.parseCues(new byte[] {(byte)0xff}), "invalid UTF-8 rejected before JSON");
        List<VotClient.Cue> zero = VotClient.parseCues(bytes("{\"subtitles\":[{\"text\":\"\",\"startMs\":0,\"durationMs\":0}]}"));
        check(zero.size() == 1 && zero.get(0).start == 0 && zero.get(0).end == 0, "zero-length cue boundaries remain valid");
        String[] invalid = {"{}", "{\"subtitles\":[{\"text\":\"x\",\"startMs\":true,\"durationMs\":1}]}", "{\"subtitles\":[{\"text\":\"x\",\"startMs\":-1,\"durationMs\":1}]}", "{\"subtitles\":[{\"text\":\"x\",\"startMs\":0,\"durationMs\":-1}]}", "{\"subtitles\":[{\"text\":\"x\",\"startMs\":1e309,\"durationMs\":1}]}"};
        for (String json : invalid) reject(() -> VotClient.parseCues(bytes(json)), "invalid cue rejected");
        reject(() -> VotClient.parseCues(new byte[8 * 1024 * 1024 + 1]), "subtitle body bound enforced before parsing");
        reject(() -> VotClient.parseCues(bytes("{\"subtitles\":[" + "{\"text\":\"x\",\"startMs\":0,\"durationMs\":1},".repeat(50000) + "{\"text\":\"x\",\"startMs\":0,\"durationMs\":1}]}")), "subtitle count bound enforced");
        Fake waiting = new Fake(r -> response(r.uri.getPath().equals("/session/create") ? SESSION : new byte[] {8,1}));
        VotClient pendingClient = new VotClient(waiting);
        try {
            pendingClient.subtitles(ID,"en","ru");
            throw new AssertionError("pending subtitles must remain distinguishable from ready empty cues");
        } catch (IOException pending) {
            check(pending instanceof VotClient.SubtitlesPendingException, "waiting response throws dedicated pending exception");
            check(pending.getCause() == null && "Subtitles are still being prepared".equals(pending.getMessage()), "pending exception has only safe constant diagnostics");
        }
        check(waiting.requests.size() == 2, "waiting tracks do not request CDN");
        waiting.handler = r -> response(r.uri.getPath().equals("/video-subtitles/get-subtitles") ? field(2,track) : bytes("{\"subtitles\":[]}"));
        check(pendingClient.subtitles(ID,"en","ru").isEmpty(), "ready empty subtitle track is a successful empty list");
        check(waiting.requests.size() == 4, "polling after pending reuses the session and fetches the ready track");
    }
    private static void invalidArguments() throws Exception {
        Fake fake = new Fake(r -> { throw new AssertionError("invalid request reached transport"); });
        VotClient client = new VotClient(fake);
        reject(() -> client.translate("invalid",19,"en","ru",false,null,true), "invalid video rejected locally");
        reject(() -> client.translate(ID,Double.NaN,"en","ru",false,null,true), "invalid duration rejected locally");
        reject(() -> client.translate(ID,19,"en","ru",true,"a\r\nb",true), "cookie control characters rejected locally");
        reject(() -> client.subtitles(ID,"en\r\n","ru"), "invalid language rejected locally");
    }
    private static VotClient.Response response(byte[] body) { return new VotClient.Response(200,body); }
    private static byte[] ready() { return join(field(1,bytes("https://audio.example/a.mp3")),new byte[] {32,1}); }
    private static byte[] bytes(String text) { return text.getBytes(StandardCharsets.UTF_8); }
    private static byte[] field(int field, byte[] value) { ByteArrayOutputStream out = new ByteArrayOutputStream(); varint(out,field*8+2); varint(out,value.length); out.write(value,0,value.length); return out.toByteArray(); }
    private static void varint(ByteArrayOutputStream out, int value) { while(value >= 128) { out.write((value & 127)|128); value >>>= 7; } out.write(value); }
    private static byte[] join(byte[]... parts) { ByteArrayOutputStream out = new ByteArrayOutputStream(); for(byte[] part:parts) out.write(part,0,part.length); return out.toByteArray(); }
    private interface Operation { void run() throws Exception; }
    private static void reject(Operation op, String message) throws Exception { try { op.run(); throw new AssertionError(message); } catch(IOException e) { check(!e.toString().contains("private") && e.getCause() == null, "exception does not expose server content or secret cause"); assertions++; } }
    private static void check(boolean condition, String message) { assertions++; if(!condition) throw new AssertionError(message); }
}
