package io.github.aurelvu.vot;

import java.io.IOException;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.json.JSONArray;
import org.json.JSONObject;

/** Synchronous VOT client. Call operations serially on a worker; cancel is safe from any thread. */
public final class VotClient {
    private static final String API = "https://api.browser.yandex.ru";
    private static final String WORKER = "https://vot-worker.toil.cc";
    private static final String TRANSLATE = "/video-translation/translate";
    private static final int PROTO_LIMIT = 1024 * 1024;
    public static final int SUBTITLE_LIMIT = 8 * 1024 * 1024;
    private final Transport transport;
    private final Set<String> audioFallbacks = new HashSet<>();
    private volatile boolean cancelled;
    private String endpoint = API;
    private String uuid;
    private String secret;
    private long expiresAt;

    public interface Transport {
        Response execute(Request request) throws IOException;
        void cancel();
    }

    /** Validated request snapshot. Sensitive headers never appear in diagnostics. */
    public static final class Request {
        public final URI uri;
        public final String method;
        public final Map<String, String> headers;
        public final byte[] body;
        public final int maxBytes;

        public Request(URI uri, String method, Map<String, String> headers, byte[] body, int maxBytes) throws IOException {
            validateUri(uri);
            if (!("GET".equals(method) || "POST".equals(method) || "PUT".equals(method))
                    || headers == null || body == null || body.length > PROTO_LIMIT
                    || maxBytes <= 0 || maxBytes > SUBTITLE_LIMIT) throw failure("Invalid network request");
            Map<String, String> copy = new LinkedHashMap<>();
            Set<String> names = new HashSet<>();
            int headerBytes = 0;
            if (headers.size() > 128) throw failure("Too many request headers");
            for (Map.Entry<String,String> entry : headers.entrySet()) {
                String name = entry.getKey(), value = entry.getValue();
                if (!headerName(name) || !headerValue(value)) throw failure("Invalid request header");
                headerBytes += name.length() + value.length() + 4;
                if (headerBytes > 32768) throw failure("Request headers are too large");
                String lower = name.toLowerCase(Locale.ROOT);
                if (!names.add(lower) || lower.equals("host") || lower.equals("content-length")
                        || lower.equals("transfer-encoding") || lower.equals("connection")
                        || lower.equals("accept-encoding") || lower.equals("authorization")
                        || lower.equals("proxy-authorization")) throw failure("Forbidden request header");
                if (lower.equals("cookie") && !("api.browser.yandex.ru".equalsIgnoreCase(uri.getHost())
                        && TRANSLATE.equals(uri.getRawPath()) && uri.getRawQuery() == null))
                    throw failure("Credentials are restricted to the translation service");
                copy.put(name,value);
            }
            this.uri = uri;
            this.method = method;
            this.headers = Collections.unmodifiableMap(copy);
            this.body = body.clone();
            this.maxBytes = maxBytes;
        }
    }

    public static final class Response {
        public final int status;
        public final byte[] body;
        public Response(int status, byte[] body) {
            this.status = status;
            this.body = body == null ? null : body.clone();
        }
    }

    /** The service has accepted subtitle preparation; poll the same client again later. */
    public static final class SubtitlesPendingException extends IOException {
        private static final long serialVersionUID = 1L;
        public SubtitlesPendingException() {
            super("Subtitles are still being prepared");
        }
    }

    public static final class Cue {
        public final String text;
        public final double start;
        public final double end;
        public Cue(String text, double start, double end) {
            this.text = text;
            this.start = start;
            this.end = end;
        }
    }

    public VotClient() { this(new StatelessHttp()); }
    public VotClient(Transport transport) {
        if (transport == null) throw new IllegalArgumentException("Transport is required");
        this.transport = transport;
    }

    /** Permanently prevent new requests and discard any response already in flight. */
    public void cancel() {
        cancelled = true;
        transport.cancel();
    }

