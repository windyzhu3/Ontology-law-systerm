ARG NODE_IMAGE=node@sha256:ba849c60be29959425b8734d57b8b4b7d56f98edd9504c9af091d5281095a71e
FROM ${NODE_IMAGE}
# Extract both checksum-verified archives once, directly into their final paths.
# Moving extracted trees in a later layer duplicates them under the vfs driver.
ADD jdk.tar.gz npm.tar.gz /opt/toolchain/
ENV JAVA_HOME=/opt/toolchain/jdk-25.0.4.1+1
ENV PATH="/opt/toolchain/jdk-25.0.4.1+1/bin:${PATH}"
RUN ln -sf /opt/toolchain/package/bin/npm-cli.js /usr/local/bin/npm && \
    ln -sf /opt/toolchain/package/bin/npx-cli.js /usr/local/bin/npx && \
    java -version 2>&1 | grep -F '25.0.4.1' && test "$(node --version)" = v24.20.0 && test "$(npm --version)" = 11.9.0
# Exact artifact/config/secret files are mounted by the registered launch command.
WORKDIR /app
ENTRYPOINT ["java"]
