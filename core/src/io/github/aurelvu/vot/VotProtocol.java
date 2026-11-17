/*
 * MIT License
 * Copyright (c) 2024 FOSWLY
 * Copyright (c) 2026 AurelVU
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
package io.github.aurelvu.vot;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Yandex VOT wire format, ported from YouTubeVOT's VOTProtocol.m and
 * FOSWLY/vot.js (https://github.com/FOSWLY/vot.js), MIT licensed.
 * The public client key is an upstream protocol constant, not an account secret.
 */
public final class VotProtocol {
    public static final String COMPONENT_VERSION = "26.11.0.1993";
    public static final String USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/154.0.0.0 YaBrowser/26.11.0.0 Safari/537.36";
    public static final String PUBLIC_PROTOCOL_KEY = "bt8xH3VOlb4mqf0nqAibnDOoiPlXsisf";
    private static final int MAX_RESPONSE_BYTES = 1024 * 1024;

    private VotProtocol() {}

    public static byte[] encodeSession(String uuid) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        string(out, 1, uuid);
        string(out, 2, "video-translation");
        return out.toByteArray();
    }

    public static byte[] encodeTranslation(String url, double duration, String source,
            String target, boolean first, boolean lively) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        string(out, 3, url);
        if (first) integer(out, 5, 1);
        varint(out, (6 << 3) | 1);
        long raw = Double.doubleToRawLongBits(duration);
        for (int i = 0; i < 8; i++) out.write((int) (raw >>> (i * 8)) & 0xff);
        integer(out, 7, 1);
        string(out, 8, source);
        if (!"auto".equals(source)) integer(out, 9, 1);
        string(out, 14, target);
        integer(out, 15, 1);
        integer(out, 16, 2);
        if (lively) integer(out, 18, 1);
        return out.toByteArray();
    }

    public static byte[] encodeSubtitles(String url, String source) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        string(out, 1, url);
        string(out, 2, source);
        return out.toByteArray();
    }

    public static byte[] encodeAudioFallback(String url, String translationId, String videoId) {
        require(videoId, "videoId");
        ByteArrayOutputStream audio = new ByteArrayOutputStream();
        string(audio, 1, "fallback-empty-audio:video-translation:" + videoId);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        string(out, 1, translationId);
        string(out, 2, url);
        bytes(out, 6, audio.toByteArray());
        return out.toByteArray();
    }

    /** Numbers are unsigned protobuf varints represented by their 64-bit Long bit pattern. */
    public static Map<Integer, Object> decodeFields(byte[] data) {
        return parse(data, 0).values;
    }

    public static Translation decodeTranslation(byte[] data) {
        Fields fields = parse(data, 0);
        long status = number(fields, 4, -1);
        if (status != 0 && status != 1 && status != 2 && status != 3
                && status != 5 && status != 6 && status != 7) throw malformed();
        long remaining = number(fields, 5, 5);
        if (remaining < 0 || remaining > Integer.MAX_VALUE) throw malformed();
        boolean lively = bool(fields, 10);
        String url = text(fields, 1, Integer.MAX_VALUE);
        String id = text(fields, 7, Integer.MAX_VALUE);
        text(fields, 8, Integer.MAX_VALUE);
        text(fields, 9, Integer.MAX_VALUE);
        if (status == 1 || status == 5) {
            if (url == null) throw malformed();
            try {
                URI uri = new URI(url);
                if (!"https".equals(uri.getScheme()) || uri.getHost() == null
                        || uri.getHost().isEmpty()) throw malformed();
            } catch (URISyntaxException e) {
                throw malformed();
            }
        }
        return new Translation((int) status, url, id, (int) remaining, lively);
    }

    public static SubtitleTracks decodeSubtitles(byte[] data) {
        Fields fields = parse(data, 2);
        boolean waiting = bool(fields, 1);
        List<Track> tracks = new ArrayList<>();
        for (byte[] trackData : fields.repeated) {
            Fields track = parse(trackData, 0);
            tracks.add(new Track(text(track, 1, 8192), text(track, 2, 8192),
                    text(track, 4, 8192), text(track, 5, 8192)));
        }
        return new SubtitleTracks(waiting, tracks);
    }

    public static String signature(byte[] body) {
        require(body, "body");
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(PUBLIC_PROTOCOL_KEY.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] digest = mac.doFinal(body);
            char[] output = new char[digest.length * 2];
            char[] digits = "0123456789abcdef".toCharArray();
            for (int i = 0; i < digest.length; i++) {
                output[i * 2] = digits[(digest[i] & 0xff) >>> 4];
                output[i * 2 + 1] = digits[digest[i] & 15];
            }
            return new String(output);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 is unavailable", e);
        }
    }

    public static Map<String, String> requestHeaders(byte[] body, String uuid, String secret, String path) {
        require(uuid, "uuid"); require(secret, "secret"); require(path, "path");
        String token = uuid + ":" + path + ":" + COMPONENT_VERSION;
        String prefix = path.startsWith("/video-subtitles/") ? "Vsubs" : "Vtrans";
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put(prefix + "-Signature", signature(body));
        headers.put("Sec-" + prefix + "-Sk", secret);
        headers.put("Sec-" + prefix + "-Token", signature(token.getBytes(StandardCharsets.UTF_8)) + ":" + token);
        return Collections.unmodifiableMap(headers);
    }

    private static void varint(ByteArrayOutputStream out, long value) {
        while ((value & ~0x7fL) != 0) {
            out.write(((int) value & 0x7f) | 0x80);
            value >>>= 7;
        }
        out.write((int) value);
    }

    private static void integer(ByteArrayOutputStream out, int field, long value) {
        varint(out, (long) field << 3);
        varint(out, value);
    }

    private static void bytes(ByteArrayOutputStream out, int field, byte[] value) {
        varint(out, ((long) field << 3) | 2);
        varint(out, value.length);
        out.write(value, 0, value.length);
    }

    private static void string(ByteArrayOutputStream out, int field, String value) {
        require(value, "string");
        bytes(out, field, value.getBytes(StandardCharsets.UTF_8));
    }

    private static final class Fields {
        final Map<Integer, Object> values = new LinkedHashMap<>();
        final Map<Integer, Integer> wireTypes = new LinkedHashMap<>();
        final List<byte[]> repeated = new ArrayList<>();
    }

    private static final class Cursor {
        final byte[] bytes;
        int offset;
        Cursor(byte[] bytes) { this.bytes = bytes; }
        long varint() {
            long value = 0;
            for (int i = 0; i < 10; i++) {
                if (offset >= bytes.length) throw malformed();
                int b = bytes[offset++] & 0xff;
                if (i == 9 && b > 1) throw malformed();
                value |= (long) (b & 0x7f) << (i * 7);
                if ((b & 0x80) == 0) return value;
            }
            throw malformed();
        }
    }

    private static Fields parse(byte[] data, int repeatedField) {
        require(data, "data");
        if (data.length > MAX_RESPONSE_BYTES) throw malformed();
        Fields fields = new Fields();
        Cursor cursor = new Cursor(data);
        while (cursor.offset < data.length) {
            long tag = cursor.varint();
            long fieldNumber = tag >>> 3;
            if (fieldNumber == 0 || fieldNumber > 0x1fffffff) throw malformed();
            int key = (int) fieldNumber;
            int wireType = (int) (tag & 7);
            Object value;
            if (wireType == 0) {
                value = cursor.varint();
            } else if (wireType == 1 || wireType == 2 || wireType == 5) {
                long length = wireType == 2 ? cursor.varint() : wireType == 1 ? 8 : 4;
                if (length < 0 || length > data.length - cursor.offset) throw malformed();
                value = Arrays.copyOfRange(data, cursor.offset, cursor.offset + (int) length);
                cursor.offset += (int) length;
            } else {
                throw malformed();
            }
            if (key == repeatedField) {
                if (wireType != 2 || fields.repeated.size() >= 128) throw malformed();
                fields.repeated.add((byte[]) value);
            } else {
                fields.values.put(key, value);
                fields.wireTypes.put(key, wireType);
            }
        }
        return fields;
    }

    private static long number(Fields fields, int key, long fallback) {
        if (!fields.values.containsKey(key)) return fallback;
        if (fields.wireTypes.get(key) != 0) throw malformed();
        return (Long) fields.values.get(key);
    }

    private static boolean bool(Fields fields, int key) {
        long value = number(fields, key, 0);
        if (value != 0 && value != 1) throw malformed();
        return value == 1;
    }

    private static String text(Fields fields, int key, int maxLength) {
        if (!fields.values.containsKey(key)) return null;
        if (fields.wireTypes.get(key) != 2) throw malformed();
        try {
            String value = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap((byte[]) fields.values.get(key))).toString();
            if (value.length() > maxLength) throw malformed();
            return value;
        } catch (CharacterCodingException e) {
            throw malformed();
        }
    }

    private static void require(Object value, String name) {
        if (value == null) throw new IllegalArgumentException(name + " is required");
    }

    private static IllegalArgumentException malformed() {
        return new IllegalArgumentException("Malformed VOT response");
    }
    public static final class Translation {
        public final int status;
        public final String url;
        public final String translationId;
        public final int remainingTime;
        public final boolean lively;
        public Translation(int status, String url, String translationId, int remainingTime, boolean lively) {
            this.status = status;
            this.url = url;
            this.translationId = translationId;
            this.remainingTime = remainingTime;
            this.lively = lively;
        }
    }
    public static final class SubtitleTracks {
        public final boolean waiting;
        public final List<Track> tracks;
        public SubtitleTracks(boolean waiting, List<Track> tracks) {
            this.waiting = waiting;
            this.tracks = Collections.unmodifiableList(new ArrayList<>(tracks));
        }
    }
    public static final class Track {
        public final String language;
        public final String url;
        public final String translatedLanguage;
        public final String translatedUrl;
        public Track(String language, String url, String translatedLanguage, String translatedUrl) {
            this.language = language;
            this.url = url;
            this.translatedLanguage = translatedLanguage;
            this.translatedUrl = translatedUrl;
        }
    }
}
