package service.ai;

import dao.KnowledgeDAO.ChunkItem;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;

/**
 * 知识库检索与相似度计算器
 *
 * <p>实现文档分块（Chunker）与余弦/TF-IDF相似度检索（Vector Retrieval），
 * 支持关键词加权、字符级切分、阈值过滤与 Top-K 召回。
 */
public class SimpleVectorRetriever {

    /** 校园核心专有名词加权词库 */
    private static final Set<String> DOMAIN_KEYWORDS = Set.of(
            "学费", "缴费", "缴纳", "费用", "银行", "账单", "转账", "报销", "支付密码", "充值",
            "学籍", "专业", "休学", "复学", "申诉", "信息变更", "教务", "成绩", "学分",
            "图书", "借书", "还书", "借阅", "续借", "逾期", "违约金", "预约", "挂失", "闭馆",
            "选课", "退选", "补考", "重修", "预选", "正选", "开学", "截止",
            "商店", "退款", "购物车", "订单", "校园", "一卡通", "流程", "规程", "规定", "制度", "卡"
    );

    /**
     * 文本分块器 (Chunker)：按标点与长度将长文档切分为带有重叠的切片
     *
     * @param fullText    完整文档
     * @param chunkSize   目标块大小（字数）
     * @param chunkOverlap 重叠字数
     * @return 分块列表
     */
    public List<String> chunkText(String fullText, int chunkSize, int chunkOverlap) {
        List<String> chunks = new ArrayList<>();
        if (fullText == null || fullText.isBlank()) return chunks;

        String text = fullText.trim();
        if (text.length() <= chunkSize) {
            chunks.add(text);
            return chunks;
        }

        int start = 0;
        while (start < text.length()) {
            int end = Math.min(start + chunkSize, text.length());
            // 尝试在句子边界切分
            if (end < text.length()) {
                int sentenceEnd = findSentenceEnd(text, end, Math.max(start + 50, end - 50));
                if (sentenceEnd > 0) {
                    end = sentenceEnd;
                }
            }
            String chunk = text.substring(start, end).trim();
            if (!chunk.isEmpty()) {
                chunks.add(chunk);
            }
            if (end >= text.length()) break;
            start = Math.max(start + 1, end - chunkOverlap);
        }
        return chunks;
    }

    private int findSentenceEnd(String text, int target, int minLimit) {
        for (int i = target; i >= minLimit; i--) {
            char c = text.charAt(i - 1);
            if (c == '\n' || c == '。' || c == '；' || c == '!' || c == '？') {
                return i;
            }
        }
        return target;
    }

    /**
     * 检索匹配的分块结果项
     */
    public static class MatchResult {
        private final ChunkItem chunk;
        private final double score;

        public MatchResult(ChunkItem chunk, double score) {
            this.chunk = chunk;
            this.score = score;
        }

        public ChunkItem getChunk() { return chunk; }
        public double getScore() { return score; }
        public BigDecimal getScoreAsBigDecimal() {
            return BigDecimal.valueOf(score).setScale(4, RoundingMode.HALF_UP);
        }
    }

    /**
     * 在给定分块集合中进行相关度打分与检索
     *
     * @param query       用户问题
     * @param chunks      知识库候选分块
     * @param topK        返回最多几个结果
     * @param minScore    最低相关度阈值
     * @return 按相关度降序排列的召回结果
     */
    public List<MatchResult> search(String query, List<ChunkItem> chunks, int topK, double minScore) {
        if (query == null || query.isBlank() || chunks == null || chunks.isEmpty()) {
            return Collections.emptyList();
        }

        Map<String, Double> queryVector = buildTermVector(query.toLowerCase());
        List<MatchResult> scoredResults = new ArrayList<>();

        for (ChunkItem chunk : chunks) {
            String targetText = (chunk.getDocTitle() + " " + chunk.getContent()).toLowerCase();
            Map<String, Double> docVector = buildTermVector(targetText);

            double cosineSim = computeCosineSimilarity(queryVector, docVector);

            // 领域关键词重合加权
            double keywordBoost = computeKeywordBoost(query, targetText);
            double finalScore = Math.min(1.0, cosineSim * 0.7 + keywordBoost * 0.3);

            if (finalScore >= minScore) {
                scoredResults.add(new MatchResult(chunk, finalScore));
            }
        }

        // 按得分降序排序
        scoredResults.sort((a, b) -> Double.compare(b.getScore(), a.getScore()));

        if (scoredResults.size() > topK) {
            return scoredResults.subList(0, topK);
        }
        return scoredResults;
    }

    /**
     * 构建基于字符/词的双字元 (bi-gram) 与词频向量
     */
    private Map<String, Double> buildTermVector(String text) {
        Map<String, Double> vector = new HashMap<>();
        if (text == null) return vector;

        // 1. 字符级/单字
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isLetterOrDigit(c)) {
                String term = String.valueOf(c);
                vector.put(term, vector.getOrDefault(term, 0.0) + 1.0);
            }
        }

        // 2. 双字元 (Bi-grams) - 极大提升中文短语匹配精度
        for (int i = 0; i < text.length() - 1; i++) {
            char c1 = text.charAt(i);
            char c2 = text.charAt(i + 1);
            if (Character.isLetterOrDigit(c1) && Character.isLetterOrDigit(c2)) {
                String bigram = "" + c1 + c2;
                vector.put(bigram, vector.getOrDefault(bigram, 0.0) + 2.0);
            }
        }

        // 3. 领域关键词匹配增强
        for (String kw : DOMAIN_KEYWORDS) {
            if (text.contains(kw)) {
                vector.put(kw, vector.getOrDefault(kw, 0.0) + 5.0);
            }
        }

        return vector;
    }

    /**
     * 计算两个词频向量的余弦相似度
     */
    private double computeCosineSimilarity(Map<String, Double> v1, Map<String, Double> v2) {
        if (v1.isEmpty() || v2.isEmpty()) return 0.0;

        double dotProduct = 0.0;
        double norm1 = 0.0;
        double norm2 = 0.0;

        for (double val : v1.values()) {
            norm1 += val * val;
        }
        for (double val : v2.values()) {
            norm2 += val * val;
        }

        for (Map.Entry<String, Double> entry : v1.entrySet()) {
            Double val2 = v2.get(entry.getKey());
            if (val2 != null) {
                dotProduct += entry.getValue() * val2;
            }
        }

        if (norm1 == 0.0 || norm2 == 0.0) return 0.0;
        return dotProduct / (Math.sqrt(norm1) * Math.sqrt(norm2));
    }

    /**
     * 计算领域关键词匹配加权系数
     */
    private double computeKeywordBoost(String query, String targetText) {
        int matchCount = 0;
        int totalQueryKeywords = 0;

        for (String kw : DOMAIN_KEYWORDS) {
            if (query.contains(kw)) {
                totalQueryKeywords++;
                if (targetText.contains(kw)) {
                    matchCount++;
                }
            }
        }

        if (totalQueryKeywords == 0) return 0.0;
        return (double) matchCount / totalQueryKeywords;
    }
}
