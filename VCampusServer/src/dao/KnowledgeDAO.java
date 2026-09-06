package dao;

import entity.KnowledgeChunk;
import entity.KnowledgeDocument;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/**
 * 校园知识库数据访问对象 (KnowledgeDAO)
 */
public class KnowledgeDAO {

    public Long insertDocument(Connection conn, KnowledgeDocument doc) throws SQLException {
        String sql = "INSERT INTO tbl_knowledge_document (title, category, content, status) VALUES (?, ?, ?, ?)";
        try (PreparedStatement stmt = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            stmt.setString(1, doc.getTitle());
            stmt.setString(2, doc.getCategory() != null ? doc.getCategory() : "校园知识");
            stmt.setString(3, doc.getContent());
            stmt.setString(4, doc.getStatus() != null ? doc.getStatus() : "ACTIVE");

            stmt.executeUpdate();
            try (ResultSet rs = stmt.getGeneratedKeys()) {
                if (rs.next()) {
                    long id = rs.getLong(1);
                    doc.setDocId(id);
                    return id;
                }
            }
        }
        return null;
    }

    public Long insertChunk(Connection conn, KnowledgeChunk chunk) throws SQLException {
        String sql = "INSERT INTO tbl_knowledge_chunk (doc_id, chunk_index, content, token_count, embedding) " +
                     "VALUES (?, ?, ?, ?, ?)";
        try (PreparedStatement stmt = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            stmt.setLong(1, chunk.getDocId());
            stmt.setInt(2, chunk.getChunkIndex());
            stmt.setString(3, chunk.getContent());
            stmt.setInt(4, chunk.getTokenCount() != null ? chunk.getTokenCount() : 0);
            stmt.setString(5, chunk.getEmbedding());

            stmt.executeUpdate();
            try (ResultSet rs = stmt.getGeneratedKeys()) {
                if (rs.next()) {
                    long id = rs.getLong(1);
                    chunk.setChunkId(id);
                    return id;
                }
            }
        }
        return null;
    }

    public List<KnowledgeDocument> findDocuments(Connection conn, String category, boolean admin) throws SQLException {
        StringBuilder sql = new StringBuilder("SELECT doc_id, title, category, content, status, created_at, updated_at FROM tbl_knowledge_document WHERE 1=1 ");
        if (!admin) {
            sql.append("AND status = 'ACTIVE' ");
        }
        if (category != null && !category.isBlank()) {
            sql.append("AND category = ? ");
        }
        sql.append("ORDER BY doc_id ASC");

        List<KnowledgeDocument> list = new ArrayList<>();
        try (PreparedStatement stmt = conn.prepareStatement(sql.toString())) {
            if (category != null && !category.isBlank()) {
                stmt.setString(1, category);
            }
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    KnowledgeDocument d = new KnowledgeDocument();
                    d.setDocId(rs.getLong("doc_id"));
                    d.setTitle(rs.getString("title"));
                    d.setCategory(rs.getString("category"));
                    d.setContent(rs.getString("content"));
                    d.setStatus(rs.getString("status"));
                    d.setCreatedAt(rs.getString("created_at"));
                    d.setUpdatedAt(rs.getString("updated_at"));
                    list.add(d);
                }
            }
        }
        return list;
    }

    /**
     * 查询所有可用文档分块，并关联文档标题，用于向量检索
     */
    public List<ChunkItem> findAllActiveChunksWithDoc(Connection conn) throws SQLException {
        String sql = "SELECT c.chunk_id, c.doc_id, c.chunk_index, c.content, c.token_count, c.embedding, " +
                     "d.title AS doc_title, d.category " +
                     "FROM tbl_knowledge_chunk c " +
                     "JOIN tbl_knowledge_document d ON c.doc_id = d.doc_id " +
                     "WHERE d.status = 'ACTIVE' " +
                     "ORDER BY c.doc_id ASC, c.chunk_index ASC";

        List<ChunkItem> list = new ArrayList<>();
        try (PreparedStatement stmt = conn.prepareStatement(sql);
             ResultSet rs = stmt.executeQuery()) {
            while (rs.next()) {
                ChunkItem item = new ChunkItem();
                item.setChunkId(rs.getLong("chunk_id"));
                item.setDocId(rs.getLong("doc_id"));
                item.setChunkIndex(rs.getInt("chunk_index"));
                item.setContent(rs.getString("content"));
                item.setTokenCount(rs.getInt("token_count"));
                item.setEmbedding(rs.getString("embedding"));
                item.setDocTitle(rs.getString("doc_title"));
                item.setCategory(rs.getString("category"));
                list.add(item);
            }
        }
        return list;
    }

    /**
     * 辅助传输类：包含分块及其所属文档信息的复合对象
     */
    public static class ChunkItem {
        private Long chunkId;
        private Long docId;
        private Integer chunkIndex;
        private String content;
        private Integer tokenCount;
        private String embedding;
        private String docTitle;
        private String category;

        public Long getChunkId() { return chunkId; }
        public void setChunkId(Long chunkId) { this.chunkId = chunkId; }
        public Long getDocId() { return docId; }
        public void setDocId(Long docId) { this.docId = docId; }
        public Integer getChunkIndex() { return chunkIndex; }
        public void setChunkIndex(Integer chunkIndex) { this.chunkIndex = chunkIndex; }
        public String getContent() { return content; }
        public void setContent(String content) { this.content = content; }
        public Integer getTokenCount() { return tokenCount; }
        public void setTokenCount(Integer tokenCount) { this.tokenCount = tokenCount; }
        public String getEmbedding() { return embedding; }
        public void setEmbedding(String embedding) { this.embedding = embedding; }
        public String getDocTitle() { return docTitle; }
        public void setDocTitle(String docTitle) { this.docTitle = docTitle; }
        public String getCategory() { return category; }
        public void setCategory(String category) { this.category = category; }
    }
}
