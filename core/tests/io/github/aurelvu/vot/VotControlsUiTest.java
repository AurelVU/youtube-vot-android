package io.github.aurelvu.vot;

public final class VotControlsUiTest {
    private static int assertions;
    private static void check(boolean value, String message) {
        assertions++;
        if (!value) throw new AssertionError(message);
    }
    public static void main(String[] arguments) {
        var portrait = VotControlsUi.place(360, 203, 1f, 0, 0, 0, 0, true, false);
        check(portrait.visible, "ordinary portrait player shows the control group");
        check(portrait.left == 12 && portrait.top == 50, "iPhone left and top spacing");
        check(portrait.pillWidth == 176 && portrait.height == 44, "default pill size");
        check(portrait.gap == 6 && portrait.settingsWidth == 44, "ellipsis hit target and gap");
        var narrow = VotControlsUi.place(204, 180, 1f, 0, 0, 0, 0, true, false);
        check(narrow.visible && narrow.pillWidth == 130, "smallest useful horizontal size fits");
        check(!VotControlsUi.place(203, 180, 1f, 0, 0, 0, 0, true, false).visible,
                "hide rather than overflow when both hit targets cannot fit");
        check(!VotControlsUi.place(360, 139, 1f, 0, 0, 0, 0, true, false).visible,
                "short player hides controls");
        check(!VotControlsUi.place(360, 203, 1f, 0, 0, 0, 0, false, false).visible,
                "minimized or hidden native player hides controls");
        check(!VotControlsUi.place(360, 203, 1f, 0, 0, 0, 0, true, true).visible,
                "Shorts hide controls");
        var safe = VotControlsUi.place(800, 500, 1f, 30, 24, 16, 0, true, false);
        check(safe.left == 42 && safe.top == 68, "safe-area offsets remain inside native player");
        check(!VotControlsUi.place(360, 150, 1f, 0, 80, 0, 0, true, false).visible,
                "hide when safe top plus full hit target exceeds player height");
        var dense = VotControlsUi.place(720, 406, 2f, 0, 0, 0, 0, true, false);
        check(dense.left == 24 && dense.top == 100 && dense.pillWidth == 352 && dense.height == 88,
                "density scales geometry exactly once");
        check(VotControlsUi.title("Перевести видео", false, false, false, "ru").equals("Перевести на русский"),
                "Russian idle target");
        check(VotControlsUi.title("Перевести видео", false, false, false, "en").equals("Перевести на английский"),
                "English idle target");
        check(VotControlsUi.title("Перевести видео", false, false, false, "kk").equals("Перевести на казахский"),
                "Kazakh idle target");
        check(VotControlsUi.title("Подготовка озвучки…", true, false, true, "ru").equals("Отмена · готовится…"),
                "request can be cancelled before media is available");
        check(VotControlsUi.title("Подготовка озвучки…", true, true, true, "ru").equals("Подготовка озвучки…"),
                "media loading is visibly preparing");
        check(VotControlsUi.title("Перевод включён", true, true, true, "ru").equals("Живые голоса · вкл."),
                "lively playback label");
        check(VotControlsUi.title("Перевод включён", true, true, false, "ru").equals("Перевод включён"),
                "classic playback label");
        check(VotControlsUi.title("Перевод на паузе", true, true, true, "ru").equals("Перевод на паузе"),
                "pause remains visible with lively mode selected");
        check(VotControlsUi.color(false, "Перевести видео") == 0xFFFFFFFF, "idle is white");
        check(VotControlsUi.color(true, "Подготовка озвучки…") == 0xFF0A84FF, "preparing is blue");
        check(VotControlsUi.color(true, "Буферизация озвучки…") == 0xFF0A84FF, "buffering is blue");
        check(VotControlsUi.color(true, "Перевод на паузе") == 0xFF0A84FF, "paused is blue");
        check(VotControlsUi.color(true, "Перевод включён") == 0xFFFFD60A, "actual playback is yellow");
        System.out.println("VotControlsUi: " + assertions + " assertions passed");
    }
}
