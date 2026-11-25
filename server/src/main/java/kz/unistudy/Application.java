package kz.unistudy;

import java.nio.file.*;
import javax.sql.DataSource;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.sqlite.SQLiteConfig;
import org.sqlite.SQLiteDataSource;

@SpringBootApplication
public class Application {
  public static void main(String[] args) throws Exception {
    // Demo hosting only: when UNISTUDY_DEMO_INIT=1 and the database file does not exist yet,
    // create the synthetic demo database once before the server starts.
    String db = System.getenv("UNISTUDY_DB");
    if ("1".equals(System.getenv("UNISTUDY_DEMO_INIT")) && db != null && !Files.exists(Path.of(db))) {
      DemoBootstrap.main(new String[] {db});
    }
    SpringApplication.run(Application.class, args);
  }

  @Bean
  Path storage(Environment env) throws Exception {
    return Files.createDirectories(
        Path.of(env.getProperty("UNISTUDY_STORAGE", "storage/files")).toAbsolutePath().normalize());
  }

  @Bean
  DataSource dataSource(Environment env) throws Exception {
    Path path = Path.of(env.getProperty("UNISTUDY_DB", "private/unistudy.db")).toAbsolutePath();
    Files.createDirectories(path.getParent());
    SQLiteConfig config = new SQLiteConfig();
    config.enforceForeignKeys(true);
    config.setBusyTimeout(15000);
    config.setTransactionMode(SQLiteConfig.TransactionMode.IMMEDIATE);
    var source = new SQLiteDataSource(config);
    source.setUrl("jdbc:sqlite:" + path);
    new ResourceDatabasePopulator(new ClassPathResource("schema.sql")).execute(source);
    try (var c = source.getConnection();
        var st = c.createStatement()) {
      st.execute("PRAGMA foreign_keys=OFF");
      c.setAutoCommit(false);
      boolean found = false;
      try (var rs = st.executeQuery("PRAGMA table_info(subjects)")) {
        while (rs.next()) if ("external_links".equals(rs.getString("name"))) found = true;
      }
      if (!found)
        st.executeUpdate(
            "ALTER TABLE subjects ADD COLUMN external_links TEXT NOT NULL DEFAULT '[]'");
      st.executeUpdate("UPDATE users SET must_change_password=0 WHERE must_change_password<>0");
      boolean materialId = false;
      boolean requiredWeek = false;
      try (var rs = st.executeQuery("PRAGMA table_info(assessments)")) {
        while (rs.next()) {
          if ("material_id".equals(rs.getString("name"))) materialId = true;
          if ("week".equals(rs.getString("name"))) requiredWeek = rs.getInt("notnull") != 0;
        }
      }
      if (requiredWeek) {
        // Keep IDs and all grade entries. Do not rename the old table: SQLite
        // would rewrite child foreign keys to the temporary table name.
        st.executeUpdate("CREATE TABLE assessments_v53 (id INTEGER PRIMARY KEY,subject_id INTEGER NOT NULL REFERENCES subjects(id),group_id INTEGER NOT NULL REFERENCES groups(id),title VARCHAR(400) NOT NULL,date VARCHAR(10) NOT NULL,week INTEGER,kind VARCHAR(30) NOT NULL,version INTEGER NOT NULL,material_id INTEGER REFERENCES materials(id) ON DELETE SET NULL)");
        st.executeUpdate("INSERT INTO assessments_v53 SELECT id,subject_id,group_id,title,date,week,kind,version," + (materialId ? "material_id" : "NULL") + " FROM assessments");
        st.executeUpdate("DROP TABLE assessments");
        st.executeUpdate("ALTER TABLE assessments_v53 RENAME TO assessments");
        st.executeUpdate("CREATE INDEX ix_assessments_subject_id ON assessments(subject_id)");
        st.executeUpdate("CREATE INDEX ix_assessments_group_id ON assessments(group_id)");
      } else if (!materialId) {
        st.executeUpdate("ALTER TABLE assessments ADD COLUMN material_id INTEGER REFERENCES materials(id) ON DELETE SET NULL");
      }
      addColumn(st, "assessments", "schedule_id", "INTEGER REFERENCES schedule(id) ON DELETE SET NULL");
      addColumn(st, "assessments", "is_demo", "INTEGER NOT NULL DEFAULT 0");
      addColumn(st, "assessments", "source", "TEXT NOT NULL DEFAULT 'manual'");
      addColumn(st, "assessments", "source_key", "TEXT");
      addColumn(st, "grade_entries", "is_demo", "INTEGER NOT NULL DEFAULT 0");
      addColumn(st, "grade_entries", "source", "TEXT NOT NULL DEFAULT 'manual'");
      addColumn(st, "schedule", "shared_key", "TEXT");
      addColumn(st, "assessments", "archived", "INTEGER NOT NULL DEFAULT 0");
      addColumn(st, "assessments", "generated_origin", "INTEGER NOT NULL DEFAULT 0");
      addColumn(st, "assessments", "merged_into", "INTEGER");
      addColumn(st, "grade_entries", "generated_origin", "INTEGER NOT NULL DEFAULT 0");
      GradeMerge57.migrate(c);
      st.executeUpdate("CREATE UNIQUE INDEX IF NOT EXISTS ix_assessments_source_key ON assessments(source_key)");
      st.executeUpdate("INSERT OR IGNORE INTO schema_migrations(version) VALUES(54)");
      st.executeUpdate("CREATE INDEX IF NOT EXISTS ix_assessments_material_id ON assessments(material_id)");
      st.executeUpdate("UPDATE materials SET section=CASE WHEN kind IN ('lab','sro') THEN 'assignment' ELSE 'resource' END WHERE kind IN ('lab','sro','lecture','note','guide','exam') AND section<>CASE WHEN kind IN ('lab','sro') THEN 'assignment' ELSE 'resource' END");
      try (var rs = st.executeQuery("PRAGMA foreign_key_check")) {
        if (rs.next()) throw new IllegalStateException("Foreign key check failed after schema migration");
      }
      c.commit();
      c.setAutoCommit(true);
      st.execute("PRAGMA foreign_keys=ON");
    }
    return source;
  }
  private static void addColumn(java.sql.Statement st, String table, String name, String definition) throws java.sql.SQLException {
    boolean found = false;
    try (var rs = st.executeQuery("PRAGMA table_info(" + table + ")")) {
      while (rs.next()) if (name.equals(rs.getString("name"))) found = true;
    }
    if (!found) st.executeUpdate("ALTER TABLE " + table + " ADD COLUMN " + name + " " + definition);
  }
}