    public VotProtocol.Translation translate(String videoId, double durationSeconds, String source,
            String target, boolean lively, String cookie, boolean first) throws IOException {
        checkActive();
        validateVideo(videoId); validateLanguage(source); validateLanguage(target);
        if (!Double.isFinite(durationSeconds) || durationSeconds <= 0 || durationSeconds > 14400)
            throw failure("Video duration must be within four hours");
        String auth = lively && cookie != null ? cookie : "";
        if (auth.length() > 16384 || !headerValue(auth)) throw failure("Invalid Yandex cookie format");
        if (!auth.isEmpty() && !API.equals(endpoint)) switchEndpoint(API);
        try {
            return translateOnce(videoId, durationSeconds, source, target, lively, auth, first);
        } catch (IOException error) {
            checkActive();
            if (!auth.isEmpty() || WORKER.equals(endpoint)) throw error;
            switchEndpoint(WORKER);
            return translateOnce(videoId, durationSeconds, source, target, lively, "", first);
        }
    }

    private VotProtocol.Translation translateOnce(String videoId, double duration, String source,
            String target, boolean lively, String cookie, boolean first) throws IOException {
        try {
            ensureSession();
            String url = "https://youtu.be/" + videoId;
            byte[] body = VotProtocol.encodeTranslation(url,duration,source,target,first,lively);
            VotProtocol.Translation result = decodeTranslation(request(TRANSLATE,body,
                    VotProtocol.requestHeaders(body,uuid,secret,TRANSLATE),"POST",cookie));
            if (result.status == 6 && result.translationId != null && !result.translationId.isEmpty()
                    && !audioFallbacks.contains(result.translationId)) {
                audioFallback(url,videoId,result.translationId);
                audioFallbacks.add(result.translationId);
            }
            if (result.status == 1 || result.status == 5) validateUri(safeUri(result.url));
            checkActive();
            return result;
        } catch (IOException error) {
            secret = null;
            throw error;
        } catch (RuntimeException error) {
            secret = null;
            throw failure("Malformed translation response");
        }
    }

    private void audioFallback(String url, String videoId, String translationId) throws IOException {
        byte[] json;
        try { json = new JSONObject().put("video_url",url).toString().getBytes(StandardCharsets.UTF_8); }
        catch (Exception error) { throw failure("Unable to prepare audio retrieval"); }
        byte[] response = request("/video-translation/fail-audio-js",json,
                headers("Accept","application/json","Content-Type","application/json"),"PUT","");
        try {
            Object status = new JSONObject(utf8(response)).opt("status");
            if (!(status instanceof Number) || ((Number)status).doubleValue() != 1)
                throw failure("Audio retrieval was not accepted");
        } catch (Exception error) { throw failure("Audio retrieval was not accepted"); }
        String path = "/video-translation/audio";
        byte[] body = VotProtocol.encodeAudioFallback(url,translationId,videoId);
        try {
            Map<Integer,Object> fields = VotProtocol.decodeFields(request(path,body,
                    VotProtocol.requestHeaders(body,uuid,secret,path),"PUT",""));
            if (!(fields.get(1) instanceof Long)) throw failure("Audio retrieval was not accepted");
        } catch (RuntimeException error) { throw failure("Malformed audio retrieval response"); }
    }

    public List<Cue> subtitles(String videoId, String source, String target) throws IOException {
        checkActive(); validateVideo(videoId); validateLanguage(source); validateLanguage(target);
        byte[] tracks;
        try { tracks = subtitleTracks(videoId,source); }
        catch (IOException error) {
            checkActive();
            if (WORKER.equals(endpoint)) throw error;
            switchEndpoint(WORKER);
            tracks = subtitleTracks(videoId,source);
        }
        VotProtocol.SubtitleTracks decoded;
        try { decoded = VotProtocol.decodeSubtitles(tracks); }
        catch (RuntimeException error) { throw failure("Malformed subtitle track response"); }
        if (decoded.waiting) { checkActive(); throw new SubtitlesPendingException(); }
        String selected = null;
        for (VotProtocol.Track track : decoded.tracks)
            if (target.equals(track.translatedLanguage) && track.translatedUrl != null && !track.translatedUrl.isEmpty()) { selected = track.translatedUrl; break; }
        if (selected == null) for (VotProtocol.Track track : decoded.tracks)
            if (target.equals(track.language) && track.url != null && !track.url.isEmpty()) { selected = track.url; break; }
        if (selected == null) throw failure("Subtitles are not available in this language");
        URI uri = safeUri(selected);
        Request request = new Request(uri,"GET",headers("Accept","application/json","User-Agent",VotProtocol.USER_AGENT),new byte[0],SUBTITLE_LIMIT);
        List<Cue> cues = parseCues(execute(request));
        checkActive();
        return cues;
    }

