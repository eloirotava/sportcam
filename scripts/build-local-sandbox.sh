#!/usr/bin/env bash
set -euo pipefail

# Compila em ambientes que redirecionam conexões de loopback entre processos,
# como o sandbox usado por este agente. Gradle, Kotlin e javac usam processos
# auxiliares que precisam conversar entre si por 127.0.0.1.
repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$repo_root"

if [[ ! -f .sportcam-env ]]; then
  echo "Execute ./scripts/bootstrap-codespaces.sh primeiro." >&2
  exit 1
fi

# shellcheck disable=SC1091
source .sportcam-env

export GRADLE_USER_HOME="${SPORTCAM_GRADLE_USER_HOME:-/tmp/sportcam-gradle-home}"
prepare_cache=false
if [[ "${1:-}" == "--prepare-cache" ]]; then
  prepare_cache=true
  shift
fi
if (($# == 0)); then
  tasks=(assembleDebug)
else
  tasks=("$@")
fi

prepare_gradle_cache() {
  local gradle_bin gradle_home gradle_cli_jar instrumentation_agent
  gradle_bin="$(readlink -f "$(command -v gradle)")"
  gradle_home="$(cd "$(dirname "$gradle_bin")/.." && pwd)"
  gradle_cli_jar="$(find "$gradle_home/lib" -maxdepth 1 -name 'gradle-gradle-cli-main-*.jar' -print -quit)"
  instrumentation_agent="$(find "$gradle_home/lib/agents" -maxdepth 1 -name 'gradle-instrumentation-agent-*.jar' -print -quit)"

  echo "Preparando o cache Gradle com acesso à rede..."
  "$JAVA_HOME/bin/java" \
    -Xmx2g \
    -Dfile.encoding=UTF-8 \
    -Duser.country= \
    -Duser.language=en \
    -Duser.variant= \
    -javaagent:"$instrumentation_agent" \
    -Dorg.gradle.appname=gradle \
    -classpath "$gradle_cli_jar" \
    org.gradle.launcher.GradleMain \
    --no-daemon \
    -I scripts/local-cache.init.gradle \
    resolveSportCamLocalDependencies \
    --console=plain
  touch "$GRADLE_USER_HOME/.sportcam-cache-ready"
}

if [[ "${SPORTCAM_BUILD_NETNS:-0}" != "1" ]]; then
  if ! command -v unshare >/dev/null || ! command -v ip >/dev/null; then
    echo "Este ambiente precisa dos comandos unshare e ip." >&2
    exit 1
  fi
  if [[ "$prepare_cache" == true || ! -f "$GRADLE_USER_HOME/.sportcam-cache-ready" ]]; then
    prepare_gradle_cache
  fi

  exec unshare --net env \
    SPORTCAM_BUILD_NETNS=1 \
    GRADLE_USER_HOME="$GRADLE_USER_HOME" \
    bash "$0" "${tasks[@]}"
fi

ip link set lo up
exec gradle --offline --no-daemon "${tasks[@]}" --console=plain
