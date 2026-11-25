package kz.unistudy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.sqlite.SQLiteConfig;
import org.sqlite.SQLiteDataSource;

/** Explicit local demo initializer; never runs as part of server startup. */
public final class DemoBootstrap {
  private DemoBootstrap() {}

  public static void main(String[] args) throws Exception {
    if (args.length != 1) throw new IllegalArgumentException("Pass the path of a NEW demo database.");
    Path target = Path.of(args[0]).toAbsolutePath().normalize();
    Files.createDirectories(target.getParent());
    // Atomic CREATE_NEW: an existing database, even an empty one, is never overwritten.
    Files.createFile(target);
    SQLiteConfig config = new SQLiteConfig();
    config.enforceForeignKeys(true);
    SQLiteDataSource source = new SQLiteDataSource(config);
    source.setUrl("jdbc:sqlite:" + target);
    new ResourceDatabasePopulator(new ClassPathResource("schema.sql")).execute(source);
    var encoder = new Argon2PasswordEncoder(16, 32, 4, 65536, 3);
    try (Connection c = source.getConnection()) {
      c.setAutoCommit(false);
      try {
        execute(c, "ALTER TABLE subjects ADD COLUMN external_links TEXT NOT NULL DEFAULT '[]'");
        execute(c, "INSERT INTO users(id,full_name,username,password_hash,role,active,approved,must_change_password,auth_version) VALUES(?,?,?,?,?,1,1,0,0)",
            1, "Demo Admin", "demo-admin", encoder.encode("DemoAdmin123!"), "admin");
        execute(c, "INSERT INTO users(id,full_name,username,password_hash,role,active,approved,must_change_password,auth_version) VALUES(?,?,?,?,?,1,1,0,0)",
            2, "Demo Teacher", "demo-teacher", encoder.encode("DemoTeacher123!"), "teacher");
        execute(c, "INSERT INTO groups(id,name,owner_id) VALUES(1,'DEMO-101',2)");
        execute(c, "INSERT INTO users(id,full_name,username,password_hash,role,group_id,active,approved,must_change_password,auth_version) VALUES(?,?,?,?,?,1,1,1,0,0)",
            3, "Demo Student", "demo-student", encoder.encode("DemoStudent123!"), "student");
        execute(c, "INSERT INTO subjects(id,code,name,teacher_name,description,group_ids,teacher_ids) VALUES(1,'DEMO-JAVA','Java Demo','Demo Teacher','Synthetic demonstration course','[1]','[2]')");
        execute(c, "INSERT INTO materials(id,subject_id,week,kind,title,description,published,group_ids,created_at,section) VALUES(1,1,1,'lecture','Welcome to Java','Synthetic sample: create a class and print Hello World.',1,'[1]',?,'resource')", java.time.Instant.now().toString());
        c.commit();
      } catch (Exception e) {
        c.rollback();
        throw e;
      }
    }
    System.out.println("Created synthetic demo database: " + target);
    System.out.println("Demo credentials are documented in README.md. Do not expose this demo publicly.");
  }

  private static void execute(Connection c, String sql, Object... values) throws Exception {
    try (var statement = c.prepareStatement(sql)) {
      for (int i = 0; i < values.length; i++) statement.setObject(i + 1, values[i]);
      statement.executeUpdate();
    }
  }
}
