ARG NODE_IMAGE=node@sha256:ba849c60be29959425b8734d57b8b4b7d56f98edd9504c9af091d5281095a71e
FROM ${NODE_IMAGE}
ADD jdk.tar.gz /opt/toolchain/
RUN mv /opt/toolchain/jdk-* /opt/jdk
ENV JAVA_HOME=/opt/jdk
ENV PATH="/opt/jdk/bin:${PATH}"
ADD npm.tar.gz /opt/npm/
RUN rm -rf /usr/local/lib/node_modules/npm && mv /opt/npm/package /usr/local/lib/node_modules/npm
RUN java -version 2>&1 | grep -F '25.0.4.1' && test "$(node --version)" = v24.20.0 && test "$(npm --version)" = 11.9.0
# Exact artifact/config/secret files are mounted by the registered launch command.
WORKDIR /app
ENTRYPOINT ["java"]
