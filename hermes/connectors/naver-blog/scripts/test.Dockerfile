FROM oven/bun:1.3.14
USER root
RUN apt-get update && apt-get install -y --no-install-recommends chromium ca-certificates \
    && rm -rf /var/lib/apt/lists/*
ENV FOS_TEST_CHROME=/usr/bin/chromium
