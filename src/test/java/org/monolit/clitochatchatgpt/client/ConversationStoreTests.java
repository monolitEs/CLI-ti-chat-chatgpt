package org.monolit.clitochatchatgpt.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.AtomicMoveNotSupportedException;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;

class ConversationStoreTests {

    @TempDir
    Path directory;

    private ConversationStore store(Path path) {
        return new ConversationStore(JsonMapper.builder().build(), path);
    }

    @Test
    void reloadsMultipleMappingsIncludingPathLikeKeysAfterRestart() throws IOException {
        var file = directory.resolve("nested/conversations.json");
        var original = store(file);
        assertThat(original.find("vk:a")).isNull();
        original.save("vk:a", "thr_a");
        original.save("../../vk:b", "thr_b");
        var restarted = store(file);
        assertThat(restarted.find("vk:a")).isEqualTo("thr_a");
        assertThat(restarted.find("../../vk:b")).isEqualTo("thr_b");
        assertThat(restarted.find("vk:c")).isNull();
        try (var files = Files.list(file.getParent())) {
            assertThat(files.toList()).containsExactly(file);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "{", "null", "[]", "{}",
            "{\"version\":2,\"conversations\":{}}", "{\"version\":4294967297,\"conversations\":{}}",
            "{\"version\":1,\"conversations\":[]}",
            "{\"version\":\"1\",\"conversations\":{}}",
            "{\"version\":1.0,\"conversations\":{}}",
            "{\"version\":true,\"conversations\":{}}",
            "{\"version\":null,\"conversations\":{}}",
            "{\"version\":1,\"conversations\":null}",
            "{\"version\":1,\"conversations\":{\"a\":123}}",
            "{\"version\":1,\"conversations\":{\"a\":1.5}}",
            "{\"version\":1,\"conversations\":{\"a\":true}}",
            "{\"version\":1,\"conversations\":{\"a\":null}}",
            "{\"version\":1,\"conversations\":{\"a\":\" \"}}",
            "{\"version\":1,\"conversations\":{\" \":\"thr_a\"}}"})
    void refusesToOverwriteCorruptStore(String content) throws IOException {
        var file = directory.resolve("conversations.json");
        Files.writeString(file, content);
        var store = store(file);
        assertThatThrownBy(() -> store.find("a")).isInstanceOf(Exception.class);
        assertThatThrownBy(() -> store.save("b", "thr_b")).isInstanceOf(Exception.class);
        assertThat(Files.readString(file)).isEqualTo(content);
    }

    @Test
    void refusesToRecreateStoreDeletedDuringProcessLifetime() throws IOException {
        var file = directory.resolve("conversations.json");
        var store = store(file);
        store.save("a", "thr_a");
        Files.delete(file);
        assertThatThrownBy(() -> store.find("a")).isInstanceOf(IOException.class);
        assertThatThrownBy(() -> store.save("b", "thr_b")).isInstanceOf(IOException.class);
        assertThat(file).doesNotExist();
    }

    @Test
    void unreadableReplacementTargetDoesNotOverwriteBackup() throws IOException {
        var file = directory.resolve("conversations.json");
        var store = store(file);
        store.save("a", "thr_a");
        var previous = Files.readString(file);
        var backup = directory.resolve("backup.json");
        Files.move(file, backup, StandardCopyOption.ATOMIC_MOVE);
        Files.createDirectory(file);
        Files.writeString(file.resolve("blocker"), "block");
        assertThatThrownBy(() -> store.save("b", "thr_b")).isInstanceOf(IOException.class);
        assertThat(Files.readString(backup)).isEqualTo(previous);
        try (var files = Files.list(directory)) {
            assertThat(files.map(p -> p.getFileName().toString()).toList())
                    .containsExactlyInAnyOrder("backup.json", "conversations.json");
        }
    }

    @Test
    void unsupportedAtomicMovePreservesPreviousMappingsAndCleansTemporaryFile() throws IOException {
        var file = directory.resolve("conversations.json");
        var store = store(file);
        store.save("a", "thr_a");
        var previous = Files.readString(file);
        try (var fileSystem = mockStatic(Files.class, CALLS_REAL_METHODS)) {
            fileSystem.when(() -> Files.move(any(Path.class), eq(file),
                            eq(StandardCopyOption.ATOMIC_MOVE), eq(StandardCopyOption.REPLACE_EXISTING)))
                    .thenThrow(new AtomicMoveNotSupportedException("temp", "target", "Unsupported"));
            assertThatThrownBy(() -> store.save("b", "thr_b"))
                    .isInstanceOf(AtomicMoveNotSupportedException.class);
        }
        assertThat(Files.readString(file)).isEqualTo(previous);
        assertThat(store.find("b")).isNull();
        try (var files = Files.list(directory)) {
            assertThat(files.toList()).containsExactly(file);
        }
    }
}
