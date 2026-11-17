package io.github.aurelvu.vot.patch;

import app.revanced.com.android.tools.smali.dexlib2.mutable.MutableClassDef;
import app.revanced.com.android.tools.smali.dexlib2.mutable.MutableMethod;
import app.revanced.patcher.extensions.InstructionKt;
import app.revanced.patcher.patch.BytecodePatchBuilder;
import app.revanced.patcher.patch.BytecodePatchContext;
import app.revanced.patcher.patch.Patch;
import app.revanced.patcher.patch.ResourcePatchBuilder;
import app.revanced.patches.youtube.misc.playercontrols.PlayerControlsPatchKt;
import app.revanced.patches.youtube.misc.playertype.PlayerTypeHookPatchKt;
import app.revanced.patches.youtube.video.information.VideoInformationPatchKt;
import com.android.tools.smali.dexlib2.Opcode;
import com.android.tools.smali.dexlib2.builder.BuilderInstruction;
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation;
import com.android.tools.smali.dexlib2.builder.instruction.BuilderInstruction35c;
import com.android.tools.smali.dexlib2.builder.instruction.BuilderInstruction3rc;
import com.android.tools.smali.dexlib2.iface.ClassDef;
import com.android.tools.smali.dexlib2.iface.Method;
import com.android.tools.smali.dexlib2.iface.instruction.Instruction;
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction;
import com.android.tools.smali.dexlib2.iface.instruction.formats.Instruction35c;
import com.android.tools.smali.dexlib2.iface.instruction.formats.Instruction3rc;
import com.android.tools.smali.dexlib2.iface.reference.MethodReference;
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableMethodReference;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import kotlin.Unit;

/** Integration with the public ReVanced 6.2.1 JVM hooks; no obfuscated names are embedded. */
public final class VotPatch {
    private static final String CONTROLLER = "Lio/github/aurelvu/vot/VotController;";
    private static final String BUTTON = "Lio/github/aurelvu/vot/VotPlayerButton;";
    private static final String ORIGINAL_AUDIO = "Lio/github/aurelvu/vot/VotOriginalAudio;";
    private static final String VIDEO_INFORMATION = "Lapp/revanced/extension/youtube/patches/VideoInformation;";
    private static final String PLAYER_TYPE = "Lapp/revanced/extension/youtube/patches/PlayerTypeHookPatch;";

    // Public static Patch fields are discovered by the official ReVanced loader.
    public static final Patch VOT = createPatch();

    private VotPatch() {}

    private static Patch createResources() {
        ResourcePatchBuilder builder = new ResourcePatchBuilder();
        builder.dependsOn(PlayerControlsPatchKt.getPlayerControlsPatch());
        builder.apply(context -> {
            for (String notice : new String[]{"LICENSE", "THIRD_PARTY_NOTICES.txt"}) {
                try (InputStream input = VotPatch.class.getClassLoader().getResourceAsStream("vot/licenses/" + notice)) {
                    if (input == null) throw new IllegalStateException("Missing VOT license notice");
                    java.io.File destination = context.get("assets/vot/" + notice, false);
                    Files.createDirectories(destination.toPath().getParent());
                    Files.copy(input, destination.toPath(), StandardCopyOption.REPLACE_EXISTING);
                } catch (IOException error) {
                    throw new IllegalStateException("Cannot copy VOT license notice", error);
                }
            }
            return Unit.INSTANCE;
        });
        return builder.build(null, "Adds native voice-over translation controls.", true);
    }

    @SuppressWarnings("unchecked")
    private static Patch createPatch() {
        BytecodePatchBuilder builder = extensionBuilder();
        builder.dependsOn(
                VideoInformationPatchKt.getVideoInformationPatch(),
                PlayerControlsPatchKt.getPlayerControlsPatch(),
                PlayerTypeHookPatchKt.getPlayerTypeHookPatch(),
                createResources());
        builder.compatibleWith(builder.invoke("com.google.android.youtube", "20.14.43", "20.40.45"));
        builder.apply(context -> {
            PlayerControlsPatchKt.initializeBottomControl(BUTTON);
            PlayerControlsPatchKt.injectVisibilityCheckCall(BUTTON);
            VideoInformationPatchKt.videoTimeHook(CONTROLLER, "onTime");
            VideoInformationPatchKt.videoSpeedChangedHook(CONTROLLER, "onSpeed");
            VideoInformationPatchKt.userSelectedPlaybackSpeedHook(CONTROLLER, "onSpeed");

            // These methods belong to the dependency extension, so their names are stable.
            hookAtEntry(context, VIDEO_INFORMATION, "setVideoId", "Ljava/lang/String;", "onVideoId");
            hookAtEntry(context, PLAYER_TYPE, "setVideoState", "Ljava/lang/Enum;", "onVideoState");
            int gainCalls = wrapOriginalGain(context);
            if (gainCalls == 0) {
                throw new IllegalStateException("VOT: no original AudioTrack/MediaPlayer volume setters found; refusing a build without independent original-volume control.");
            }
            System.out.println("VOT: wrapped " + gainCalls + " native original-audio volume call sites.");
            return Unit.INSTANCE;
        });
        return builder.build("Voice-over translation", "Adds native Yandex voice-over translation, settings and synchronization.", false);
    }

