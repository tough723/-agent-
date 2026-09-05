package com.oncall.ontology;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * 基于 JDBC 的 {@link OntologyStore} 实现。
 *
 * <p>只用 JDK 自带的 {@code javax.sql} / {@code java.sql}，
 * 因此 {@code oncall-ontology} 的生产代码仍然零外部依赖（H2 只在测试作用域）。
 *
 * <p><b>为什么必须存在</b>：内存实现在多实例部署下会让 CMDB 同步任务
 * 写进 A 实例、B 实例看不到——与配置治理当初必须换 JDBC 的原因完全一样。
 *
 * <p><b>upsert 不用方言语法</b>：H2 的 {@code MERGE INTO} 与 PostgreSQL 的
 * {@code ON CONFLICT} 不通用，这里先 UPDATE 再按影响行数决定 INSERT。
 * 本体写入频率极低（概念是人工维护的），这点开销无所谓。
 *
 * <p><b>标签大小写归一化</b>：入库时额外写一列 {@code label_norm = lower(label)}，
 * 查询走 {@code label_norm}。<b>不在查询时用 {@code LOWER()} 函数包裹列</b>——
 * 那样普通 B-tree 索引会失效，需要函数索引，而函数索引又是方言差异点。
 */
public final class JdbcOntologyStore implements OntologyStore {

    /** 建表 DDL（跨方言，H2 与 PostgreSQL 都能跑）。生产环境应改由 Flyway 管理。 */
    public static final String CREATE_CONCEPT_SQL = """
            CREATE TABLE IF NOT EXISTS %s (
                id           VARCHAR(64)  NOT NULL PRIMARY KEY,
                pref_label   VARCHAR(191) NOT NULL,
                parent_id    VARCHAR(64),
                kind         VARCHAR(32)  NOT NULL,
                criticality  VARCHAR(16)  NOT NULL,
                description  VARCHAR(500),
                updated_at   TIMESTAMP    NOT NULL
            )
            """;

    public static final String CREATE_LABEL_SQL = """
            CREATE TABLE IF NOT EXISTS %s (
                concept_id  VARCHAR(64)  NOT NULL,
                label       VARCHAR(191) NOT NULL,
                label_norm  VARCHAR(191) NOT NULL,
                label_type  VARCHAR(16)  NOT NULL,
                PRIMARY KEY (concept_id, label_type, label)
            )
            """;

    public static final String CREATE_RELATION_SQL = """
            CREATE TABLE IF NOT EXISTS %s (
                subject    VARCHAR(64) NOT NULL,
                predicate  VARCHAR(64) NOT NULL,
                object     VARCHAR(64) NOT NULL,
                source     VARCHAR(64) NOT NULL,
                updated_at TIMESTAMP   NOT NULL,
                PRIMARY KEY (subject, predicate, object)
            )
            """;

    private final DataSource dataSource;
    private final String conceptTable;
    private final String labelTable;
    private final String relationTable;

    public JdbcOntologyStore(DataSource dataSource) {
        this(dataSource, "onto_concept", "onto_concept_label", "onto_relation");
    }

    public JdbcOntologyStore(DataSource dataSource,
                             String conceptTable,
                             String labelTable,
                             String relationTable) {
        if (dataSource == null) {
            throw new IllegalArgumentException("dataSource 不能为 null");
        }
        this.dataSource = dataSource;
        this.conceptTable = conceptTable;
        this.labelTable = labelTable;
        this.relationTable = relationTable;
    }

    /** 建表（幂等）。测试与首次启动用；生产建议交给 Flyway（见 db/migration/V6）。 */
    public void createSchemaIfMissing() {
        try (Connection c = dataSource.getConnection(); Statement st = c.createStatement()) {
            st.execute(String.format(CREATE_CONCEPT_SQL, conceptTable));
            st.execute(String.format(CREATE_LABEL_SQL, labelTable));
            st.execute(String.format(CREATE_RELATION_SQL, relationTable));
        } catch (SQLException e) {
            throw new IllegalStateException("本体建表失败", e);
        }
    }

    // ------------------------------------------------------------ 概念层

