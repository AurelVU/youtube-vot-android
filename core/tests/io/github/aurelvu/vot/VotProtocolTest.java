package io.github.aurelvu.vot;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/** Host fixtures ported from YouTubeVOT ProtocolTests.m; wire/HMAC literals are independent. */
public final class VotProtocolTest {
    private static int assertions;
    private static int failures;

    public static void main(String[] args) {
        run("session wire", VotProtocolTest::session);
        run("translation wire", VotProtocolTest::translation);
        run("subtitles and fallback wire", VotProtocolTest::requests);
        run("HMAC and canonical headers", VotProtocolTest::headers);
        run("numeric and raw fields", VotProtocolTest::fields);
        run("translation statuses and defaults", VotProtocolTest::responses);
        run("translation malformed responses", VotProtocolTest::invalidResponses);
        run("repeated subtitle tracks", VotProtocolTest::subtitles);
        run("subtitle malformed responses", VotProtocolTest::invalidSubtitles);
        run("parser malformed and bounded inputs", VotProtocolTest::invalidFields);
        System.out.println("VotProtocolTest: " + assertions + " assertions; " + failures + " failed groups");
        if (failures != 0) throw new AssertionError("Protocol test groups failed: " + failures);
    }

    private static void session() {
        bytes("0a0241421211766964656f2d7472616e736c6174696f6e", VotProtocol.encodeSession("AB"));
    }

    private static void translation() {
        // Python struct.pack('<d', 9), independent protobuf field layout.
        bytes("1a0175280131000000000000224038014202646548017202656e7801800102900101",
                VotProtocol.encodeTranslation("u", 9, "de", "en", true, true));
        bytes("1a0175310000000000002240380142046175746f720272757801800102",
                VotProtocol.encodeTranslation("u", 9, "auto", "ru", false, false));
    }

    private static void requests() {
        bytes("0a017512026465", VotProtocol.encodeSubtitles("u", "de"));
        bytes("0a02696412017532340a3266616c6c6261636b2d656d7074792d617564696f3a766964656f2d7472616e736c6174696f6e3a6162636465666768696a6b",
                VotProtocol.encodeAudioFallback("u", "id", "abcdefghijk"));
    }

    private static void headers() {
        byte[] body = "test".getBytes(StandardCharsets.UTF_8);
        String sig = "ef02ef65d4b0c97334b2e2840177ba354f5b7e530200636dfe5b969eda9442e4";
        equal(sig, VotProtocol.signature(body));
        Map<String, String> h = VotProtocol.requestHeaders(body, "AB", "secret", "/video-translation/translate");
        equal(Map.of("Vtrans-Signature", sig, "Sec-Vtrans-Sk", "secret", "Sec-Vtrans-Token",
                "0f21dfac238861c0cd8ffeb3f34976b3bf2a31ca4978c646d3cb76d9982ebee3:AB:/video-translation/translate:26.11.0.1993"), h);
        equal(Map.of("Vsubs-Signature", sig, "Sec-Vsubs-Sk", "secret", "Sec-Vsubs-Token",
                "b2d71e58e88e8fff62d2a932cd38e91f6eccacc9d8e4b9bff88fe75f10b35ab1:AB:/video-subtitles/get-subtitles:26.11.0.1993"),
                VotProtocol.requestHeaders(body, "AB", "secret", "/video-subtitles/get-subtitles"));
    }

    private static void fields() {
        Map<Integer, Object> f = VotProtocol.decodeFields(hex("0801089601120261621900010203040506072500010203a00601"));
        equal(150L, f.get(1));
        bytes("6162", (byte[]) f.get(2));
        bytes("0001020304050607", (byte[]) f.get(3));
        bytes("00010203", (byte[]) f.get(4));
        equal(1L, f.get(100));
        equal(-1L, VotProtocol.decodeFields(hex("08ffffffffffffffffff01")).get(1));
        equal(1L, VotProtocol.decodeFields(hex("f8ffffff0f01")).get(536870911));
        equal(Map.of(), VotProtocol.decodeFields(new byte[0]));
    }

    private static void responses() {
        VotProtocol.Translation ready = VotProtocol.decodeTranslation(hex("0a0968747470733a2f2f6120013a026964"));
        equal(1, ready.status); equal("https://a", ready.url); equal("id", ready.translationId);
        equal(5, ready.remainingTime); equal(false, ready.lively);
        equal(true, VotProtocol.decodeTranslation(hex("0a0968747470733a2f2f6120055001")).lively);
        VotProtocol.Translation waiting = VotProtocol.decodeTranslation(hex("200228783a026964"));
        equal(2, waiting.status); equal(120, waiting.remainingTime); equal(null, waiting.url);
        for (String status : List.of("2000", "2002", "2003", "2006", "2007")) {
            equal(Integer.parseInt(status.substring(2), 16), VotProtocol.decodeTranslation(hex(status)).status);
        }
        equal(0, VotProtocol.decodeTranslation(hex("20022800")).remainingTime);
        // Optional language/message fields are still type- and UTF-8-checked.
        equal(2, VotProtocol.decodeTranslation(hex("20024202656e4a04d182d0b5")).status);
    }

