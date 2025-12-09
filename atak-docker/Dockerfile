# syntax=docker/dockerfile:1.7
ARG CMDLINE_TOOLS_VERSION=11076708_latest
ARG NDK_VERSION=27.0.12077973
ARG CMAKE_VERSION=3.22.1
ARG BUILD_TOOLS=34.0.0
ARG PLATFORM=29
ARG USERNAME=builder
ARG UID=1000
ARG GID=1001

FROM eclipse-temurin:11-jdk-jammy AS jdk11
FROM mirror.gcr.io/library/debian:bookworm AS build
ARG CMDLINE_TOOLS_VERSION NDK_VERSION CMAKE_VERSION BUILD_TOOLS PLATFORM USERNAME UID GID

RUN apt-get update && apt-get install -y --no-install-recommends \
    ca-certificates curl unzip git bash openssh-client \
    file make cmake ninja-build python3 pkg-config \
    gcc g++ libc6 libstdc++6 rsync \
    openjdk-17-jdk-headless \
    clang libclang1 \
 && rm -rf /var/lib/apt/lists/*


RUN groupadd -g ${GID} ${USERNAME} && useradd -m -u ${UID} -g ${GID} -s /bin/bash ${USERNAME}
USER ${USERNAME}
WORKDIR /home/${USERNAME}

ENV HOME=/home/${USERNAME}
ENV ANDROID_HOME=${HOME}/Android/Sdk
ENV ANDROID_SDK_ROOT=${ANDROID_HOME}
RUN mkdir -p ${ANDROID_SDK_ROOT}/cmdline-tools ${ANDROID_SDK_ROOT}/licenses

RUN curl -fsSL https://dl.google.com/android/repository/commandlinetools-linux-${CMDLINE_TOOLS_VERSION}.zip -o /tmp/cmdline-tools.zip \
 && unzip -q /tmp/cmdline-tools.zip -d /tmp/cmdline-tools \
 && mkdir -p ${ANDROID_SDK_ROOT}/cmdline-tools/latest \
 && mv /tmp/cmdline-tools/cmdline-tools/* ${ANDROID_SDK_ROOT}/cmdline-tools/latest/ \
 && rm -rf /tmp/cmdline-tools*

RUN export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 && export PATH="$JAVA_HOME/bin:${ANDROID_SDK_ROOT}/cmdline-tools/latest/bin:${ANDROID_SDK_ROOT}/platform-tools:$PATH" \
 && yes | sdkmanager --licenses >/dev/null || true

RUN export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 && export PATH="$JAVA_HOME/bin:${ANDROID_SDK_ROOT}/cmdline-tools/latest/bin:${ANDROID_SDK_ROOT}/platform-tools:$PATH" \
 && yes | sdkmanager "platform-tools" "platforms;android-${PLATFORM}" "build-tools;${BUILD_TOOLS}" "ndk;${NDK_VERSION}" "cmake;${CMAKE_VERSION}"


COPY --from=jdk11 /opt/java/openjdk /opt/java-11
ENV JAVA_HOME=/opt/java-11
ENV PATH=$JAVA_HOME/bin:${ANDROID_SDK_ROOT}/cmdline-tools/latest/bin:${ANDROID_SDK_ROOT}/platform-tools:$PATH


ENV ANDROID_NDK_HOME=${ANDROID_SDK_ROOT}/ndk/${NDK_VERSION}
ENV TOOLCHAIN_FILE=${ANDROID_NDK_HOME}/build/cmake/android.toolchain.cmake
ENV INSTALL_ROOT=/home/${USERNAME}/install
RUN mkdir /home/${USERNAME}/install
ENV DEPLOY_ROOT=/out
ENV ABIS="arm64-v8a armeabi-v7a x86"
ENV BUILD_ROOT=/home/${USERNAME}/build
RUN mkdir /home/${USERNAME}/build
ENV DEPS_ROOT=/home/${USERNAME}/deps

# === Rust toolchain for building libatak.so from the private repo ===
RUN curl -sSf https://sh.rustup.rs | sh -s -- -y --default-toolchain stable
ENV CARGO_HOME=/home/${USERNAME}/.cargo RUSTUP_HOME=/home/${USERNAME}/.rustup PATH=/home/${USERNAME}/.cargo/bin:$PATH
RUN cargo install cargo-ndk
RUN rustup target add aarch64-linux-android armv7-linux-androideabi i686-linux-android

COPY --chown=${USERNAME}:${USERNAME} . /app
WORKDIR /app

COPY --chown=${USERNAME}:${USERNAME} third_party/libusb/     /src/libusb/
COPY --chown=${USERNAME}:${USERNAME} third_party/hackrf/     /src/hackrf/
COPY --chown=${USERNAME}:${USERNAME} third_party/SoapySDR/   /src/SoapySDR/
COPY --chown=${USERNAME}:${USERNAME} third_party/SoapyHackRF/ /src/SoapyHackRF/
COPY --chown=${USERNAME}:${USERNAME} third_party/FutureSDR/ /src/FutureSDR/
COPY entrypoint.sh /

ENV RUST_CRATE_DIR=/src/FutureSDR/examples/atak

# .cargo/config.toml
RUN mkdir -p "${RUST_CRATE_DIR}/.cargo" && \
    printf '%s\n' \
    '[target.aarch64-linux-android]' \
    'linker = "${ANDROID_NDK_HOME}/toolchains/llvm/prebuilt/linux-x86_64/bin/aarch64-linux-android21-clang"' \
    'ar     = "${ANDROID_NDK_HOME}/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-ar"' \
    '' \
    '[target.armv7-linux-androideabi]' \
    'linker = "${ANDROID_NDK_HOME}/toolchains/llvm/prebuilt/linux-x86_64/bin/armv7a-linux-androideabi21-clang"' \
    'ar     = "${ANDROID_NDK_HOME}/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-ar"' \
    '' \
    '[target.i686-linux-android]' \
    'linker = "${ANDROID_NDK_HOME}/toolchains/llvm/prebuilt/linux-x86_64/bin/i686-linux-android21-clang"' \
    'ar     = "${ANDROID_NDK_HOME}/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-ar"' \
    '' \
    '[env]' \
    'PKG_CONFIG_ALLOW_CROSS = "1"' \
    '' \
    '[target.aarch64-linux-android.env]' \
    'SOAPYSDR_NO_PKG_CONFIG = "1"' \
    'SOAPYSDR_LIB_DIR     = "${INSTALL_ROOT}/arm64-v8a/lib/SoapySDR"' \
    'SOAPYSDR_INCLUDE_DIR = "${INSTALL_ROOT}/arm64-v8a/include/SoapySDR"' \
    > "${RUST_CRATE_DIR}/.cargo/config.toml"

# build libusb (ndk-build) for all ABIs
ENTRYPOINT ["/bin/bash", "/entrypoint.sh"]

## 用 BuildKit secret 挂载私钥来克隆私库（不依赖 SSH agent）
#RUN --mount=type=secret,id=git_key,uid=1000,gid=1000,mode=0400 \
#    bash -c 'set -euo pipefail; \
#      mkdir -p ${DEPS_ROOT} ~/.ssh; \
#      # 将私钥复制到用户目录并设置正确权限 \
#      cp /run/secrets/git_key ~/.ssh/id_ed25519; \
#      chmod 600 ~/.ssh/id_ed25519; \
#      ssh-keyscan -H dev.seemoo.tu-darmstadt.de >> ~/.ssh/known_hosts 2>/dev/null; \
#      if [ ! -d ${DEPS_ROOT}/futuresdr ]; then \
#        GIT_SSH_COMMAND="ssh -o IdentitiesOnly=yes -o StrictHostKeyChecking=yes -i ~/.ssh/id_ed25519" \
#        git clone -b atak-local --single-branch \
#          git@dev.seemoo.tu-darmstadt.de:stud-theses/bsc-junchi-weng-futuresdr.git \
#          ${DEPS_ROOT}/futuresdr; \
#        cd ${DEPS_ROOT}/futuresdr; \
#        git -c core.sshCommand="ssh -o IdentitiesOnly=yes -o StrictHostKeyChecking=yes -i ~/.ssh/id_ed25519" \
#          submodule update --init --recursive || true; \
#      fi; \
#      # 清理私钥 \
#      rm -f ~/.ssh/id_ed25519'
#
#

#
#
#WORKDIR /app
#
#RUN bash -lc 'set -euo pipefail; \
#for ABI in ${ABIS}; do \
#  mkdir -p app/src/main/jniLibs/$ABI; \
#  cp -v ${INSTALL_ROOT}/$ABI/lib/libusb1.0.so   app/src/main/jniLibs/$ABI/ || true; \
#  cp -v ${INSTALL_ROOT}/$ABI/lib/libhackrf.so   app/src/main/jniLibs/$ABI/ || true; \
#  cp -v ${INSTALL_ROOT}/$ABI/lib/libSoapySDR.so app/src/main/jniLibs/$ABI/ || true; \
#  shopt -s nullglob; \
#  for m in ${INSTALL_ROOT}/$ABI/lib/SoapySDR/modules*/libHackRFSupport.so; do \
#    cp -v "$m" app/src/main/jniLibs/$ABI/; \
#  done; \
#  shopt -u nullglob; \
#done'
#
#RUN mkdir -p /home/builder/.gradle && \
#    printf '%s\n' \
#    'org.gradle.unsafe.watch-fs=false' \
#    'org.gradle.daemon=false' \
#    'org.gradle.configureondemand=false' \
#    'org.gradle.parallel=true' \
#    'org.gradle.caching=true' \
#    'org.gradle.vfs.watch=false' \
#    > /home/builder/.gradle/gradle.properties
#
#ENV GRADLE_USER_HOME=/home/builder/.gradle
#
#ENV GRADLE_OPTS="-Dorg.gradle.unsafe.watch-fs=false -Dorg.gradle.daemon=false"
#
#RUN chmod +x gradlew
#RUN mkdir -p /home/${USERNAME}/.gradle && \
#    printf '%s\n' \
#    'org.gradle.java.home=/opt/java-11' \
#    'org.gradle.java.installations.auto-detect=false' \
#    'org.gradle.java.installations.paths=/opt/java-11' \
#    'org.gradle.unsafe.watch-fs=false' \
#    'org.gradle.daemon=false' \
#    'org.gradle.configureondemand=false' \
#    > /home/${USERNAME}/.gradle/gradle.properties
#
#RUN ./gradlew assembleDebug --no-daemon --no-watch-fs --no-configure-on-demand
#
#FROM scratch AS artifacts
#COPY --from=build /app/app/build/outputs /outputs