    private byte[] subtitleTracks(String videoId, String source) throws IOException {
        try {
            ensureSession();
            String path = "/video-subtitles/get-subtitles";
            byte[] body = VotProtocol.encodeSubtitles("https://youtu.be/" + videoId,source);
            return request(path,body,VotProtocol.requestHeaders(body,uuid,secret,path),"POST","");
        } catch (IOException error) { secret = null; throw error; }
    }

    private void ensureSession() throws IOException {
        checkActive();
        if (secret != null && expiresAt - System.nanoTime() > 30_000_000_000L) return;
        uuid = UUID.randomUUID().toString().replace("-","").toUpperCase(Locale.ROOT);
        byte[] body = VotProtocol.encodeSession(uuid);
        byte[] response = request("/session/create",body,headers("Vtrans-Signature",VotProtocol.signature(body)),"POST","");
        try {
            Map<Integer,Object> fields = VotProtocol.decodeFields(response);
            if (!(fields.get(1) instanceof byte[]) || !(fields.get(2) instanceof Long)) throw failure("Invalid translation session");
            byte[] key = (byte[]) fields.get(1);
            long ttl = (Long) fields.get(2);
            if (key.length == 0 || key.length > 4096 || ttl < 1) throw failure("Invalid translation session");
            String value = utf8(key);
            if (!headerValue(value)) throw failure("Invalid translation session");
            secret = value;
            expiresAt = System.nanoTime() + Math.min(ttl,86400) * 1_000_000_000L;
        } catch (RuntimeException error) { throw failure("Malformed translation session"); }
    }

    private byte[] request(String path, byte[] body, Map<String,String> extra, String method, String cookie) throws IOException {
        Map<String,String> headers = new LinkedHashMap<>();
        headers.put("User-Agent",VotProtocol.USER_AGENT);
        headers.put("Accept","application/x-protobuf");
        headers.put("Accept-Language","en");
        headers.put("Content-Type","application/x-protobuf");
        headers.put("Cache-Control","no-cache");
        headers.putAll(extra);
        if (!cookie.isEmpty()) {
            if (!API.equals(endpoint) || !TRANSLATE.equals(path)) throw failure("Credentials are restricted to the translation service");
            headers.put("Cookie",cookie);
        }
        if (WORKER.equals(endpoint)) {
            if (headers.containsKey("Cookie")) throw failure("Authenticated worker requests are forbidden");
            String encoded;
            try { encoded = Base64.getEncoder().encodeToString(new JSONObject(headers).toString().getBytes(StandardCharsets.UTF_8)); }
            catch (Exception error) { throw failure("Unable to prepare worker request"); }
            headers = new LinkedHashMap<>(headers("User-Agent","vot.js/3.2.3","X-VOT-Headers",encoded,"Content-Type",headers.get("Content-Type")));
        }
        return execute(new Request(URI.create(endpoint + path),method,headers,body,PROTO_LIMIT));
    }

    private byte[] execute(Request request) throws IOException {
        checkActive();
        Response response;
        try { response = transport.execute(request); }
        catch (Exception error) { checkActive(); throw failure("Translation network request failed"); }
        checkActive();
        if (response == null || response.status != 200) throw failure("Translation service HTTP " + (response == null ? 0 : response.status));
        if (response.body == null || response.body.length == 0 || response.body.length > request.maxBytes)
            throw failure("Invalid translation response size");
        return response.body;
    }

