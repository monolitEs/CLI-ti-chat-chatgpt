package org.monolit.clitochatchatgpt.client;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.cfg.CoercionAction;
import tools.jackson.databind.cfg.CoercionInputShape;
import tools.jackson.databind.type.LogicalType;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.Map;

@Component
public class ConversationStore {

    private final ObjectMapper json;
    private final Path file;
    private boolean previouslyPresent;

    @Autowired
    public ConversationStore(ObjectMapper json, @Value("${CODEX_HOME:}") String home) {
        this(json, (home.isBlank() ? Path.of(System.getProperty("user.home"), ".codex") : Path.of(home))
                .resolve("chat-service/conversations.json"));
    }

    ConversationStore(ObjectMapper json, Path file) {
        this.json = json.rebuild()
                .withCoercionConfig(LogicalType.Textual, config -> config
                        .setCoercion(CoercionInputShape.Integer, CoercionAction.Fail)
                        .setCoercion(CoercionInputShape.Float, CoercionAction.Fail)
                        .setCoercion(CoercionInputShape.Boolean, CoercionAction.Fail))
                .withCoercionConfig(LogicalType.Integer, config -> config
                        .setCoercion(CoercionInputShape.String, CoercionAction.Fail)
                        .setCoercion(CoercionInputShape.Float, CoercionAction.Fail)
                        .setCoercion(CoercionInputShape.Boolean, CoercionAction.Fail))
                .build();
        this.file = file;
    }

    public synchronized String find(String conversationId) throws IOException {
        return read().get(conversationId);
    }

    public synchronized void save(String conversationId, String threadId) throws IOException {
        var mappings = read();
        mappings.put(conversationId, threadId);
        Files.createDirectories(file.getParent());
        var temporary = Files.createTempFile(file.getParent(), "conversations-", ".tmp");
        try {
            Files.writeString(temporary, json.writeValueAsString(new StoreData(1, mappings)));
            Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            previouslyPresent = true;
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private Map<String, String> read() throws IOException {
        String content;
        try {
            content = Files.readString(file);
            previouslyPresent = true;
        } catch (NoSuchFileException exception) {
            if (previouslyPresent) {
                throw exception;
            }
            return new HashMap<>();
        }
        var data = json.readValue(content, StoreData.class);
        if (data == null || data.version != 1 || data.conversations == null) {
            throw new IOException("Invalid conversation store format");
        }
        for (var entry : data.conversations.entrySet()) {
            var threadId = entry.getValue();
            if (entry.getKey().isBlank() || entry.getKey().length() > 256 || threadId == null || threadId.isBlank()) {
                throw new IOException("Invalid conversation mapping");
            }
        }
        return new HashMap<>(data.conversations);
    }

    @Getter
    @JsonIgnoreProperties(ignoreUnknown = true)
    private static final class StoreData {
        private final int version;
        private final Map<String, String> conversations;

        @JsonCreator
        private StoreData(@JsonProperty("version") int version,
                @JsonProperty("conversations") Map<String, String> conversations) {
            this.version = version;
            this.conversations = conversations;
        }
    }
}
