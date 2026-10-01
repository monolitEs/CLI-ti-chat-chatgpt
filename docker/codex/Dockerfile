FROM eclipse-temurin:21-jdk-jammy AS java-build

WORKDIR /workspace

COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN chmod 0755 mvnw && ./mvnw -B -DskipTests dependency:go-offline

COPY src/ src/
RUN ./mvnw -B -DskipTests package

FROM eclipse-temurin:21-jre-jammy AS java-runtime

FROM node:22.20.0-bookworm-slim

ARG CODEX_VERSION=0.159.3

ENV JAVA_HOME=/opt/java/openjdk
ENV PATH="${JAVA_HOME}/bin:${PATH}"

COPY --from=java-runtime /opt/java/openjdk /opt/java/openjdk
COPY --from=java-build /workspace/target/CLI-to-chat-chatgpt-0.0.1-SNAPSHOT.jar /opt/app/chat-service.jar

RUN set -eu; \
    apt-get update; \
    apt-get install --yes --no-install-recommends ca-certificates; \
    rm -rf /var/lib/apt/lists/*; \
    npm install --global "@openai/codex@${CODEX_VERSION}"; \
    npm cache clean --force; \
    useradd --create-home --uid 10001 --shell /usr/sbin/nologin codex; \
    mkdir -p /chat /opt/codex /home/codex/.codex; \
    printf '%s\n' \
      'approval_policy = "never"' \
      'sandbox_mode = "read-only"' \
      'forced_login_method = "chatgpt"' \
      'cli_auth_credentials_store = "file"' \
      'web_search = "disabled"' \
      'check_for_update_on_startup = false' \
      'file_opener = "none"' \
      'model_instructions_file = "/opt/codex/chat-instructions.md"' \
      '' \
      '[history]' \
      'persistence = "save-all"' \
      '' \
      '[features]' \
      'auth_elicitation = false' \
      'browser_use = false' \
      'browser_use_external = false' \
      'browser_use_full_cdp_access = false' \
      'code_mode_host = false' \
      'computer_use = false' \
      'goals = false' \
      'hooks = false' \
      'image_generation = false' \
      'in_app_browser = false' \
      'respect_system_proxy = true' \
      'shell_tool = false' \
      'shell_snapshot = false' \
      'skill_search = false' \
      'sleep_tool = false' \
      'tool_suggest = false' \
      'unified_exec = false' \
      'view_image = false' \
      'workspace_dependencies = false' \
      'skill_mcp_dependency_install = false' \
      'apps = false' \
      'plugins = false' \
      'multi_agent = false' \
      > /opt/codex/config.toml; \
    printf '%s\n' \
      'You are a conversational assistant.' \
      'Answer the user directly without inspecting files, running commands, using tools, or creating artifacts.' \
      > /opt/codex/chat-instructions.md; \
    printf '%s\n' \
      '#!/bin/sh' \
      'set -eu' \
      'umask 077' \
      'cp /opt/codex/config.toml "${CODEX_HOME}/config.toml"' \
      'if [ "$#" -gt 0 ]; then' \
      '  exec codex "$@"' \
      'fi' \
      'exec java -jar /opt/app/chat-service.jar' \
      > /usr/local/bin/codex-chat; \
    chmod 0755 /usr/local/bin/codex-chat; \
    chown -R codex:codex /chat /home/codex

USER codex
WORKDIR /chat

EXPOSE 8080
ENTRYPOINT ["codex-chat"]
