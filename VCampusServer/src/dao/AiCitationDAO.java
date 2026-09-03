package dao;

import entity.AiCitation;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/**
 * AI 知识库引用来源数据访问对象 (AiCitationDAO)
 */
public class AiCitationDAO {

    public Long insert(Connection conn, AiCitation citation) throws SQLException {
        String sql = "INSERT INTO tbl_ai_citation (message_id, chunk_id, doc_title, similarity_score, excerpt) " +
                     "VALUES (?, ?, ?, ?, ?)";
        try (PreparedStatement stmt = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            stmt.setLong(1, citation.getMessageId());
            if (citation.getChunkId() != null) {
                stmt.setLong(2, citation.getChunkId());
            } else {
                stmt.setNull(2, java.sql.Types.BIGINT);
            }
            stmt.setString(3, citation.getDocTitle());
            stmt.setBigDecimal(4, citation.getSimilarityScore());
            stmt.setString(5, citation.getExcerpt());

            stmt.executeUpdate();
            try (ResultSet rs = stmt.getGeneratedKeys()) {
                if (rs.next()) {
                    long id = rs.getLong(1);
                    citation.setCitationId(id);
                    return id;
                }
            }
        }
        return null;
    }

    public List<AiCitation> findByMessageId(Connection conn, Long messageId) throws SQLException {
        String sql = "SELECT citation_id, message_id, chunk_id, doc_title, similarity_score, excerpt, created_at " +
                     "FROM tbl_ai_citation WHERE message_id = ? ORDER BY similarity_score DESC";
        List<AiCitation> list = new ArrayList<>();
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setLong(1, messageId);
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    AiCitation c = new AiCitation();
                    c.setCitationId(rs.getLong("citation_id"));
                    c.setMessageId(rs.getLong("message_id"));
                    long chunkId = rs.getLong("chunk_id");
                    if (!rs.wasNull()) {
                        c.setChunkId(chunkId);
                    }
                    c.setDocTitle(rs.getString("doc_title"));
                    c.setSimilarityScore(rs.getBigDecimal("similarity_score"));
                    c.setExcerpt(rs.getString("excerpt"));
                    c.setCreatedAt(rs.getString("created_at"));
                    list.add(c);
                }
            }
        }
        return list;
    }
}
