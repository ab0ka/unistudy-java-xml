package kz.unistudy.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeUnit;
import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okio.BufferedSink;

/**
 * Synchronous API for worker threads. Every operation captures endpoint/user/generation;
 * an old response can never replace a newer login or cross an account's offline cache.
 * Refresh is serialized separately from the state monitor, so network I/O never blocks
 * logout/configuration on that monitor. Persisted v2 credentials and downloads are retained.
 */
public final class Api {
    public static final class Error extends Exception {
        public final int status;
        public Error(int status, String message) { super(message); this.status = status; }
    }
    public static final class LocalFile {
        public final JSONObject meta;
        public final File file;
        public final String title;
        public LocalFile(JSONObject meta, File file, String title) {
            this.meta = meta; this.file = file; this.title = title;
        }
    }
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");
    private static final long UPLOAD_LIMIT = 100L * 1024 * 1024;
    private final Context context;
    private final SharedPreferences config;
    private final SecureStore secure;
    private final Object refreshLock = new Object();
    private final Object downloadLock = new Object();
    private final OkHttpClient client = new OkHttpClient.Builder()
            .connectTimeout(12, TimeUnit.SECONDS).readTimeout(90, TimeUnit.SECONDS).build();
    private String endpoint;
    private JSONObject session;
    private long epoch;

    private final OkHttpClient connectionProbe = client.newBuilder()
            .callTimeout(7, TimeUnit.SECONDS).followRedirects(false).build();

    public void testConnection(String url) throws Exception {
        if (!Rules.validEndpoint(url)) throw new IllegalArgumentException("Сервер адресі дұрыс емес");
        Request request = new Request.Builder().url(url.replaceAll("/+$", "") + "/api/health").build();
        try (Response response = connectionProbe.newCall(request).execute()) {
            if (response.isSuccessful() && response.body() != null) {
                try {
                    if ("ok".equals(new JSONObject(response.body().string()).optString("status"))) return;
                } catch (org.json.JSONException invalidBody) {
                    // An HTTP response alone does not identify a UniStudy server.
                }
            }
            throw new Error(response.code(), "UniStudy сервері жауап бермеді. Адресті және сервердің іске қосылғанын тексеріңіз.");
        }
    }

    public static String userMessage(Exception failure) {
        if (failure instanceof javax.net.ssl.SSLException) {
            return "Қауіпсіз қосылым орнамады. Сервердің HTTPS адресі мен сертификатын тексеріңіз.";
        }
        if (failure instanceof java.io.InterruptedIOException) {
            return "Серверден жауап күту уақыты аяқталды. Желіні тексеріп, қайта көріңіз.";
        }
        if (failure instanceof java.net.UnknownHostException || failure instanceof java.net.ConnectException
                || failure instanceof java.net.NoRouteToHostException || failure instanceof java.net.SocketException
                || failure instanceof java.net.ProtocolException || failure instanceof java.net.UnknownServiceException) {
            return "Серверге қосыла алмадық. Желіні тексеріңіз немесе «Сервер баптауы» бөлімінде қосылымды тексеріңіз.";
        }
        if (failure instanceof IOException) {
            return "Деректерді жіберу немесе оқу мүмкін болмады. Қайта көріңіз.";
        }
        return failure.getMessage() == null ? "Сұрау орындалмады" : failure.getMessage();
    }

