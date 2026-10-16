#!/usr/bin/env bash
# Run the pinned wrapper with workspace-local SDK/caches. Never creates a release key.
set -euo pipefail
project_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
toolchains_dir="${MOTIONPIN_TOOLCHAINS_DIR:-/workspace/toolchains}"
export JAVA_HOME="${MOTIONPIN_JAVA_HOME:-${toolchains_dir}/jdk-21.0.12.1+1}"
if [[ ! -x "$JAVA_HOME/bin/javac" ]]; then
    printf '%s\n' 'A full JDK is required. Set MOTIONPIN_JAVA_HOME to its directory.' >&2
    exit 1
fi
export ANDROID_HOME="${ANDROID_HOME:-${toolchains_dir}/android-sdk}"
export ANDROID_USER_HOME="${ANDROID_USER_HOME:-${toolchains_dir}/android-user}"
export GRADLE_USER_HOME="${GRADLE_USER_HOME:-${toolchains_dir}/gradle-cache}"
mkdir -p "$ANDROID_USER_HOME" "$GRADLE_USER_HOME"

proxy_args=()
if [[ -n "${CODEX_PROXY_CERT:-}" && -r /etc/ssl/certs/java/cacerts ]]; then
    # Keep the managed environment's configured proxy CA trust when using the downloaded JDK.
    proxy_args+=("-Djavax.net.ssl.trustStore=/etc/ssl/certs/java/cacerts")
fi
for proxy_scheme in http https; do
    if [[ "$proxy_scheme" == https ]]; then
        proxy_url="${HTTPS_PROXY:-${https_proxy:-}}"
    else
        proxy_url="${HTTP_PROXY:-${http_proxy:-}}"
    fi
    if [[ -n "$proxy_url" ]]; then
        # Only host/port are passed to Java; never place proxy credentials in arguments/logs.
        read -r proxy_host proxy_port < <(python3 - "$proxy_scheme" <<'PY'
import os, sys
from urllib.parse import urlsplit
name = sys.argv[1] + "_proxy"
parsed = urlsplit(os.environ.get(name.upper(), os.environ.get(name, "")))
if parsed.hostname:
    print(parsed.hostname, parsed.port or (443 if parsed.scheme == "https" else 80))
PY
)
        proxy_args+=("-D${proxy_scheme}.proxyHost=${proxy_host}" "-D${proxy_scheme}.proxyPort=${proxy_port}")
    fi
done
proxy_args+=("-Dhttp.nonProxyHosts=localhost|127.*|[::1]")

cd "$project_dir"
if [[ "$#" -eq 0 ]]; then
    # APK generation/signing is a separate, explicitly authorized delivery step.
    set -- compileDebugKotlin compileReleaseKotlin compileDebugAndroidTestKotlin testDebugUnitTest lintRelease
fi
exec ./gradlew --no-daemon "${proxy_args[@]}" "$@"
