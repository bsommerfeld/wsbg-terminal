# Resolves the JDK 27 the build runs on and exports JAVA_HOME. Sourced by the
# other scripts. A JVM registered with macOS (java_home) wins; the Homebrew keg
# (`brew install openjdk`, keg-only, so invisible to java_home) is the fallback.
resolve_jdk() {
    local home
    home="$(/usr/libexec/java_home -v 27 2>/dev/null || true)"
    if ! grep -qs '^JAVA_VERSION="27' "$home/release" 2>/dev/null; then
        home="/opt/homebrew/opt/openjdk/libexec/openjdk.jdk/Contents/Home"
    fi
    if ! grep -qs '^JAVA_VERSION="27' "$home/release" 2>/dev/null; then
        echo "JDK 27 not found - install it with: brew install openjdk" >&2
        return 1
    fi
    export JAVA_HOME="$home"
}
resolve_jdk || exit 1
