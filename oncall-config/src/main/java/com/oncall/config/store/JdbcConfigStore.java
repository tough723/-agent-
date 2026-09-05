package com.oncall.config.store;

import com.oncall.config.ConfigStore;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * 基于 JDBC 的 {@link ConfigStore} 实现。
 *
 * <p>只用 JDK 自带的 {@code javax.sql} / {@code java.sql}，因此 {@code oncall-config}
 * 的生产代码仍然零外部依赖（H2 只在测试作用域出现）。
 *
 * <p><b>只存"被改过"的键</b>：默认值留在 {@code ConfigSpec} 声明里。
 * 这样"哪些参数被改离了基线"一条 SQL 就能查出来，也能整体清空以回到出厂状态。
 *
 * <p><b>删除用墓碑而不是 DELETE</b>：{@link #remove} 把值置 NULL 但保留行。
 * 因为 {@link #revision()} 取的是 {@code MAX(revision)}，真删行会让修订号倒退，
 * 导致 {@code ConfigService} 的快照缓存误判为"没变化"而读到旧值。
 * 墓碑行的数量上限就是配置项总数，不会无限增长。
 *
 * <p><b>upsert 不用方言语法</b>：H2 的 {@code MERGE INTO} 与 PostgreSQL 的
 * {@code ON CONFLICT} 不通用，这里先 UPDATE 再按影响行数决定 INSERT，
 * 并对主键冲突做一次重试兜底。配置写入频率极低，这点开销无所谓。
 */
public final class JdbcConfigStore implements ConfigStore {

    /** 建表 DDL。生产环境应改由 Flyway 管理（见 db/migration），此方法供测试与首次启动使用。 */
    public static final String CREATE_TABLE_SQL = """
            CREATE TABLE IF NOT EXISTS %s (
                config_key   VARCHAR(191)  NOT NULL PRIMARY KEY,
                config_value VARCHAR(2000),
                revision     BIGINT        NOT NULL,
                updated_at   TIMESTAMP     NOT NULL,
                updated_by   VARCHAR(64)
            )
            """;

    private final DataSource dataSource;
    private final String table;

    public JdbcConfigStore(DataSource dataSource) {
        this(dataSource, "app_config");
    }

    public JdbcConfigStore(DataSource dataSource, String table) {
        if (dataSource == null) {
            throw new IllegalArgumentException("dataSource 不能为 null");
        }
        this.dataSource = dataSource;
        this.table = table;
    }

    /** 建表（幂等）。测试与首次启动用；生产建议交给 Flyway。 */
    public void createSchemaIfMissing() {
        try (Connection c = dataSource.getConnection(); Statement st = c.createStatement()) {
            st.execute(String.format(CREATE_TABLE_SQL, table));
        } catch (SQLException e) {
            throw new IllegalStateException("建表失败：" + table, e);
        }
    }

    @Override
    public Optional<String> get(String key) {
        String sql = "SELECT config_value FROM " + table
                + " WHERE config_key = ? AND config_value IS NOT NULL";
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, key);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.ofNullable(rs.getString(1)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("读取配置失败：" + key, e);
        }
    }

    @Override
    public Map<String, String> getAll() {
        String sql = "SELECT config_key, config_value FROM " + table
                + " WHERE config_value IS NOT NULL ORDER BY config_key";
        Map<String, String> out = new LinkedHashMap<>();
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                out.put(rs.getString(1), rs.getString(2));
            }
        } catch (SQLException e) {
            throw new IllegalStateException("读取全部配置失败", e);
        }
        return out;
    }

    @Override
    public void put(String key, String value) {
        if (key == null || value == null) {
            throw new IllegalArgumentException("key/value 不能为 null");
        }
        write(key, value);
    }

    /** 恢复默认：置为墓碑行（值 NULL），行保留以维持 revision 单调。 */
    @Override
    public void remove(String key) {
        if (key == null) {
            throw new IllegalArgumentException("key 不能为 null");
        }
        write(key, null);
    }

    @Override
    public long revision() {
        String sql = "SELECT COALESCE(MAX(revision), 0) FROM " + table;
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            return rs.next() ? rs.getLong(1) : 0L;
        } catch (SQLException e) {
            throw new IllegalStateException("读取配置修订号失败", e);
        }
    }

    /** 当前被显式覆盖的键数量（不含墓碑行）。供健康检查与前端"已改 N 项"提示。 */
    public int overriddenCount() {
        String sql = "SELECT COUNT(*) FROM " + table + " WHERE config_value IS NOT NULL";
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            return rs.next() ? rs.getInt(1) : 0;
        } catch (SQLException e) {
            throw new IllegalStateException("统计覆盖项失败", e);
        }
    }

    // ------------------------------------------------------------------ 内部

    private void write(String key, String value) {
        long next = nextRevision();
        Timestamp now = Timestamp.from(Instant.now());
        String update = "UPDATE " + table
                + " SET config_value = ?, revision = ?, updated_at = ? WHERE config_key = ?";
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(update)) {
            ps.setString(1, value);
            ps.setLong(2, next);
            ps.setTimestamp(3, now);
            ps.setString(4, key);
            if (ps.executeUpdate() > 0) {
                return;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("更新配置失败：" + key, e);
        }
        insert(key, value, next, now);
    }

    private void insert(String key, String value, long revision, Timestamp at) {
        String sql = "INSERT INTO " + table
                + " (config_key, config_value, revision, updated_at) VALUES (?, ?, ?, ?)";
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, key);
            ps.setString(2, value);
            ps.setLong(3, revision);
            ps.setTimestamp(4, at);
            ps.executeUpdate();
        } catch (SQLException first) {
            // 并发下另一个写入者可能刚插入了同一键：退化成 UPDATE 再来一次
            String update = "UPDATE " + table
                    + " SET config_value = ?, revision = ?, updated_at = ? WHERE config_key = ?";
            try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(update)) {
                ps.setString(1, value);
                ps.setLong(2, revision);
                ps.setTimestamp(3, at);
                ps.setString(4, key);
                ps.executeUpdate();
            } catch (SQLException second) {
                second.addSuppressed(first);
                throw new IllegalStateException("写入配置失败：" + key, second);
            }
        }
    }

    private long nextRevision() {
        String sql = "SELECT COALESCE(MAX(revision), 0) + 1 FROM " + table;
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            return rs.next() ? rs.getLong(1) : 1L;
        } catch (SQLException e) {
            throw new IllegalStateException("取下一个配置修订号失败", e);
        }
    }
}
