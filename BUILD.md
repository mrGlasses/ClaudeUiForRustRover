# Building without installing Java or an IDE

The result you want is `build/distributions/claude-meta-plugin-*.zip` — **that** is what you pick in
Settings > Plugins > gear > Install Plugin from Disk (not the source zip).

Expect compile errors on the first run (see "Not verified" in SKETCH.md); send them back and they get fixed.

## Debugging the bridge

The plugin writes a plain-text log to `%USERPROFILE%\.claude-meta\bridge.log` (handshake headers, connect/close
codes, JSON-RPC method names; never the token or message bodies). After a failed `/ide` attempt, send that file's contents.

## Option A — use the Java that ships inside RustRover (nothing to install)

RustRover bundles a JetBrains Runtime (JBR) in its install folder. Point `JAVA_HOME` at it for one terminal session,
and compile against the same install:

```
# Windows (PowerShell)
$env:JAVA_HOME = "C:\Program Files\JetBrains\RustRover 2026.2\jbr"
.\gradlew.bat buildPlugin -PrustroverPath="C:\Program Files\JetBrains\RustRover 2026.2"

# macOS
export JAVA_HOME="/Applications/RustRover.app/Contents/jbr/Contents/Home"
chmod +x gradlew
./gradlew buildPlugin -PrustroverPath="/Applications/RustRover.app"

# Linux (install folder = wherever RustRover lives, e.g. /opt/rustrover)
export JAVA_HOME="/opt/rustrover/jbr"
chmod +x gradlew
./gradlew buildPlugin -PrustroverPath="/opt/rustrover"
```

Use your real install folder (the one containing `bin/`, `lib/` and `jbr/`). The wrapper downloads Gradle itself.
If Gradle complains that the runtime has no compiler / is not a JDK, use Option B instead.

## Option B — let GitHub build it (no local tooling at all)

1. Create a GitHub repository and upload this project's files (web UI: "Add file" > "Upload files").
   Make sure `.github/workflows/build.yml` made it in; if the hidden folder was skipped, create that file
   in the web editor ("Add file" > "Create new file", name `.github/workflows/build.yml`) and paste the contents.
2. Open the repository's **Actions** tab. The `build-plugin` workflow runs on every push (or start it with "Run workflow").
3. When it finishes, open the run and download the **plugin-zip** artifact. Inside is the installable plugin zip.
4. If the run fails, open the log and paste the compiler errors here.
