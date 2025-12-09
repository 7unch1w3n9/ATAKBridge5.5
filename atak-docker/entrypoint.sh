bash -lc 'set -euo pipefail; \
for ABI in ${ABIS}; do \
  echo "=== ndk-build libusb for $ABI ==="; \
  rm -rf ${BUILD_ROOT}/libusb_build && mkdir -p ${BUILD_ROOT}/libusb_build; \
  cd /src/libusb/android; \
  NDK_PROJECT_PATH=. \
  APP_PLATFORM=android-21 \
  APP_ABI=$ABI \
  NDK_LIBS_OUT=${BUILD_ROOT}/libusb_build/libs \
  NDK_OUT=${BUILD_ROOT}/libusb_build/obj \
  ${ANDROID_NDK_HOME}/ndk-build -j$(nproc); \
  mkdir -p ${INSTALL_ROOT}/$ABI/lib ${INSTALL_ROOT}/$ABI/include; \
  cp -v ${BUILD_ROOT}/libusb_build/libs/$ABI/libusb1.0.so ${INSTALL_ROOT}/$ABI/lib/; \
  rsync -a --delete /src/libusb/libusb/ ${INSTALL_ROOT}/$ABI/include/libusb/; \
  mkdir -p ${DEPLOY_ROOT}/$ABI/; \
  cp ${INSTALL_ROOT}/$ABI/lib/libusb1.0.so ${DEPLOY_ROOT}/$ABI/libusb1.0.so; \
done'



bash -lc 'set -euo pipefail; \
for ABI in ${ABIS}; do \
  echo "=== build libhackrf for $ABI ==="; \
  mkdir -p ${BUILD_ROOT}/hackrf-$ABI; \
  cmake -S /src/hackrf/host/libhackrf -B ${BUILD_ROOT}/hackrf-$ABI \
    -DCMAKE_TOOLCHAIN_FILE=${TOOLCHAIN_FILE} \
    -DANDROID_ABI=$ABI \
    -DANDROID_PLATFORM=android-21 \
    -DCMAKE_BUILD_TYPE=Release \
    -DBUILD_SHARED_LIBS=ON \
    -DTHREADS_PREFER_PTHREAD_FLAG=ON \
    -DTHREADS_PTHREAD_ARG=-pthread \
    -DTHREADS_HAVE_PTHREAD_ARG=TRUE \
    -DLIBUSB_INCLUDE_DIR=${INSTALL_ROOT}/$ABI/include/libusb \
    -DLIBUSB_INCLUDE_DIRS=${INSTALL_ROOT}/$ABI/include/libusb \
    -DLIBUSB_LIBRARIES=${INSTALL_ROOT}/$ABI/lib/libusb1.0.so; \
  cmake --build ${BUILD_ROOT}/hackrf-$ABI -j; \
  cmake --install ${BUILD_ROOT}/hackrf-$ABI --prefix ${INSTALL_ROOT}/$ABI; \
  cp ${INSTALL_ROOT}/$ABI/lib/libhackrf.so ${DEPLOY_ROOT}/$ABI/libhackrf.so; \
done'


bash -lc 'set -euo pipefail; \
for ABI in ${ABIS}; do \
  echo "=== build SoapySDR for $ABI ==="; \
  mkdir -p ${BUILD_ROOT}/soapysdr-$ABI; \
  cmake -S /src/SoapySDR -B ${BUILD_ROOT}/soapysdr-$ABI \
    -DCMAKE_TOOLCHAIN_FILE=${TOOLCHAIN_FILE} \
    -DANDROID_ABI=$ABI \
    -DANDROID_PLATFORM=android-24 \
    -DBUILD_SHARED_LIBS=ON \
    -DCMAKE_BUILD_TYPE=Release \
    -DTHREADS_PREFER_PTHREAD_FLAG=ON \
    -DTHREADS_PTHREAD_ARG=-pthread \
    -DTHREADS_HAVE_PTHREAD_ARG=TRUE \
    -DCMAKE_INSTALL_PREFIX=${INSTALL_ROOT}/$ABI; \
  cmake --build ${BUILD_ROOT}/soapysdr-$ABI -j; \
  cmake --install ${BUILD_ROOT}/soapysdr-$ABI; \
  cp ${INSTALL_ROOT}/$ABI/lib/libSoapySDR.so ${DEPLOY_ROOT}/$ABI/libSoapySDR.so; \
done'

