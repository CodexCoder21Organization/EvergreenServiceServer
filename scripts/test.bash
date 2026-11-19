#!/bin/bash
# Test script using kompile-cli.

SCRIPT_PATH="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
mkdir -p "$SCRIPT_PATH/../jars"
JAR_PATH="$SCRIPT_PATH/../jars/KompileCli-0.0.132.jar"

if [ ! -f "$JAR_PATH" ]; then
  # coursier is pinned to a release jar and checked against its SHA-256 before use. COURSIER may name
  # a coursier executable to use instead of the pinned one.
  COURSIER_VERSION="2.1.25-M26"
  COURSIER_SHA256="97647d0378b877db2552a29caec075de1bae86375f0343ba82cf52d69193eb5e"
  COURSIER_URL="https://github.com/coursier/coursier/releases/download/v$COURSIER_VERSION/coursier.jar"
  COURSIER_JAR="$SCRIPT_PATH/../jars/coursier-$COURSIER_VERSION.jar"
  sha256_digest_of() {
    local digest_line
    if command -v sha256sum > /dev/null 2>&1; then
      digest_line=$(sha256sum "$1") || return 1
    elif command -v shasum > /dev/null 2>&1; then
      digest_line=$(shasum -a 256 "$1") || return 1
    else
      return 1
    fi
    printf '%s\n' "${digest_line%% *}"
  }
  if [ -n "${COURSIER:-}" ]; then
    coursier_command=("$COURSIER")
  else
    if [ ! -f "$COURSIER_JAR" ] || [ "$(sha256_digest_of "$COURSIER_JAR" || true)" != "$COURSIER_SHA256" ]; then
      echo "Downloading coursier $COURSIER_VERSION from $COURSIER_URL..."
      mkdir -p "$SCRIPT_PATH/../jars"
      COURSIER_TMP=$(mktemp "$COURSIER_JAR.tmp.XXXXXX")
      if ! curl -fLo "$COURSIER_TMP" --retry 6 --retry-delay 2 --retry-all-errors "$COURSIER_URL"; then
        rm -f "$COURSIER_TMP"
        echo "Could not download the coursier launcher $COURSIER_VERSION from $COURSIER_URL." >&2
        exit 1
      fi
      if ! COURSIER_ACTUAL_SHA256=$(sha256_digest_of "$COURSIER_TMP"); then
        rm -f "$COURSIER_TMP"
        echo "Could not compute the sha256 digest of the coursier launcher downloaded from $COURSIER_URL to $COURSIER_TMP; neither sha256sum nor shasum was found on PATH ($PATH)." >&2
        exit 1
      fi
      if [ "$COURSIER_ACTUAL_SHA256" != "$COURSIER_SHA256" ]; then
        rm -f "$COURSIER_TMP"
        echo "The coursier launcher downloaded from $COURSIER_URL has sha256 digest $COURSIER_ACTUAL_SHA256, but $COURSIER_SHA256 was expected; refusing to use it." >&2
        exit 1
      fi
      mv -f "$COURSIER_TMP" "$COURSIER_JAR"
    fi
    coursier_command=(java -jar "$COURSIER_JAR")
  fi

  echo "Fetching kompile.cli:kompile-cli:0.0.132 from https://kotlin.directory/..."
  CLASSPATH=$("${coursier_command[@]}" fetch --repository https://kotlin.directory/ --repository central kompile.cli:kompile-cli:0.0.132 --classpath)

  cat > "$JAR_PATH" <<'LAUNCHER_EOF'
#!/bin/bash
LAUNCHER_EOF
  echo "CLASSPATH='$CLASSPATH'" >> "$JAR_PATH"
  cat >> "$JAR_PATH" <<'LAUNCHER_EOF'
exec java $JAVA_OPTS -cp "$CLASSPATH" kompile.cli.CliKt "$@"
LAUNCHER_EOF
  chmod +x "$JAR_PATH"
fi

JAVA_PROXY_OPTS=""
if [ -n "$HTTP_PROXY" ]; then
  proxy_host=$(echo "$HTTP_PROXY" | sed -E 's#^[^/]*//([^/:@]+).*#\1#')
  proxy_port=$(echo "$HTTP_PROXY" | sed -nE 's#.*:([0-9]+).*#\1#p')
  JAVA_PROXY_OPTS="-Dhttp.proxyHost=$proxy_host -Dhttps.proxyHost=$proxy_host"
  if [ -n "$proxy_port" ]; then
    JAVA_PROXY_OPTS="$JAVA_PROXY_OPTS -Dhttp.proxyPort=$proxy_port -Dhttps.proxyPort=$proxy_port"
  else
    JAVA_PROXY_OPTS="$JAVA_PROXY_OPTS -Dhttp.proxyPort=80 -Dhttps.proxyPort=443"
  fi
fi

REPO_PATH=$(cd "$SCRIPT_PATH/.." && pwd)
CACHE_PATH="$HOME/.aibuildcaches/$(echo "$REPO_PATH" | sed 's|/|_|g')"
JAVA_OPTS="$JAVA_PROXY_OPTS" "$JAR_PATH" --cache-location "$CACHE_PATH" -w "$SCRIPT_PATH/.." "$@"
