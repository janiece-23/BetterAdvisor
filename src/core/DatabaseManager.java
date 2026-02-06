package core;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import java.lang.reflect.Field;
import java.sql.*;
import java.util.*;
import java.util.stream.Collectors;

public class DatabaseManager {

    private static DatabaseManager instance;
    private HikariDataSource dataSource;

    private static final String URL = "jdbc:mysql://localhost:3306/yourdb";
    private static final String USER = "user";
    private static final String PASSWORD = "password";

    /* ==========================
       Constructor / Lifecycle
       ========================== */

    private DatabaseManager() {
        initializeDatabase();
    }

    public static synchronized DatabaseManager getInstance() {
        if (instance == null) {
            instance = new DatabaseManager();
        }
        return instance;
    }

    private void initializeDatabase() {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(URL);
        config.setUsername(USER);
        config.setPassword(PASSWORD);
        config.setMaximumPoolSize(10);

        this.dataSource = new HikariDataSource(config);
    }

    public void shutdown() {
        if (dataSource != null) {
            dataSource.close();
        }
    }

    public void seedDatabase() {
        // Optional: insert default records
    }

    /* ==========================
       Core Execution
       ========================== */

    public <T> T executeQuery(String sql, QueryHandler<T> handler, Object[] params) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {

            setParameters(pstmt, params);
            ResultSet rs = pstmt.executeQuery();
            return handler.handle(rs);

        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public int executeUpdate(String sql, Object[] params) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {

            setParameters(pstmt, params);
            return pstmt.executeUpdate();

        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public int executeInsert(String sql, Object[] params) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement pstmt =
                     conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {

            setParameters(pstmt, params);
            pstmt.executeUpdate();

            ResultSet keys = pstmt.getGeneratedKeys();
            return keys.next() ? keys.getInt(1) : -1;

        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    /* ==========================
       Fetch Helpers
       ========================== */

    public <T> List<T> fetchList(String sql, QueryHandler<T> rowMapper, Object[] params) {
        return executeQuery(sql, rs -> {
            List<T> results = new ArrayList<>();
            while (rs.next()) {
                results.add(rowMapper.handle(rs));
            }
            return results;
        }, params);
    }

    public <T> T fetch(String sql, QueryHandler<T> rowMapper, Object[] params) {
        return executeQuery(sql, rs -> rs.next() ? rowMapper.handle(rs) : null, params);
    }

    /* ==========================
       ORM Fetching
       ========================== */

    public <T> T fetchOne(Class<T> clazz, String idColumn, Object idValue) {
        String sql = "SELECT * FROM " + clazz.getSimpleName()
                + " WHERE " + idColumn + " = ?";
        return fetch(sql, autoMapper(clazz), new Object[]{idValue});
    }

    public <T> List<T> fetchMany(Class<T> clazz, String fkColumn, Object value) {
        String sql = "SELECT * FROM " + clazz.getSimpleName()
                + " WHERE " + fkColumn + " = ?";
        return fetchList(sql, autoMapper(clazz), new Object[]{value});
    }

    public <T> List<T> fetchManyToMany(
            Class<T> targetClass,
            String joinTable,
            String joinCol,
            String invJoinCol,
            Object sourceId
    ) {
        String sql = "SELECT t.* FROM " + targetClass.getSimpleName() + " t "
                + "JOIN " + joinTable + " j ON t.id = j." + invJoinCol
                + " WHERE j." + joinCol + " = ?";

        return fetchList(sql, autoMapper(targetClass), new Object[]{sourceId});
    }

    private <T> QueryHandler<T> autoMapper(Class<T> clazz) {
        return rs -> {
            T instance = clazz.getDeclaredConstructor().newInstance();
            for (Field field : getAllAnnotatedFields(clazz)) {
                field.setAccessible(true);
                Object value = rs.getObject(field.getName());
                field.set(instance, value);
            }
            return instance;
        };
    }

    /* ==========================
       Persistence
       ========================== */

    public <T> void upsertAll(List<T> items) {
        items.forEach(this::upsert);
    }

    public <T> void upsert(T item) {
        // Build SQL dynamically using reflection
        // Execute PreparedStatement
    }

    public <T> void delete(T item) {
        // DELETE FROM table WHERE id = ?
    }

    public <T> void deleteAll(List<T> items) {
        items.forEach(this::delete);
    }

    private <T> void propagateGeneratedKeys(
            PreparedStatement pstmt,
            List<T> items,
            List<Field> localFields
    ) {
        // Map generated IDs back into objects
    }

    /* ==========================
       Reflection & SQL Builders
       ========================== */
