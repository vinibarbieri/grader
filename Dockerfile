FROM eclipse-temurin:25-jdk

RUN apt-get update \
    && DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends \
        bash \
        gcc \
        g++ \
        make \
        maven \
        procps \
        util-linux \
    && rm -rf /var/lib/apt/lists/*

ENV MAVEN_CONFIG=/var/maven/.m2

WORKDIR /workspace/backend

CMD ["bash"]