    public static List<Cue> parseCues(byte[] data) throws IOException {
        if (data == null || data.length == 0 || data.length > SUBTITLE_LIMIT) throw failure("Invalid subtitle response size");
        try {
            JSONObject json = new JSONObject(utf8(data));
            Object raw = json.opt("subtitles");
            if (!(raw instanceof JSONArray)) throw failure("Malformed subtitles");
            JSONArray items = (JSONArray) raw;
            if (items.length() > 50000) throw failure("Too many subtitle cues");
            List<Cue> cues = new ArrayList<>(items.length());
            for (int i = 0; i < items.length(); i++) {
                Object rawItem = items.get(i);
                if (!(rawItem instanceof JSONObject)) throw failure("Malformed subtitle cue");
                JSONObject item = (JSONObject) rawItem;
                Object text = item.opt("text"), start = item.opt("startMs"), duration = item.opt("durationMs");
                if (!(text instanceof String) || ((String)text).length() > 16384
                        || !(start instanceof Number) || !(duration instanceof Number)) throw failure("Malformed subtitle cue");
                double begin = ((Number)start).doubleValue() / 1000;
                double length = ((Number)duration).doubleValue() / 1000;
                double end = begin + length;
                if (!Double.isFinite(begin) || !Double.isFinite(length) || !Double.isFinite(end) || begin < 0 || length < 0)
                    throw failure("Invalid subtitle timing");
                cues.add(new Cue((String)text,begin,end));
            }
            cues.sort(Comparator.comparingDouble(cue -> cue.start));
            return Collections.unmodifiableList(cues);
        } catch (Exception error) { throw failure("Malformed subtitle response"); }
    }

    static void validateUri(URI uri) throws IOException {
        if (uri == null || !"https".equals(uri.getScheme()) || uri.getHost() == null || uri.getHost().isEmpty()
                || uri.getRawUserInfo() != null || (uri.getPort() != -1 && uri.getPort() != 443)
                || uri.getRawFragment() != null) throw failure("Only credential-free HTTPS URLs on port 443 are allowed");
    }
    private static URI safeUri(String value) throws IOException {
        try { return URI.create(value); }
        catch (RuntimeException error) { throw failure("Malformed service URL"); }
    }
    static boolean headerName(String name) {
        if (name == null || name.isEmpty() || name.length() > 128) return false;
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (!((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || "!#$%&'*+-.^_`|~".indexOf(c) >= 0)) return false;
        }
        return true;
    }
    static boolean headerValue(String value) {
        if (value == null || value.length() > 32768) return false;
        for (int i = 0; i < value.length(); i++) if (value.charAt(i) < 32 || value.charAt(i) > 126) return false;
        return true;
    }
    private void checkActive() throws IOException { if (cancelled) throw failure("Translation request cancelled"); }
    private void switchEndpoint(String value) { endpoint = value; secret = null; }
    private static void validateVideo(String id) throws IOException { if (id == null || !id.matches("[A-Za-z0-9_-]{11}")) throw failure("Invalid YouTube video identifier"); }
    private static void validateLanguage(String language) throws IOException { if (language == null || !language.matches("[A-Za-z-]{1,16}")) throw failure("Invalid translation language"); }
    private static VotProtocol.Translation decodeTranslation(byte[] data) throws IOException { try { return VotProtocol.decodeTranslation(data); } catch (RuntimeException error) { throw failure("Malformed translation response"); } }
    private static String utf8(byte[] bytes) throws IOException {
        try { return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString(); }
        catch (CharacterCodingException error) { throw failure("Malformed UTF-8 response"); }
    }
    private static Map<String,String> headers(String... pairs) {
        Map<String,String> result = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) result.put(pairs[i],pairs[i+1]);
        return result;
    }
    static IOException failure(String message) { return new IOException(message); }
}
