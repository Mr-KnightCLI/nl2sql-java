package io.nl2sql.core;

import io.nl2sql.core.examples.Example;
import io.nl2sql.core.examples.ExampleStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ExampleStoreTest {
    @TempDir
    Path dir;

    @Test
    void failedSaveDoesNotPublishAnExampleOrPersistItOnTheNextSave() throws Exception {
        Path parent = dir.resolve("missing");
        Path file = parent.resolve("examples.json");
        var store = new ExampleStore(file, Nl2SqlEngine.defaultJson());

        assertThatThrownBy(() -> store.add("failed shipment count", "SELECT COUNT(*) FROM shipments", ""))
                .isInstanceOf(Nl2SqlException.class)
                .hasMessageContaining("Cannot write examples file");
        assertThat(store.size()).isZero();
        assertThat(store.all()).isEmpty();
        assertThat(store.search("failed shipment count", 3)).isEmpty();
        assertThat(file).doesNotExist();

        Files.createDirectory(parent);
        store.add("successful invoice count", "SELECT COUNT(*) FROM invoices", "");

        var reloaded = new ExampleStore(file, Nl2SqlEngine.defaultJson());
        assertThat(store.all()).containsExactly(new Example("successful invoice count", "SELECT COUNT(*) FROM invoices", ""));
        assertThat(reloaded.all()).isEqualTo(store.all());
    }

    @Test
    void successfulSavesRetainExistingExamplesAndNormalizeInput() {
        Path file = dir.resolve("examples.json");
        var store = new ExampleStore(file, Nl2SqlEngine.defaultJson());
        store.add("  shipment count  ", "  SELECT COUNT(*) FROM shipments  ", null);
        store.add("invoice count", "SELECT COUNT(*) FROM invoices", "Only invoices");

        var reloaded = new ExampleStore(file, Nl2SqlEngine.defaultJson());
        assertThat(reloaded.all()).containsExactly(
                new Example("shipment count", "SELECT COUNT(*) FROM shipments", ""),
                new Example("invoice count", "SELECT COUNT(*) FROM invoices", "Only invoices"));
        assertThat(reloaded.search("shipment count", 1)).containsExactly(reloaded.all().get(0));
    }

    @Test
    void inMemoryStoreStillAcceptsExamplesWithoutAFile() {
        var store = new ExampleStore(null, Nl2SqlEngine.defaultJson());
        store.add("shipment count", "SELECT COUNT(*) FROM shipments", "");

        assertThat(store.size()).isEqualTo(1);
        assertThat(store.search("shipment count", 1)).containsExactly(store.all().get(0));
    }
}
