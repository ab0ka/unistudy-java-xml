CREATE TABLE IF NOT EXISTS groups (
	id INTEGER NOT NULL, 
	name VARCHAR(120) NOT NULL, 
	owner_id INTEGER, 
	PRIMARY KEY (id), 
	UNIQUE (name), 
	FOREIGN KEY(owner_id) REFERENCES users (id)
);
CREATE INDEX IF NOT EXISTS ix_groups_owner_id ON groups (owner_id);
CREATE TABLE IF NOT EXISTS users (
	id INTEGER NOT NULL, 
	full_name VARCHAR(200) NOT NULL, 
	username VARCHAR(80) NOT NULL, 
	password_hash VARCHAR(255) NOT NULL, 
	role VARCHAR(12) NOT NULL, 
	active BOOLEAN NOT NULL, 
	approved BOOLEAN NOT NULL, 
	must_change_password BOOLEAN NOT NULL, 
	group_id INTEGER, 
	auth_version INTEGER NOT NULL, 
	reset_hash VARCHAR(64), 
	reset_expires BIGINT, 
	source_key VARCHAR(64), 
	PRIMARY KEY (id), 
	UNIQUE (username), 
	FOREIGN KEY(group_id) REFERENCES groups (id), 
	UNIQUE (source_key)
);
CREATE INDEX IF NOT EXISTS ix_users_group_id ON users (group_id);
CREATE TABLE IF NOT EXISTS subjects (
	id INTEGER NOT NULL, 
	code VARCHAR(80) NOT NULL, 
	name VARCHAR(300) NOT NULL, 
	teacher_name VARCHAR(200) NOT NULL, 
	description TEXT NOT NULL, 
	group_ids JSON NOT NULL, 
	teacher_ids JSON NOT NULL, 
	source_key VARCHAR(64), 
	PRIMARY KEY (id), 
	UNIQUE (code), 
	UNIQUE (source_key)
);
CREATE TABLE IF NOT EXISTS auth_sessions (
	id INTEGER NOT NULL, 
	user_id INTEGER NOT NULL, 
	auth_version INTEGER NOT NULL, 
	access_hash VARCHAR(64) NOT NULL, 
	refresh_hash VARCHAR(64) NOT NULL, 
	access_expires BIGINT NOT NULL, 
	refresh_expires BIGINT NOT NULL, 
	PRIMARY KEY (id), 
	FOREIGN KEY(user_id) REFERENCES users (id) ON DELETE CASCADE, 
	UNIQUE (access_hash), 
	UNIQUE (refresh_hash)
);
CREATE INDEX IF NOT EXISTS ix_auth_sessions_user_id ON auth_sessions (user_id);
CREATE TABLE IF NOT EXISTS materials (
	id INTEGER NOT NULL, 
	subject_id INTEGER NOT NULL, 
	week INTEGER, 
	kind VARCHAR(15) NOT NULL, 
	title VARCHAR(400) NOT NULL, 
	description TEXT NOT NULL, 
	published BOOLEAN NOT NULL, 
	group_ids JSON NOT NULL, 
	created_at VARCHAR(40) NOT NULL, 
	deadline VARCHAR(40), 
	source_key VARCHAR(64), section VARCHAR(10) NOT NULL DEFAULT 'resource', 
	PRIMARY KEY (id), 
	FOREIGN KEY(subject_id) REFERENCES subjects (id), 
	UNIQUE (source_key)
);
CREATE INDEX IF NOT EXISTS ix_materials_subject_id ON materials (subject_id);
CREATE TABLE IF NOT EXISTS attachments (
	id INTEGER NOT NULL, 
	material_id INTEGER NOT NULL, 
	name VARCHAR(400) NOT NULL, 
	"key" VARCHAR(64) NOT NULL, 
	size BIGINT NOT NULL, 
	mime VARCHAR(150) NOT NULL, 
	PRIMARY KEY (id), 
	FOREIGN KEY(material_id) REFERENCES materials (id) ON DELETE CASCADE, 
	UNIQUE ("key")
);
CREATE INDEX IF NOT EXISTS ix_attachments_material_id ON attachments (material_id);
CREATE TABLE IF NOT EXISTS assessments (
	id INTEGER NOT NULL, 
	subject_id INTEGER NOT NULL, 
	group_id INTEGER NOT NULL, 
	title VARCHAR(400) NOT NULL, 
	date VARCHAR(10) NOT NULL, 
	week INTEGER NOT NULL, 
	kind VARCHAR(30) NOT NULL, 
	version INTEGER NOT NULL, 
	PRIMARY KEY (id), 
	FOREIGN KEY(subject_id) REFERENCES subjects (id), 
	FOREIGN KEY(group_id) REFERENCES groups (id)
);
CREATE INDEX IF NOT EXISTS ix_assessments_subject_id ON assessments (subject_id);
CREATE INDEX IF NOT EXISTS ix_assessments_group_id ON assessments (group_id);
CREATE TABLE IF NOT EXISTS schedule (
	id INTEGER NOT NULL, 
	subject_id INTEGER NOT NULL, 
	teacher_id INTEGER NOT NULL, 
	group_id INTEGER NOT NULL, 
	date VARCHAR(10) NOT NULL, 
	start_time VARCHAR(5) NOT NULL, 
	end_time VARCHAR(5) NOT NULL, 
	room VARCHAR(120) NOT NULL, 
	room_key VARCHAR(250) NOT NULL, 
	kind VARCHAR(30) NOT NULL, 
	week INTEGER NOT NULL, source VARCHAR(20) NOT NULL DEFAULT 'manual', 
	PRIMARY KEY (id), 
	FOREIGN KEY(subject_id) REFERENCES subjects (id), 
	FOREIGN KEY(teacher_id) REFERENCES users (id), 
	FOREIGN KEY(group_id) REFERENCES groups (id)
);
CREATE INDEX IF NOT EXISTS ix_schedule_teacher_id ON schedule (teacher_id);
CREATE INDEX IF NOT EXISTS ix_schedule_date ON schedule (date);
CREATE INDEX IF NOT EXISTS ix_schedule_group_id ON schedule (group_id);
CREATE TABLE IF NOT EXISTS submissions (
	id INTEGER NOT NULL, 
	material_id INTEGER NOT NULL, 
	student_id INTEGER NOT NULL, 
	text TEXT NOT NULL, 
	updated_at VARCHAR(40) NOT NULL, 
	PRIMARY KEY (id), 
	UNIQUE (material_id, student_id), 
	FOREIGN KEY(material_id) REFERENCES materials (id) ON DELETE CASCADE, 
	FOREIGN KEY(student_id) REFERENCES users (id)
);
CREATE INDEX IF NOT EXISTS ix_submissions_student_id ON submissions (student_id);
CREATE INDEX IF NOT EXISTS ix_submissions_material_id ON submissions (material_id);
CREATE TABLE IF NOT EXISTS grade_entries (
	assessment_id INTEGER NOT NULL, 
	student_id INTEGER NOT NULL, 
	value VARCHAR(3) NOT NULL, 
	PRIMARY KEY (assessment_id, student_id), 
	FOREIGN KEY(assessment_id) REFERENCES assessments (id) ON DELETE CASCADE, 
	FOREIGN KEY(student_id) REFERENCES users (id)
);
CREATE TABLE IF NOT EXISTS submission_files (
	id INTEGER NOT NULL, 
	submission_id INTEGER NOT NULL, 
	name VARCHAR(400) NOT NULL, 
	"key" VARCHAR(64) NOT NULL, 
	size BIGINT NOT NULL, 
	mime VARCHAR(150) NOT NULL, 
	PRIMARY KEY (id), 
	FOREIGN KEY(submission_id) REFERENCES submissions (id) ON DELETE CASCADE, 
	UNIQUE ("key")
);
CREATE INDEX IF NOT EXISTS ix_submission_files_submission_id ON submission_files (submission_id);
CREATE TABLE IF NOT EXISTS schema_migrations (version INTEGER PRIMARY KEY);
CREATE TABLE IF NOT EXISTS credential_migrations (version INTEGER PRIMARY KEY);
CREATE TABLE IF NOT EXISTS import_runs (
	"key" VARCHAR(100) NOT NULL, 
	report JSON NOT NULL, 
	PRIMARY KEY ("key")
);
CREATE TABLE IF NOT EXISTS grade_components (
 subject_id INTEGER NOT NULL REFERENCES subjects(id), group_id INTEGER NOT NULL REFERENCES groups(id),
 student_id INTEGER NOT NULL REFERENCES users(id), rk1 INTEGER, rk2 INTEGER, exam INTEGER,
 version INTEGER NOT NULL DEFAULT 0,
 PRIMARY KEY(subject_id,group_id,student_id),
 CHECK(rk1 IS NULL OR rk1 BETWEEN 0 AND 100), CHECK(rk2 IS NULL OR rk2 BETWEEN 0 AND 100), CHECK(exam IS NULL OR exam BETWEEN 0 AND 100)
);
CREATE TABLE IF NOT EXISTS grade_audit (
 id INTEGER PRIMARY KEY, assessment_id INTEGER, subject_id INTEGER NOT NULL,
 group_id INTEGER NOT NULL, student_id INTEGER NOT NULL, component TEXT NOT NULL,
 old_value TEXT, new_value TEXT, changed_by INTEGER NOT NULL, changed_at TEXT NOT NULL
);
CREATE INDEX IF NOT EXISTS ix_grade_audit_scope ON grade_audit(subject_id,group_id,student_id);

CREATE TABLE IF NOT EXISTS grade_merge_archive (version INTEGER PRIMARY KEY, created_at TEXT NOT NULL, data TEXT NOT NULL);
