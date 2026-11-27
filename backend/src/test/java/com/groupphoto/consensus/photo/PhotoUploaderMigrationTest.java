package com.groupphoto.consensus.photo;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class PhotoUploaderMigrationTest {
    @TempDir
    Path database;

    @Test
    void upgradesMainV3AndRetainsExistingPhotosAndConfirmedSelections() {
        String url = "jdbc:h2:file:" + database.resolve("catalog") + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE";
        var dataSource = new DriverManagerDataSource(url, "sa", "");
        var jdbc = new JdbcTemplate(dataSource);
        var main = Flyway.configure().dataSource(dataSource).target("3").load();
        main.migrate();
        int originalChecksum = main.info().current().getChecksum();
        jdbc.update("INSERT INTO events (id, name) VALUES ('event', 'Migration test')");
        jdbc.update("INSERT INTO photos (id, event_id) VALUES ('photo', 'event')");
        jdbc.update("""
                INSERT INTO photo_versions (id, photo_id, event_id, version_number, media_type)
                VALUES ('version', 'photo', 'event', 1, 'image/png')
                """);
        jdbc.update("INSERT INTO confirmed_selections (id, event_id, purpose) VALUES ('selection', 'event', 'INTERNAL_SHARE')");
        jdbc.update("""
                INSERT INTO confirmed_selection_items (selection_id, sort_order, photo_id, photo_version_id, subject_revision)
                VALUES ('selection', 0, 'photo', 'version', 0)
                """);

        var upgraded = Flyway.configure().dataSource(dataSource).load();
        upgraded.migrate();
        upgraded.validate();

        assertThat(upgraded.info().applied()).anySatisfy(migration -> {
            assertThat(migration.getVersion().getVersion()).isEqualTo("3");
            assertThat(migration.getChecksum()).isEqualTo(originalChecksum);
        }).anySatisfy(migration -> {
            assertThat(migration.getVersion().getVersion()).isEqualTo("5");
            assertThat(migration.getScript()).isEqualTo("V5__photo_uploaders.sql");
        });
        assertThat(jdbc.queryForObject("SELECT uploader_id FROM photos WHERE id = 'photo'", String.class)).isNull();
        assertThat(jdbc.queryForObject("""
                SELECT photo_version_id FROM confirmed_selection_items WHERE selection_id = 'selection'
                """, String.class)).isEqualTo("version");
        assertThat(jdbc.queryForObject("SELECT photo_id FROM photo_versions WHERE id = 'version'", String.class)).isEqualTo("photo");
    }
}
