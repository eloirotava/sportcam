#!/usr/bin/env bash
set -euo pipefail

# Instala, fora do Git, as ferramentas necessárias para compilar em um Codespace
# Ubuntu x86_64. Os números são compatíveis com AGP 8.9.1 e compileSdk 35 usados
# neste projeto.
if [[ "$(uname -s)" != "Linux" || "$(uname -m)" != "x86_64" ]]; then
  echo "Este instalador é destinado a Linux x86_64." >&2
  exit 1
fi

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
android_home="${ANDROID_HOME:-$HOME/android-sdk}"
gradle_home="$HOME/.local/gradle-8.11.1"
command_tools_url="https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip"
gradle_url="https://services.gradle.org/distributions/gradle-8.11.1-bin.zip"

sudo apt-get update
sudo apt-get install -y openjdk-17-jdk wget unzip

work_dir="$(mktemp -d)"
trap 'rm -rf "$work_dir"' EXIT

if [[ ! -x "$android_home/cmdline-tools/latest/bin/sdkmanager" ]]; then
  wget -q "$command_tools_url" -O "$work_dir/android-command-tools.zip"
  unzip -q "$work_dir/android-command-tools.zip" -d "$work_dir/android-command-tools"
  mkdir -p "$android_home/cmdline-tools"
  mv "$work_dir/android-command-tools/cmdline-tools" "$android_home/cmdline-tools/latest"
fi

if [[ ! -x "$gradle_home/bin/gradle" ]]; then
  wget -q "$gradle_url" -O "$work_dir/gradle.zip"
  mkdir -p "$HOME/.local"
  unzip -q "$work_dir/gradle.zip" -d "$HOME/.local"
fi

java_home="$(dirname "$(dirname "$(readlink -f "$(command -v javac)")")")"
export JAVA_HOME="$java_home"
export ANDROID_HOME="$android_home"
export PATH="$gradle_home/bin:$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools:$PATH"

yes | sdkmanager --licenses >/dev/null || true
sdkmanager "platform-tools" "platforms;android-35" "build-tools;35.0.0"
printf 'sdk.dir=%s\n' "$ANDROID_HOME" > "$repo_root/local.properties"
cat > "$repo_root/.cascam-env" <<EOF
export JAVA_HOME="$JAVA_HOME"
export ANDROID_HOME="$ANDROID_HOME"
export PATH="$gradle_home/bin:\$ANDROID_HOME/cmdline-tools/latest/bin:\$ANDROID_HOME/platform-tools:\$PATH"
EOF

echo
echo "Ferramentas instaladas fora do repositório. Agora execute:"
echo "  source .cascam-env"
echo "  gradle assembleDebug"
