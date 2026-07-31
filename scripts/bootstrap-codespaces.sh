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

# Codespaces pode trazer um Java muito novo como padrão (por exemplo 25.0.2).
# AGP 8.9.1 deve rodar no JDK 17. Não derive JAVA_HOME de `command -v javac`, pois
# isso escolheria o Java global do Codespace em vez do pacote instalado acima.
java_home="/usr/lib/jvm/java-17-openjdk-amd64"
if [[ ! -x "$java_home/bin/java" ]]; then
  java_binary="$(dpkg -L openjdk-17-jdk-headless | grep '/bin/java$' | head -n 1)"
  java_home="$(dirname "$(dirname "$java_binary")")"
fi
if [[ "$($java_home/bin/java -version 2>&1 | head -n 1)" != *'17.'* ]]; then
  echo "Não foi possível selecionar o JDK 17 em $java_home" >&2
  exit 1
fi
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

# Um daemon iniciado anteriormente com Java 25 não deve ser reaproveitado.
"$gradle_home/bin/gradle" --stop >/dev/null 2>&1 || true

echo
echo "Ferramentas instaladas fora do repositório. Agora execute:"
echo "  source .cascam-env"
echo "  java -version  # deve mostrar 17"
echo "  gradle assembleDebug"
