package service;

import com.google.gson.*;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import service.PaperSearchService.Paper;
import service.PaperSearchService.Page;

/** Europe PMC open-access search, using API cursor pagination. */
public final class EuropePmcService {
    private final Map<String, Page> pages = new LinkedHashMap<>();
    private final Map<String, String> cursors = new HashMap<>();
    private long retryAfter;
    public synchronized Page search(String keywords, int page, boolean newest) throws Exception {
        if (keywords == null || keywords.isBlank() || keywords.length() > 200 || page < 0 || page > 99)
            throw new IOException("请输入 1–200 个字符的关键词");
        String base = keywords.trim() + "|" + newest + "|";
        String key = base + page;
        if (pages.containsKey(key)) return pages.get(key);
        if (System.currentTimeMillis() < retryAfter) throw new IOException("Europe PMC 请求繁忙，请稍后重试");
        String cursor = page == 0 ? "*" : cursors.get(key);
        if (cursor == null) throw new IOException("请从第一页开始检索");
        String terms = Arrays.stream(keywords.trim().split("\\s+"))
                .map(word -> "\"" + word.replace("\"", "") + "\"").reduce((a,b) -> a + " AND " + b).orElseThrow();
        String query = "(" + terms + ") AND OPEN_ACCESS:y" + (newest ? " sort_date:y" : "");
        String url = "https://www.ebi.ac.uk/europepmc/webservices/rest/search?query=" + encode(query)
                + "&format=json&resultType=core&pageSize=10&cursorMark=" + encode(cursor);
        byte[] bytes;
        try { bytes = PaperSearchService.fetch(url, 4 * 1024 * 1024); }
        catch (IOException error) {
            if (error.getMessage().contains("429")) retryAfter = System.currentTimeMillis() + 60000;
            throw error;
        }
        JsonObject root = JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8)).getAsJsonObject();
        Page result = parse(root);
        if (pages.size() >= 100) { pages.clear(); cursors.clear(); }
        pages.put(key, result);
        String next = string(root, "nextCursorMark");
        if (!next.isBlank()) cursors.put(base + (page + 1), next);
        return result;
    }
    static Page parse(JsonObject root) throws IOException {
        if (!root.has("hitCount") || !root.has("resultList")) throw new IOException("Europe PMC 返回格式异常");
        List<Paper> papers = new ArrayList<>();
        JsonArray results = root.getAsJsonObject("resultList").getAsJsonArray("result");
        if (results == null) return new Page(List.of(), root.get("hitCount").getAsInt());
        for (JsonElement item : results) {
            JsonObject record = item.getAsJsonObject();
            String id = string(record, "id"), pmcid = string(record, "pmcid");
            String source = !pmcid.isBlank() ? "https://europepmc.org/articles/" + encode(pmcid)
                    : "https://europepmc.org/article/" + encode(string(record, "source")) + "/" + encode(id);
            String pdf = "";
            if (record.has("fullTextUrlList") && record.get("fullTextUrlList").isJsonObject()) {
                JsonArray urls = record.getAsJsonObject("fullTextUrlList").getAsJsonArray("fullTextUrl");
                if (urls != null) for (JsonElement element : urls) {
                    JsonObject link = element.getAsJsonObject();
                    String address = string(link, "url");
                    if (!string(link, "documentStyle").equals("pdf") || !string(link, "availabilityCode").equals("F")) continue;
                    try {
                        var uri = java.net.URI.create(address);
                        if (!"https".equals(uri.getScheme()) || uri.getHost() == null || !PaperSearchService.trustedHost(uri.getHost())) continue;
                        if (pdf.isEmpty() || uri.getHost().equals("europepmc.org")) pdf = address;
                    } catch (IllegalArgumentException ignored) { }
                }
            }
            String summary = string(record, "abstractText").replaceAll("<[^>]+>", " ").replaceAll("\\s+", " ").trim();
            papers.add(new Paper(pmcid.isBlank() ? id : pmcid, string(record,"title"), string(record,"authorString"),
                    string(record,"firstPublicationDate"), summary.isBlank() ? "该记录暂无摘要，可查看原文页面。" : summary,
                    "开放获取", pdf, source));
        }
        return new Page(List.copyOf(papers), root.get("hitCount").getAsInt());
    }
    private static String string(JsonObject object, String key) {
        return !object.has(key) || object.get(key).isJsonNull() ? "" : object.get(key).getAsString();
    }
    private static String encode(String text) { return URLEncoder.encode(text, StandardCharsets.UTF_8); }
}