    @Override
    public void putConcept(OntoConcept concept) {
        // UPDATE 的 WHERE 用 id，所以 id 是第 7 个占位符；
        // INSERT 的列顺序里 id 在第 1 位，所以 bind 从第 2 个开始。
        // 两处偏移不同，这是这段代码最容易写错的地方。
        String upsert = "UPDATE " + conceptTable
                + " SET pref_label=?, parent_id=?, kind=?, criticality=?, description=?, updated_at=?"
                + " WHERE id=?";
        String insert = "INSERT INTO " + conceptTable
                + " (id, pref_label, parent_id, kind, criticality, description, updated_at)"
                + " VALUES (?,?,?,?,?,?,?)";
        try (Connection c = dataSource.getConnection()) {
            c.setAutoCommit(false);
            try {
                try (PreparedStatement ps = c.prepareStatement(upsert)) {
                    bindConcept(ps, concept, 1);
                    ps.setString(7, concept.id());
                    if (ps.executeUpdate() == 0) {
                        try (PreparedStatement ins = c.prepareStatement(insert)) {
                            ins.setString(1, concept.id());
                            bindConcept(ins, concept, 2);
                            ins.executeUpdate();
                        }
                    }
                }
                replaceLabels(c, concept);
                c.commit();
            } catch (SQLException e) {
                c.rollback();
                throw e;
            } finally {
                c.setAutoCommit(true);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("写入概念失败：" + concept.id(), e);
        }
    }

    /** 从 {@code from} 号占位符开始绑定概念字段（不含 id）。 */
    private static void bindConcept(PreparedStatement ps, OntoConcept concept, int from) throws SQLException {
        ps.setString(from, concept.prefLabel());
        ps.setString(from + 1, concept.parentId());
        ps.setString(from + 2, concept.kind().name());
        ps.setString(from + 3, concept.criticality().name());
        ps.setString(from + 4, concept.description());
        ps.setTimestamp(from + 5, Timestamp.from(Instant.now()));
    }

    /** 标签整体替换。先删后插——标签数量是个位数，且必须保证删除的旧别名不再命中。 */
    private void replaceLabels(Connection c, OntoConcept concept) throws SQLException {
        try (PreparedStatement del = c.prepareStatement("DELETE FROM " + labelTable + " WHERE concept_id=?")) {
            del.setString(1, concept.id());
            del.executeUpdate();
        }
        String ins = "INSERT INTO " + labelTable
                + " (concept_id, label, label_norm, label_type) VALUES (?,?,?,?)";
        try (PreparedStatement ps = c.prepareStatement(ins)) {
            insertLabel(ps, concept.id(), concept.prefLabel(), "PREF");
            for (String a : concept.altLabels()) {
                insertLabel(ps, concept.id(), a, "ALT");
            }
            for (String h : concept.hiddenLabels()) {
                insertLabel(ps, concept.id(), h, "HIDDEN");
            }
            ps.executeBatch();
        }
    }

    private static void insertLabel(PreparedStatement ps, String id, String label, String type)
            throws SQLException {
        if (label == null || label.isBlank()) {
            return;
        }
        ps.setString(1, id);
        ps.setString(2, label);
        ps.setString(3, label.toLowerCase(Locale.ROOT));
        ps.setString(4, type);
        ps.addBatch();
    }

    @Override
    public Optional<OntoConcept> concept(String id) {
        String sql = "SELECT id, pref_label, parent_id, kind, criticality, description FROM "
                + conceptTable + " WHERE id=?";
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(withLabels(c, readConcept(rs))) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("读取概念失败：" + id, e);
        }
    }

    @Override
    public List<OntoConcept> allConcepts() {
        return queryConcepts("SELECT id, pref_label, parent_id, kind, criticality, description FROM "
                + conceptTable + " ORDER BY id", null);
    }

    @Override
    public List<OntoConcept> children(String parentId) {
        if (parentId == null) {
            return List.of();
        }
        return queryConcepts("SELECT id, pref_label, parent_id, kind, criticality, description FROM "
                + conceptTable + " WHERE parent_id=? ORDER BY id", parentId);
    }

