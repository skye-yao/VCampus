package dao;

import entity.AiConversation;
import util.DBUtil;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * AI 会话数据访问对象 (AiConversationDAO)
 */
public class AiConversationDAO {

    public boolean create(Connection conn, AiConversation conv) throws SQLException {
        String sql = "INSERT INTO tbl_ai_conversation (conversation_id, user_id, title) VALUES (?, ?, ?)";
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, conv.getConversationId());
            stmt.setString(2, conv.getUserId());
            stmt.setString(3, conv.getTitle() != null ? conv.getTitle() : "新对话");
            return stmt.executeUpdate() > 0;
        }
    }

    public List<AiConversation> findByUserId(Connection conn, String userId) throws SQLException {
        String sql = "SELECT conversation_id, user_id, title, created_at, updated_at " +
                     "FROM tbl_ai_conversation WHERE user_id = ? ORDER BY updated_at DESC";
        List<AiConversation> list = new ArrayList<>();
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, userId);
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    AiConversation conv = new AiConversation();
                    conv.setConversationId(rs.getString("conversation_id"));
                    conv.setUserId(rs.getString("user_id"));
                    conv.setTitle(rs.getString("title"));
                    conv.setCreatedAt(rs.getString("created_at"));
                    conv.setUpdatedAt(rs.getString("updated_at"));
                    list.add(conv);
                }
            }
        }
        return list;
    }

    public AiConversation findById(Connection conn, String conversationId) throws SQLException {
        String sql = "SELECT conversation_id, user_id, title, created_at, updated_at " +
                     "FROM tbl_ai_conversation WHERE conversation_id = ?";
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, conversationId);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    AiConversation conv = new AiConversation();
                    conv.setConversationId(rs.getString("conversation_id"));
                    conv.setUserId(rs.getString("user_id"));
                    conv.setTitle(rs.getString("title"));
                    conv.setCreatedAt(rs.getString("created_at"));
                    conv.setUpdatedAt(rs.getString("updated_at"));
                    return conv;
                }
            }
        }
        return null;
    }

    public boolean updateTitle(Connection conn, String conversationId, String title) throws SQLException {
        String sql = "UPDATE tbl_ai_conversation SET title = ?, updated_at = CURRENT_TIMESTAMP WHERE conversation_id = ?";
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, title);
            stmt.setString(2, conversationId);
            return stmt.executeUpdate() > 0;
        }
    }

    public boolean touch(Connection conn, String conversationId) throws SQLException {
        String sql = "UPDATE tbl_ai_conversation SET updated_at = CURRENT_TIMESTAMP WHERE conversation_id = ?";
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, conversationId);
            return stmt.executeUpdate() > 0;
        }
    }

    public boolean delete(Connection conn, String conversationId, String userId) throws SQLException {
        String sql = "DELETE FROM tbl_ai_conversation WHERE conversation_id = ? AND user_id = ?";
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, conversationId);
            stmt.setString(2, userId);
            return stmt.executeUpdate() > 0;
        }
    }
}