    private static BytecodePatchBuilder extensionBuilder() {
        BytecodePatchBuilder builder = new BytecodePatchBuilder();
        // The String overload is Kotlin-inline: javac would execute its JVM fallback
        // and capture the patcher/CLI classloader, which cannot see this RVP's resources.
        builder.extendWith(() -> {
            InputStream extension = VotPatch.class.getClassLoader().getResourceAsStream("extensions/vot.rve");
            if (extension == null) throw new IllegalStateException("VOT extension missing from custom patch bundle");
            return extension;
        });
        return builder;
    }

    private static MutableMethod method(BytecodePatchContext context, String owner, String name, String argument) {
        ClassDef original = context.getClassDefs().get(owner);
        if (original == null) throw new IllegalStateException("VOT: missing dependency class " + owner);
        MutableClassDef mutable = context.getClassDefs().getOrReplaceMutable(original);
        for (MutableMethod method : mutable.getMethods()) {
            if (method.getName().equals(name) && method.getReturnType().equals("V")
                    && method.getParameterTypes().size() == 1
                    && method.getParameterTypes().get(0).toString().equals(argument)) return method;
        }
        throw new IllegalStateException("VOT: missing dependency method " + owner + "->" + name);
    }

    private static void hookAtEntry(BytecodePatchContext context, String owner, String name,
                                          String argument, String callback) {
        MutableMethod method = method(context, owner, name, argument);
        MutableMethodImplementation code = method.getImplementation();
        if (code == null) throw new IllegalStateException("VOT: dependency callback has no code");
        // p0 is valid at entry. In the compiled enum setter it is reused for Enum.name()'s
        // String result, so injecting at the return would introduce an invalid Enum argument.
        int index = 0;
        for (BuilderInstruction instruction : InstructionKt.toInstructions(
                "invoke-static/range {p0 .. p0}, " + CONTROLLER + "->" + callback + "(" + argument + ")V", method)) {
            code.addInstruction(index++, instruction);
        }
    }

    private static ImmutableMethodReference wrappedGain(MethodReference reference) {
        String owner = reference.getDefiningClass();
        String name = reference.getName();
        List<String> parameters = new ArrayList<>();
        for (CharSequence parameter : reference.getParameterTypes()) parameters.add(parameter.toString());
        boolean audioTrack = owner.equals("Landroid/media/AudioTrack;")
                && reference.getReturnType().equals("I")
                && ((name.equals("setVolume") && parameters.equals(List.of("F")))
                || (name.equals("setStereoVolume") && parameters.equals(List.of("F", "F"))));
        boolean mediaPlayer = owner.equals("Landroid/media/MediaPlayer;")
                && name.equals("setVolume") && reference.getReturnType().equals("V")
                && parameters.equals(List.of("F", "F"));
        if (!audioTrack && !mediaPlayer) return null;
        parameters.add(0, owner);
        return new ImmutableMethodReference(ORIGINAL_AUDIO, name, parameters, reference.getReturnType());
    }

    private static int wrapOriginalGain(BytecodePatchContext context) {
        int count = 0;
        for (ClassDef sourceClass : new ArrayList<>(context.getClassDefs())) {
            String owner = sourceClass.getType();
            if (owner.startsWith("Lio/github/aurelvu/vot/") || owner.startsWith("Lapp/revanced/extension/")) continue;
            MutableClassDef mutableClass = null;
            for (Method sourceMethod : sourceClass.getMethods()) {
                if (sourceMethod.getImplementation() == null) continue;
                MutableMethod mutableMethod = null;
                int index = 0;
                for (Instruction instruction : sourceMethod.getImplementation().getInstructions()) {
                    Opcode opcode = instruction.getOpcode();
                    if ((opcode == Opcode.INVOKE_VIRTUAL || opcode == Opcode.INVOKE_VIRTUAL_RANGE)
                            && instruction instanceof ReferenceInstruction referenceInstruction
                            && referenceInstruction.getReference() instanceof MethodReference reference) {
                        ImmutableMethodReference target = wrappedGain(reference);
                        if (target != null) {
                            if (mutableClass == null) mutableClass = context.getClassDefs().getOrReplaceMutable(sourceClass);
                            if (mutableMethod == null) {
                                for (MutableMethod candidate : mutableClass.getMethods()) {
                                    if (candidate.equals(sourceMethod)) { mutableMethod = candidate; break; }
                                }
                            }
                            if (mutableMethod == null) throw new IllegalStateException("VOT: cannot resolve native audio method");
                            BuilderInstruction replacement;
                            if (instruction instanceof Instruction35c compact) {
                                replacement = new BuilderInstruction35c(Opcode.INVOKE_STATIC,
                                        compact.getRegisterCount(), compact.getRegisterC(), compact.getRegisterD(),
                                        compact.getRegisterE(), compact.getRegisterF(), compact.getRegisterG(), target);
                            } else if (instruction instanceof Instruction3rc range) {
                                replacement = new BuilderInstruction3rc(Opcode.INVOKE_STATIC_RANGE,
                                        range.getStartRegister(), range.getRegisterCount(), target);
                            } else throw new IllegalStateException("VOT: unsupported native audio invocation format");
                            mutableMethod.getImplementation().replaceInstruction(index, replacement);
                            count++;
                        }
                    }
                    index++;
                }
            }
        }
        return count;
    }
}