    public Api(Context context) {
        this.context = context.getApplicationContext();
        config = this.context.getSharedPreferences("config", Context.MODE_PRIVATE);
        secure = new SecureStore(this.context);
        endpoint = config.getString("endpoint", "http://10.0.2.2:8000");
        try { String raw = secure.read(); session = raw == null ? null : new JSONObject(raw); }
        catch (Exception invalidSession) { secure.clear(); }
    }
    public synchronized JSONObject user() {
        JSONObject value = session == null ? null : session.optJSONObject("user");
        if (value == null) return null;
        try { return new JSONObject(value.toString()); }
        catch (Exception impossible) { throw new IllegalStateException(impossible); }
    }
    public synchronized String endpoint() { return endpoint; }
    public synchronized long generation() { return epoch; }
    public synchronized void configure(String url) {
        if (!Rules.validEndpoint(url)) throw new IllegalArgumentException("Сервер адресі дұрыс емес");
        clearSession();
        endpoint = url.trim().replaceAll("/+$", "");
        config.edit().putString("endpoint", endpoint).apply();
    }
    public synchronized void clearSession() { epoch++; session = null; secure.clear(); }
    private void saveSession(JSONObject value) throws Exception {
        secure.save(value.toString());
        session = value;
    }
    private static final class Scope {
        final long generation;
        final String endpoint, token, refresh;
        final int userId;
        Scope(long generation, String endpoint, String token, String refresh, int userId) {
            this.generation = generation; this.endpoint = endpoint; this.token = token;
            this.refresh = refresh; this.userId = userId;
        }
    }
    private static String string(JSONObject value, String key) {
        return value == null || value.isNull(key) ? "" : value.optString(key, "");
    }
    private synchronized Scope scope() {
        JSONObject user = session == null ? null : session.optJSONObject("user");
        return new Scope(epoch, endpoint, string(session, "access_token"),
                string(session, "refresh_token"), user == null ? 0 : user.optInt("id"));
    }
    private synchronized void requireCurrent(Scope scope) {
        if (epoch != scope.generation) throw new CancellationException("Session changed");
    }
    private Request.Builder builder(Scope scope, String path, boolean auth) {
        if (!path.startsWith("/") || path.startsWith("//")) throw new IllegalArgumentException("Invalid API path");
        Request.Builder builder = new Request.Builder().url(scope.endpoint + "/api" + path);
        if (auth && !scope.token.isEmpty()) builder.header("Authorization", "Bearer " + scope.token);
        return builder;
    }
    private Error error(Response response) throws IOException {
        String raw = response.body() == null ? "" : response.body().string();
        String message = "Сервер қатесі (" + response.code() + ")";
        try { message = new JSONObject(raw).get("detail").toString(); } catch (Exception ignored) { }
        return new Error(response.code(), message);
    }
    private Scope refresh(Scope failed) throws Exception {
        synchronized (refreshLock) {
            final Scope current;
            synchronized (this) { requireCurrent(failed); current = scope(); }
            if (!current.token.equals(failed.token)) return current;
            if (current.refresh.isEmpty()) {
                synchronized (this) { requireCurrent(current); clearSession(); }
                throw new Error(401, "Сессия аяқталды. Қайта кіріңіз.");
            }
            JSONObject body = new JSONObject().put("refresh_token", current.refresh);
            Request req = builder(current, "/auth/refresh", false)
                    .post(RequestBody.create(body.toString(), JSON)).build();
            try (Response response = client.newCall(req).execute()) {
                if (!response.isSuccessful()) {
                    Error failure = error(response);
                    synchronized (this) {
                        requireCurrent(current);
                        if (failure.status == 401 || failure.status == 403) clearSession();
                    }
                    throw failure.status == 403 ? new Error(401, failure.getMessage()) : failure;
                }
                JSONObject value = new JSONObject(response.body().string());
                synchronized (this) { requireCurrent(current); saveSession(value); return scope(); }
            }
        }
    }
    private interface RequestFactory { Request make(Scope scope) throws Exception; }
    private Response execute(Scope captured, boolean auth, RequestFactory factory) throws Exception {
        requireCurrent(captured);
        Response response = client.newCall(factory.make(captured)).execute();
        if (response.code() != 401 || !auth) return response;
        response.close();
        if (captured.token.isEmpty()) {
            synchronized (this) { requireCurrent(captured); clearSession(); }
            throw new Error(401, "Сессия аяқталды. Қайта кіріңіз.");
        }
        Scope renewed = refresh(captured);
        requireCurrent(captured);
        Response retried = client.newCall(factory.make(renewed)).execute();
        if (retried.code() == 401) {
            Error expired;
            try { expired = error(retried); } finally { retried.close(); }
            synchronized (this) { requireCurrent(captured); clearSession(); }
            throw expired;
        }
        return retried;
    }
    private String read(Scope captured, String path, String method, JSONObject body, boolean auth) throws Exception {
        try {
            String raw;
            try (Response response = execute(captured, auth, current -> builder(current, path, auth)
                    .method(method, method.equals("GET") || method.equals("HEAD") ? null
                            : RequestBody.create(body == null ? "{}" : body.toString(), JSON)).build())) {
                if (!response.isSuccessful()) throw error(response);
                raw = response.body() == null ? "" : response.body().string();
            }
            requireCurrent(captured);
            return raw;
        } catch (Exception failure) {
            // A refresh deliberately clearing this session must still reach the sign-in UI.
            if (!(failure instanceof Error) || ((Error) failure).status != 401 || user() != null) requireCurrent(captured);
            throw failure;
        }
    }
    public String request(String path, String method, JSONObject body, boolean auth) throws Exception {
        return read(scope(), path, method, body, auth);
    }
    public JSONObject object(String path, String method, JSONObject body) throws Exception {
        String raw = request(path, method, body, true);
        return raw.trim().isEmpty() ? new JSONObject() : new JSONObject(raw);
    }
    public JSONArray array(String path) throws Exception { return new JSONArray(request(path, "GET", null, true)); }
    public JSONArray publicGroups() throws Exception { return new JSONArray(request("/groups", "GET", null, false)); }
    public JSONObject login(String username, String password) throws Exception {
        Scope captured = scope();
        JSONObject value = new JSONObject(read(captured, "/auth/login", "POST",
                new JSONObject().put("username", username).put("password", password), false));
        synchronized (this) { requireCurrent(captured); saveSession(value); epoch++; return user(); }
    }
    public JSONObject me() throws Exception {
        Scope captured = scope();
        JSONObject value = new JSONObject(read(captured, "/auth/me", "GET", null, true));
        synchronized (this) {
            requireCurrent(captured);
            if (session != null) saveSession(new JSONObject(session.toString()).put("user", value));
            return value;
        }
    }
    public void logout() throws Exception {
        Scope captured = scope();
        synchronized (refreshLock) {
            Scope current;
            synchronized (this) { requireCurrent(captured); current = scope(); }
            try { read(current, "/auth/logout", "POST", new JSONObject().put("refresh_token", current.refresh), false); }
            finally { synchronized (this) { if (epoch == captured.generation) clearSession(); } }
        }
    }
    private static String hash(String value) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        StringBuilder result = new StringBuilder();
        for (byte b : digest) result.append(String.format(Locale.ROOT, "%02x", b & 255));
        return result.toString();
    }
    private File folder(Scope captured) throws Exception {
        File dir = new File(context.getFilesDir(), "offline/" + hash(captured.endpoint) + "/" + captured.userId);
        if (!dir.isDirectory() && !dir.mkdirs() && !dir.isDirectory()) throw new IOException("Кэш бумасы ашылмады");
        return dir;
    }
    private static String readFile(File file) throws IOException {
        return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
    }
    private static void writeFile(File file, String value) throws IOException {
        File temp = File.createTempFile("cache-", ".part", file.getParentFile());
        try {
            Files.write(temp.toPath(), value.getBytes(StandardCharsets.UTF_8));
            Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING);
        } finally { temp.delete(); }
    }
    private JSONArray cachedRequest(String path, String name) throws Exception {
        Scope captured = scope();
        String raw = read(captured, path, "GET", null, true);
        JSONArray result = new JSONArray(raw);
        synchronized (this) { requireCurrent(captured); writeFile(new File(folder(captured), name), raw); }
        return result;
    }
    public JSONArray subjects() throws Exception { return cachedRequest("/subjects", "subjects.json"); }
    public static final class MaterialList {
        final JSONArray rows;
        final boolean offline;
        MaterialList(JSONArray rows, boolean offline) { this.rows = rows; this.offline = offline; }
    }
    private long materialRequestSequence, lastSavedMaterialSequence;
    public MaterialList allMaterials() throws Exception {
        Scope captured;
        long sequence;
        String name;
        synchronized (this) {
            captured = scope();
            sequence = ++materialRequestSequence;
            JSONObject account = user();
            if (account == null) throw new Error(401, "Сессия аяқталды");
            name = "materials-" + account.optString("role") + "-" + account.optInt("group_id") + "-all.json";
        }
        String raw;
        try {
            raw = read(captured, "/materials", "GET", null, true);
        } catch (IOException offline) {
            synchronized (this) {
                requireCurrent(captured);
                File snapshot = new File(folder(captured), name);
                if (!snapshot.isFile()) throw offline;
                return new MaterialList(new JSONArray(readFile(snapshot)), true);
            }
        }
        JSONArray rows = new JSONArray(raw);
        synchronized (this) {
            requireCurrent(captured);
            if (sequence > lastSavedMaterialSequence) {
                writeFile(new File(folder(captured), name), raw);
                lastSavedMaterialSequence = sequence;
            }
        }
        return new MaterialList(rows, false);
    }
    public JSONArray materials(int subjectId) throws Exception {
        return cachedRequest("/subjects/" + subjectId + "/materials", "materials-" + subjectId + ".json");
    }
    private synchronized JSONArray cached(String name) {
        try { return new JSONArray(readFile(new File(folder(scope()), name))); }
        catch (Exception absentOrInvalid) { return new JSONArray(); }
    }
    public JSONArray cachedSubjects() { return cached("subjects.json"); }
    public JSONArray cachedMaterials(int subjectId) { return cached("materials-" + subjectId + ".json"); }
    private static String downloadPath(JSONObject file) {
        String path = string(file, "download_path");
        return path.isEmpty() ? "/files/" + file.optInt("id") + "/download" : path;
    }
    public LocalFile download(JSONObject file, String title) throws Exception {
        Scope captured = scope();
        synchronized (downloadLock) {
            requireCurrent(captured);
            File dir = folder(captured);
            String path = downloadPath(file), key = hash(path), name = string(file, "name");
            int dot = name.lastIndexOf('.');
            String extension = dot < 0 ? "bin" : name.substring(dot + 1).replaceAll("[^a-zA-Z0-9]", "");
            if (extension.isEmpty()) extension = "bin";
            if (extension.length() > 10) extension = extension.substring(0, 10);
            File target = new File(dir, "file-" + key + "." + extension);
            File part = File.createTempFile("download-", ".part", dir);
            try {
                try (Response response = execute(captured, true, current -> builder(current, path, true).get().build())) {
                    if (!response.isSuccessful()) throw error(response);
                    if (response.body() == null) throw new IOException("Файл бос");
                    try (InputStream input = response.body().byteStream(); FileOutputStream output = new FileOutputStream(part)) {
                        byte[] buffer = new byte[65536];
                        int count;
                        while ((count = input.read(buffer)) != -1) {
                            requireCurrent(captured);
                            output.write(buffer, 0, count);
                        }
                    }
                }
                JSONObject meta = new JSONObject(file.toString()).put("download_path", path)
                        .put("local", target.getName()).put("material_title", title);
                synchronized (this) {
                    requireCurrent(captured);
                    Files.move(part.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
                    writeFile(new File(dir, "download-" + key + ".json"), meta.toString());
                }
                return new LocalFile(meta, target, title);
            } finally { part.delete(); }
        }
    }
    public synchronized List<LocalFile> downloads() {
        List<LocalFile> result = new ArrayList<>();
        try {
            File dir = folder(scope());
            File[] entries = dir.listFiles((parent, name) -> name.startsWith("download-") && name.endsWith(".json"));
            if (entries == null) return result;
            // Read all legacy filename styles; only new writes use the full path SHA-256 key.
            for (File entry : entries) {
                try {
                    JSONObject meta = new JSONObject(readFile(entry));
                    File file = new File(dir, string(meta, "local"));
                    if (!dir.getCanonicalFile().equals(file.getCanonicalFile().getParentFile()) || !file.isFile()) continue;
                    meta.put("download_path", downloadPath(meta));
                    result.add(new LocalFile(meta, file, string(meta, "material_title")));
                } catch (Exception invalidEntry) { /* Keep intact, but never expose an unsafe path. */ }
            }
        } catch (Exception unreadableCache) { }
        return result;
    }
    public synchronized void deleteDownload(LocalFile download) throws Exception {
        File dir = folder(scope()).getCanonicalFile();
        File file = download.file.getCanonicalFile();
        if (!dir.equals(file.getParentFile())) throw new IOException("Файл басқа аккаунтқа тиесілі");
        if (file.exists() && !file.delete()) throw new IOException("Файл өшірілмеді");
        File[] entries = dir.listFiles((parent, name) -> name.startsWith("download-") && name.endsWith(".json"));
        if (entries != null) for (File entry : entries) {
            try {
                JSONObject meta = new JSONObject(readFile(entry));
                if (new File(dir, string(meta, "local")).getCanonicalFile().equals(file)) {
                    if (!entry.delete()) throw new IOException("Файл ақпараты өшірілмеді");
                }
            } catch (org.json.JSONException invalidLegacyMetadata) { }
        }
    }
    public JSONObject upload(String path, Uri uri) throws Exception {
        Scope captured = scope();
        String displayName = "attachment";
        long queriedSize = -1;
        try (Cursor cursor = context.getContentResolver().query(uri, null, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int nameColumn = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (nameColumn >= 0 && !cursor.isNull(nameColumn)) displayName = cursor.getString(nameColumn);
                int sizeColumn = cursor.getColumnIndex(OpenableColumns.SIZE);
                if (sizeColumn >= 0 && !cursor.isNull(sizeColumn)) queriedSize = cursor.getLong(sizeColumn);
            }
        }
        if (queriedSize > UPLOAD_LIMIT) throw new IllegalArgumentException("Файл 100 МБ-тан аспауы керек");
        final long size = queriedSize;
        String type = context.getContentResolver().getType(uri);
        final MediaType mime = MediaType.parse(type == null ? "application/octet-stream" : type);
        RequestBody data = new RequestBody() {
            @Override public MediaType contentType() { return mime; }
            @Override public long contentLength() { return size > 0 ? size : -1; }
            @Override public void writeTo(BufferedSink sink) throws IOException {
                try (InputStream input = context.getContentResolver().openInputStream(uri)) {
                    if (input == null) throw new IOException("Файл ашылмады");
                    byte[] buffer = new byte[65536];
                    long copied = 0;
                    int count;
                    while ((count = input.read(buffer)) != -1) {
                        requireCurrent(captured);
                        copied += count;
                        if (copied > UPLOAD_LIMIT) throw new IOException("Файл 100 МБ-тан аспауы керек");
                        sink.write(buffer, 0, count);
                    }
                }
            }
        };
        RequestBody multipart = new MultipartBody.Builder().setType(MultipartBody.FORM)
                .addFormDataPart("file", displayName, data).build();
        String raw;
        try (Response response = execute(captured, true, current -> builder(current, path, true).post(multipart).build())) {
            if (!response.isSuccessful()) throw error(response);
            raw = response.body() == null ? "" : response.body().string();
        }
        requireCurrent(captured);
        return raw.trim().isEmpty() ? new JSONObject() : new JSONObject(raw);
    }
}
