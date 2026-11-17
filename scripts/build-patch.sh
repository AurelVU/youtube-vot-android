#!/bin/bash
set -euo pipefail
cd "$(dirname "$0")/.."

patch_cli="${VOT_CLI_JAR:-build/revanced-cli.jar}"
official_bundle="${VOT_OFFICIAL_RVP:-build/patches.rvp}"
extension_dex="${VOT_EXTENSION_DEX:-build/vot.dex}"
output_bundle="${VOT_PATCH_RVP:-build/vot-patches.rvp}"

test -f "$patch_cli"
test -f "$official_bundle"
mkdir -p build/patch-classes
find patch/src -name '*.java' -print > build/patch-java-sources.txt
javac --release 17 -Xlint:unchecked -cp "$patch_cli:$official_bundle" \
    -d build/patch-classes @build/patch-java-sources.txt

if [[ "${1:-}" == "--compile-only" ]]; then
    exit 0
fi
test -f "$extension_dex"

python3 - "$official_bundle" "$extension_dex" "$output_bundle" <<'PY'
from pathlib import Path
import hashlib
import sys
import zipfile

original, dex, output = map(Path, sys.argv[1:])
if original.resolve() == output.resolve():
    raise SystemExit('Refusing to overwrite official patch bundle')
original_hash = hashlib.sha256(original.read_bytes()).hexdigest()
if not dex.read_bytes().startswith(b'dex\n'):
    raise SystemExit('Extension input is not a raw DEX file')
output.parent.mkdir(parents=True, exist_ok=True)
with zipfile.ZipFile(original) as source, zipfile.ZipFile(output, 'w', zipfile.ZIP_DEFLATED) as target:
    for member in source.infolist():
        target.writestr(member, source.read(member.filename))
    for folder in [Path('build/patch-classes'), Path('patch/resources')]:
        for path in sorted(folder.rglob('*')):
            if path.is_file():
                target.write(path, str(path.relative_to(folder)))
    target.write(dex, 'extensions/vot.rve')
if hashlib.sha256(original.read_bytes()).hexdigest() != original_hash:
    raise SystemExit('Official patch bundle changed unexpectedly')
print(f'Composed custom patch bundle: {output}')
PY

java -jar "$patch_cli" list-patches -p "$output_bundle" -b \
    --filter-package-name com.google.android.youtube > build/vot-patch-list.txt
if ! rg -q 'Voice-over translation' build/vot-patch-list.txt; then
    echo 'Custom voice-over patch was not discovered' >&2
    exit 1
fi
echo 'Custom patch discovered: Voice-over translation'

# Discovery does not execute the deferred extension loader. Exercise that loader from
# a separate URLClassLoader, just as the CLI does, and merge the actual returned DEX.
mkdir -p build/patch-smoke-src build/patch-smoke-classes
cat > build/patch-smoke-src/ExtensionLoadSmoke.java <<'JAVA'
import app.revanced.patcher.patch.BytecodePatchBuilder;
import app.revanced.patcher.patch.BytecodePatchContext;
import java.io.InputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

public final class ExtensionLoadSmoke {
    public static void main(String[] arguments) throws Exception {
        Path bundle = Path.of(arguments[0]).toAbsolutePath();
        Path fixture = Path.of("build/extension-smoke.apk");
        try (ZipFile archive = new ZipFile(bundle.toFile());
             ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(fixture))) {
            output.putNextEntry(new ZipEntry("classes.dex"));
            try (InputStream original = archive.getInputStream(archive.getEntry("extensions/youtube.rve"))) {
                original.transferTo(output);
            }
            output.closeEntry();
        }
        try (URLClassLoader loader = new URLClassLoader(
                new URL[]{bundle.toUri().toURL()}, ExtensionLoadSmoke.class.getClassLoader())) {
            Class<?> patchClass = loader.loadClass("io.github.aurelvu.vot.patch.VotPatch");
            var factory = patchClass.getDeclaredMethod("extensionBuilder");
            factory.setAccessible(true);
            BytecodePatchBuilder builder = (BytecodePatchBuilder) factory.invoke(null);
            BytecodePatchContext context = new BytecodePatchContext(
                    fixture.toFile(), Path.of("build/extension-smoke-output").toFile());
            try (InputStream extension = builder.getGetExtensionInputStream().invoke()) {
                if (extension == null) throw new AssertionError("Deferred loader returned no extension");
                context.addExtension$patcher(extension);
            }
            context.getClassDefs().initializeCache$patcher();
            for (String required : new String[]{
                    "Lio/github/aurelvu/vot/VotController;",
                    "Lio/github/aurelvu/vot/VotPlayerButton;",
                    "Lio/github/aurelvu/vot/VotOriginalAudio;"}) {
                if (context.getClassDefs().get(required) == null) {
                    throw new AssertionError("Extension was not merged: " + required);
                }
            }
            System.out.println("Deferred extension loader: isolated RVP classloader loaded and merged actual VOT DEX");
        }
    }
}
JAVA
javac --release 17 -cp "$patch_cli" -d build/patch-smoke-classes \
    build/patch-smoke-src/ExtensionLoadSmoke.java
# Deliberately keep the RVP off the system classpath: otherwise the inline-loader
# regression is hidden, because the wrong classloader can accidentally find it.
java -Xmx512m -cp "build/patch-smoke-classes:$patch_cli" ExtensionLoadSmoke "$output_bundle"