    @Override
    public List<OntoConcept> findByMention(String mention) {
        if (mention == null || mention.isBlank()) {
            return List.of();
        }
        String sql = "SELECT c.id, c.pref_label, c.parent_id, c.kind, c.criticality, c.description"
                + " FROM " + conceptTable + " c JOIN " + labelTable + " l ON l.concept_id = c.id"
                + " WHERE l.label_norm = ? ORDER BY c.id";
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, mention.trim().toLowerCase(Locale.ROOT));
            List<OntoConcept> out = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(withLabels(c, readConcept(rs)));
                }
            }
            return out;
        } catch (SQLException e) {
            throw new IllegalStateException("按标签查询失败：" + mention, e);
        }
    }

    private List<OntoConcept> queryConcepts(String sql, String param) {
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            if (param != null) {
                ps.setString(1, param);
            }
            List<OntoConcept> out = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(withLabels(c, readConcept(rs)));
                }
            }
            return out;
        } catch (SQLException e) {
            throw new IllegalStateException("查询概念失败", e);
        }
    }

    private static OntoConcept readConcept(ResultSet rs) throws SQLException {
        return new OntoConcept(
                rs.getString("id"),
                rs.getString("pref_label"),
                List.of(),
                List.of(),
                rs.getString("parent_id"),
                ConceptKind.valueOf(rs.getString("kind")),
                Criticality.valueOf(rs.getString("criticality")),
                rs.getString("description"));
    }

    private OntoConcept withLabels(Connection c, OntoConcept bare) throws SQLException {
        List<String> alt = new ArrayList<>();
        List<String> hidden = new ArrayList<>();
        String sql = "SELECT label, label_type FROM " + labelTable
                + " WHERE concept_id=? ORDER BY label_type, label";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, bare.id());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String type = rs.getString("label_type");
                    if ("ALT".equals(type)) {
                        alt.add(rs.getString("label"));
                    } else if ("HIDDEN".equals(type)) {
                        hidden.add(rs.getString("label"));
                    }
                }
            }
        }
        return new OntoConcept(bare.id(), bare.prefLabel(), alt, hidden, bare.parentId(),
                bare.kind(), bare.criticality(), bare.description());
    }

    // ------------------------------------------------------------ 关系层

    @Override
    public void putRelation(OntoRelation relation) {
        String upsert = "UPDATE " + relationTable + " SET source=?, updated_at=?"
                + " WHERE subject=? AND predicate=? AND object=?";
        String insert = "INSERT INTO " + relationTable
                + " (subject, predicate, object, source, updated_at) VALUES (?,?,?,?,?)";
        String sql = "SELECT source FROM " + relationTable
                + " WHERE subject=? AND predicate=? AND object=?";
        try (Connection c = dataSource.getConnection()) {
            try (PreparedStatement ps = c.prepareStatement(upsert)) {
                ps.setString(1, relation.source());
                ps.setTimestamp(2, Timestamp.from(Instant.now()));
                ps.setString(3, relation.subject());
                ps.setString(4, relation.predicate());
                ps.setString(5, relation.object());
                if (ps.executeUpdate() == 0) {
                    try (PreparedStatement ins = c.prepareStatement(insert)) {
                        ins.setString(1, relation.subject());
                        ins.setString(2, relation.predicate());
                        ins.setString(3, relation.object());
                        ins.setString(4, relation.source());
                        ins.setTimestamp(5, Timestamp.from(Instant.now()));
                        ins.executeUpdate();
                    }
                }
            }
            // 自检：upsert 之后这一行必须存在，否则主键定义或 SQL 写错了
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                ps.setString(1, relation.subject());
                ps.setString(2, relation.predicate());
                ps.setString(3, relation.object());
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        throw new IllegalStateException("关系写入后查不到："
                                + relation.subject() + "/" + relation.predicate() + "/" + relation.object());
                    }
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("写入关系失败", e);
        }
    }

    @Override
    public List<String> objects(String subject, String predicate) {
        return queryRelation("SELECT object FROM " + relationTable
                + " WHERE subject=? AND predicate=? ORDER BY object", subject, predicate);
    }

    @Override
    public List<String> subjects(String object, String predicate) {
        return queryRelation("SELECT subject FROM " + relationTable
                + " WHERE object=? AND predicate=? ORDER BY subject", object, predicate);
    }

    private List<String> queryRelation(String sql, String a, String b) {
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, a);
            ps.setString(2, b);
            List<String> out = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(rs.getString(1));
                }
            }
            return out;
        } catch (SQLException e) {
            throw new IllegalStateException("查询关系失败", e);
        }
    }

    /** 概念总数，用于启动自检与指标。 */
    public int conceptCount() {
        try (Connection c = dataSource.getConnection();
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM " + conceptTable)) {
            return rs.next() ? rs.getInt(1) : 0;
        } catch (SQLException e) {
            throw new IllegalStateException("统计概念失败", e);
        }
    }

    /** 按来源统计关系数。CMDB 关系为 0 意味着本体会退化成受控词表——这是要暴露出来的事实。 */
    public Map<String, Integer> relationCountBySource() {
        String sql = "SELECT source, COUNT(*) FROM " + relationTable + " GROUP BY source ORDER BY source";
        Map<String, Integer> out = new LinkedHashMap<>();
        try (Connection c = dataSource.getConnection();
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) {
                out.put(rs.getString(1), rs.getInt(2));
            }
            return out;
        } catch (SQLException e) {
            throw new IllegalStateException("统计关系失败", e);
        }
    }
}