    private static void invalidResponses() {
        for (String bad : List.of("", "0a80", "0a1061", "208080808080808080808080", "00", "2300", "220161", "2001", "2063",
                "0a0968747470733a2f2f612001520161", "20025002", "2002288080808008", "20022a00", "20023801", "20024201ff", "20024a01ff", "20020a01ff",
                "0a08687474703a2f2f612001", "0a0868747470733a2f2f2005")) {
            rejects(() -> VotProtocol.decodeTranslation(hex(bad)));
        }
    }

    private static void subtitles() {
        VotProtocol.SubtitleTracks subs = VotProtocol.decodeSubtitles(hex("080112070a02656e12016112070a027275120162"));
        equal(true, subs.waiting); equal(2, subs.tracks.size());
        equal("en", subs.tracks.get(0).language); equal("b", subs.tracks.get(1).url);
        VotProtocol.Track translated = VotProtocol.decodeSubtitles(hex("12120a02656e120161220272752a056874747073")).tracks.get(0);
        equal("ru", translated.translatedLanguage); equal("https", translated.translatedUrl);
        VotProtocol.SubtitleTracks empty = VotProtocol.decodeSubtitles(new byte[0]);
        equal(false, empty.waiting); equal(0, empty.tracks.size());
        equal(null, VotProtocol.decodeSubtitles(hex("1200")).tracks.get(0).language);
        byte[] tracks = new byte[256];
        for (int i = 0; i < tracks.length; i += 2) tracks[i] = 0x12;
        equal(128, VotProtocol.decodeSubtitles(tracks).tracks.size());
    }

    private static void invalidSubtitles() {
        for (String bad : List.of("12010a", "0802", "0a00", "1001", "12020801", "12030a01ff", "12022a80", "12022200ff")) {
            rejects(() -> VotProtocol.decodeSubtitles(hex(bad)));
        }
        byte[] tracks = new byte[258];
        for (int i = 0; i < tracks.length; i += 2) tracks[i] = 0x12;
        rejects(() -> VotProtocol.decodeSubtitles(tracks));
        // A length-delimited track with 8193 ASCII characters exceeds the iOS bound.
        byte[] longTrack = new byte[8199];
        System.arraycopy(hex("1284400a8140"), 0, longTrack, 0, 6);
        Arrays.fill(longTrack, 6, longTrack.length, (byte) 'a');
        rejects(() -> VotProtocol.decodeSubtitles(longTrack));
    }

    private static void invalidFields() {
        for (String bad : List.of("00", "01", "06", "07", "0b", "0c", "08", "0a80", "0a1061", "0900", "0d0000",
                "088080808080808080808002", "0880808080808080808080", "808080801001", "0affffffffffffffffff01")) {
            rejects(() -> VotProtocol.decodeFields(hex(bad)));
        }
        rejects(() -> VotProtocol.decodeFields(new byte[1024 * 1024 + 1]));
        byte[] boundary = new byte[1024 * 1024];
        // Field 1, bytes length 1048572: total exactly 1 MiB.
        System.arraycopy(hex("0afcff3f"), 0, boundary, 0, 4);
        equal(1048572, ((byte[]) VotProtocol.decodeFields(boundary).get(1)).length);
    }

    private static void run(String name, Runnable test) {
        try { test.run(); } catch (Throwable e) { failures++; System.err.println("FAIL " + name + ": " + e); }
    }
    private static void rejects(Runnable action) {
        assertions++;
        try { action.run(); } catch (IllegalArgumentException expected) { return; }
        throw new AssertionError("Expected IllegalArgumentException");
    }
    private static void bytes(String expected, byte[] actual) {
        assertions++;
        if (!Arrays.equals(hex(expected), actual)) throw new AssertionError("Wire mismatch; expected " + expected);
    }
    private static void equal(Object expected, Object actual) {
        assertions++;
        if (!java.util.Objects.equals(expected, actual)) throw new AssertionError("Expected " + expected + ", got " + actual);
    }
    private static byte[] hex(String value) {
        byte[] bytes = new byte[value.length() / 2];
        for (int i = 0; i < bytes.length; i++) bytes[i] = (byte) Integer.parseInt(value.substring(i * 2, i * 2 + 2), 16);
        return bytes;
    }
}