bash -lc 'set -euo pipefail; \
for ABI in ${ABIS}; do \
  echo "=== build SoapyHackRF for $ABI ==="; \
  mkdir -p ${BUILD_ROOT}/soapyhackrf-$ABI; \
  cmake -S /src/SoapyHackRF -B ${BUILD_ROOT}/soapyhackrf-$ABI \
    -DCMAKE_TOOLCHAIN_FILE=${TOOLCHAIN_FILE} \
    -DANDROID_ABI=$ABI \
    -DANDROID_PLATFORM=android-24 \
    -DCMAKE_BUILD_TYPE=Release \
    -DTHREADS_PREFER_PTHREAD_FLAG=ON \
    -DTHREADS_PTHREAD_ARG=-pthread \
    -DTHREADS_HAVE_PTHREAD_ARG=TRUE \
    -DHACKRF_LIBRARY=${INSTALL_ROOT}/$ABI/lib/libhackrf.so \
    -DHACKRF_INCLUDE_DIR=/src/hackrf/host/libhackrf/src \
    -DSoapySDR_DIR=${INSTALL_ROOT}/$ABI/share/cmake/SoapySDR \
    -DCMAKE_INSTALL_PREFIX=${INSTALL_ROOT}/$ABI; \
  cmake --build ${BUILD_ROOT}/soapyhackrf-$ABI -j; \
  cmake --install ${BUILD_ROOT}/soapyhackrf-$ABI; \
  cp ${INSTALL_ROOT}/$ABI/lib/SoapySDR/modules0.8-3/libHackRFSupport.so ${DEPLOY_ROOT}/$ABI/libHackRFSupport.so; \
done'

 === Build libatak.so from /deps/futuresdr/examples/atak and copy into public plugin jniLibs ===
bash -lc 'set -euo pipefail; \
for ABI in ${ABIS}; do \
  case "$ABI" in \
    arm64-v8a)   TRIPLE=aarch64-linux-android ; API=21 ;; \
    armeabi-v7a) TRIPLE=armv7-linux-androideabi ; API=21 ;; \
    x86)         TRIPLE=i686-linux-android ; API=21 ;; \
    *) echo "Unknown ABI: $ABI" >&2; exit 1 ;; \
  esac; \
  echo "=== cargo-ndk build libatak.so for $ABI (from private repo) ==="; \
  export SOAPYSDR_NO_PKG_CONFIG=1; \
  export SoapySDR_DIR=${INSTALL_ROOT}/$ABI; \
  export SOAPY_SDR_ROOT=${INSTALL_ROOT}/$ABI; \
  export SOAPYSDR_LIB_DIR=${INSTALL_ROOT}/$ABI/lib; \
  export SOAPYSDR_INCLUDE_DIR=${INSTALL_ROOT}/$ABI/include; \
  export BINDGEN_EXTRA_CLANG_ARGS="--sysroot=${ANDROID_NDK_HOME}/toolchains/llvm/prebuilt/linux-x86_64/sysroot \
    -I${INSTALL_ROOT}/$ABI/include -target ${TRIPLE}${API} -fsigned-char"; \
  cd ${RUST_CRATE_DIR}; \
  cargo ndk -t $ABI build --release --lib -p atak; \
  ${ANDROID_NDK_HOME}/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-strip -s target/${TRIPLE}/release/libatak.so || true; \
  cp -v target/${TRIPLE}/release/libatak.so ${DEPLOY_ROOT}/$ABI/libatak.so; \
done'
