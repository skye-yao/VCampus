package dao;

import entity.AiMessage;
import util.DBUtil;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/**
 * AI 消息数据访问对象 (AiMessageDAO)
 */
public class AiMessageDAO {

    public Long insert(Connection conn, AiMessage msg) throws SQLException {
        String sql = "INSERT INTO tbl_ai_message (conversation_id, sender_type, content, intent_type, " +
                     "prompt_tokens, completion_tokens, cost_amount, transaction_no) " +
                     "VALUES (?, ?, ?, ?, ?, ?, ?, ?)";
        try (PreparedStatement stmt = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            stmt.setString(1, msg.getConversationId());
            stmt.setString(2, msg.getSenderType());
            stmt.setString(3, msg.getContent());
            stmt.setString(4, msg.getIntentType() != null ? msg.getIntentType() : "GENERAL");
            stmt.setInt(5, msg.getPromptTokens() != null ? msg.getPromptTokens() : 0);
            stmt.setInt(6, msg.getCompletionTokens() != null ? msg.getCompletionTokens() : 0);
            stmt.setBigDecimal(7, msg.getCostAmount() != null ? msg.getCostAmount() : java.math.BigDecimal.ZERO);
            stmt.setString(8, msg.getTransactionNo());

            stmt.executeUpdate();
            try (ResultSet rs = stmt.getGeneratedKeys()) {
                if (rs.next()) {
                    long id = rs.getLong(1);
                    msg.setMessageId(id);
                    return id;
                }
            }
        }
        return null;
    }

    public List<AiMessage> findByConversationId(Connection conn, String conversationId) throws SQLException {
        String sql = "SELECT message_id, conversation_id, sender_type, content, intent_type, " +
                     "prompt_tokens, completion_tokens, cost_amount, transaction_no, created_at " +
                     "FROM tbl_ai_message WHERE conversation_id = ? ORDER BY created_at ASC, message_id ASC";
        List<AiMessage> list = new ArrayList<>();
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, conversationId);
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    AiMessage msg = new AiMessage();
                    msg.setMessageId(rs.getLong("message_id"));
                    msg.setConversationId(rs.getString("conversation_id"));
                    msg.setSenderType(rs.getString("sender_type"));
                    msg.setContent(rs.getString("content"));
                    msg.setIntentType(rs.getString("intent_type"));
                    msg.setPromptTokens(rs.getInt("prompt_tokens"));
                    msg.setCompletionTokens(rs.getInt("completion_tokens"));
                    msg.setCostAmount(rs.getBigDecimal("cost_amount"));
                    msg.setTransactionNo(rs.getString("transaction_no"));
                    msg.setCreatedAt(rs.getString("created_at"));
                    list.add(msg);
                }
            }
        }
        return list;
    }
}
