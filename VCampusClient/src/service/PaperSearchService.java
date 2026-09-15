package service;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.*;

/** Public arXiv metadata and personal-use PDF retrieval; no API key required. */
public final class PaperSearchService {
    public record Paper(String id, String title, String authors, String published,
                        String summary, String category, String pdfUrl, String sourceUrl) {
        public String sourceName() { return sourceUrl.contains("europepmc.org/") ? "Europe PMC" : "arXiv"; }
    }
    public record Page(List<Paper> papers, int total) {}
    public static final int PAGE_SIZE = 10;
    private static long lastRequest;
    private static volatile long retryAfter;
    private static final String ATOM = "http://www.w3.org/2005/Atom";
    private final Map<String, Page> cache = new LinkedHashMap<>();

    public synchronized Page search(String keywords, int page, boolean newest) throws Exception {
        if (keywords == null || keywords.isBlank() || keywords.length() > 200 || page < 0 || page > 99)
            throw new IOException("请输入 1–200 个字符的关键词，最多浏览前 100 页");
        String query = Arrays.stream(keywords.trim().split("\\s+"))
                .map(word -> "all:\"" + word.replace("\"", "") + "\"")
                .reduce((a, b) -> a + " AND " + b).orElseThrow();
        String url = "https://export.arxiv.org/api/query?search_query="
                + URLEncoder.encode(query, StandardCharsets.UTF_8) + "&start=" + page * PAGE_SIZE
                + "&max_results=" + PAGE_SIZE + "&sortBy=" + (newest ? "submittedDate" : "relevance")
                + "&sortOrder=descending";
        if (cache.containsKey(url)) return cache.get(url);
        Page result;
        // The legacy API requires one connection and at least three seconds between requests.
        synchronized (PaperSearchService.class) {
            long remaining = (retryAfter - System.currentTimeMillis() + 999) / 1000;
            if (remaining > 0) throw new IOException("arXiv 接口限流，程序已暂停请求，请约 " + remaining + " 秒后再试，或使用网页检索");
            long wait = 3100 - (System.currentTimeMillis() - lastRequest);
            if (wait > 0) Thread.sleep(wait);
            lastRequest = System.currentTimeMillis();
            result = parse(new ByteArrayInputStream(fetch(url, 4 * 1024 * 1024)));
        }
        if (cache.size() >= 30) cache.remove(cache.keySet().iterator().next());
        cache.put(url, result);
        return result;
    }

    public byte[] download(Paper paper) throws Exception {
        if (paper.pdfUrl().isBlank()) throw new IOException("该记录暂无 PDF，请查看原文页面");
        byte[] bytes = fetch(paper.pdfUrl(), 40 * 1024 * 1024);
        String prefix = new String(bytes, 0, Math.min(bytes.length, 1024), StandardCharsets.ISO_8859_1);
        if (!prefix.contains("%PDF-")) throw new IOException("来源未返回有效 PDF，请使用浏览器打开");
        return bytes;
    }

    static byte[] fetch(String address, int limit) throws Exception {
        URI uri = URI.create(address);
        Proxy proxy = PaperNetworkConfig.load();
        for (int redirects = 0; redirects < 5; redirects++) {
            if (!"https".equals(uri.getScheme()) || uri.getHost() == null
                    || !trustedHost(uri.getHost()))
                throw new IOException("论文链接不是受支持的来源 HTTPS 地址");
            HttpURLConnection connection = (HttpURLConnection) (proxy == null
                    ? uri.toURL().openConnection() : uri.toURL().openConnection(proxy));
            connection.setConnectTimeout(15000);
            connection.setReadTimeout(30000);
            connection.setInstanceFollowRedirects(false);
            connection.setRequestProperty("User-Agent", "VCampus-Library/1.0 (academic paper search)");
            try {
                int code = connection.getResponseCode();
                if (code >= 300 && code < 400) {
                    String location = connection.getHeaderField("Location");
                    if (location == null) throw new IOException("论文来源返回了无效跳转");
                    uri = uri.resolve(location);
                    continue;
                }
                if (code == 429) {
                    if (uri.getHost().endsWith("arxiv.org"))
                        retryAfter = cooldownUntil(connection.getHeaderField("Retry-After"), System.currentTimeMillis());
                    throw new IOException("论文来源接口限流（HTTP 429），请稍后再试或切换来源");
                }
                if (code != 200) throw new IOException("论文来源返回 HTTP " + code);
                if (connection.getContentLengthLong() > limit) throw new IOException("文件过大，请通过浏览器阅读或下载");
                try (InputStream input = connection.getInputStream(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                    byte[] buffer = new byte[8192];
                    long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(90);
                    int read;
                    while ((read = input.read(buffer)) != -1) {
                        if (output.size() + read > limit) throw new IOException("文件过大，请通过浏览器阅读或下载");
                        if (System.nanoTime() > deadline) throw new IOException("下载超时，请重试或通过浏览器打开");
                        output.write(buffer, 0, read);
                    }
                    return output.toByteArray();
                }
            } catch (SocketTimeoutException e) {
                throw new IOException("等待论文来源或代理响应超时，请稍后重试；也可使用网页检索");
            } finally { connection.disconnect(); }
        }
        throw new IOException("论文来源跳转次数过多");
    }

    static boolean trustedHost(String host) {
        return host.equals("arxiv.org") || host.endsWith(".arxiv.org")
                || host.equals("europepmc.org") || host.equals("www.ebi.ac.uk")
                || host.equals("pmc.ncbi.nlm.nih.gov") || host.equals("www.ncbi.nlm.nih.gov");
    }

    static long cooldownUntil(String value, long now) {
        long fallback = now + 60_000;
        if (value == null) return fallback;
        try {
            long seconds = Long.parseLong(value.trim());
            return Math.max(fallback, Math.addExact(now, Math.multiplyExact(seconds, 1000L)));
        } catch (RuntimeException ignored) {
            try {
                return Math.max(fallback, java.time.ZonedDateTime.parse(value,
                        java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli());
            } catch (RuntimeException invalid) { return fallback; }
        }
    }

    static Page parse(InputStream input) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        Element root = factory.newDocumentBuilder().parse(input).getDocumentElement();
        if (!ATOM.equals(root.getNamespaceURI()) || !"feed".equals(root.getLocalName()))
            throw new IOException("arXiv 返回格式异常");
        NodeList entries = root.getElementsByTagNameNS(ATOM, "entry");
        List<Paper> papers = new ArrayList<>();
        for (int i = 0; i < entries.getLength(); i++) {
            Element entry = (Element) entries.item(i);
            String source = value(entry, ATOM, "id");
            if (source.contains("/api/errors")) throw new IOException("arXiv 检索失败：" + value(entry, ATOM, "summary"));
            String id = source.substring(source.lastIndexOf("/abs/") + 5);
            if (!source.contains("/abs/") || !id.matches("[A-Za-z0-9./-]+")) continue;
            List<String> authors = new ArrayList<>();
            NodeList authorNodes = entry.getElementsByTagNameNS(ATOM, "author");
            for (int a = 0; a < authorNodes.getLength(); a++) authors.add(value((Element) authorNodes.item(a), ATOM, "name"));
            NodeList categories = entry.getElementsByTagNameNS(ATOM, "category");
            String category = categories.getLength() == 0 ? "" : ((Element) categories.item(0)).getAttribute("term");
            NodeList links = entry.getElementsByTagNameNS(ATOM, "link");
            boolean pdf = false;
            for (int l = 0; l < links.getLength(); l++) {
                Element link = (Element) links.item(l);
                if ("application/pdf".equals(link.getAttribute("type")) || "pdf".equals(link.getAttribute("title"))) pdf = true;
            }
            String published = value(entry, ATOM, "published");
            papers.add(new Paper(id, value(entry, ATOM, "title"), String.join(", ", authors),
                    published.substring(0, Math.min(10, published.length())), value(entry, ATOM, "summary"), category,
                    pdf ? "https://arxiv.org/pdf/" + id : "", "https://arxiv.org/abs/" + id));
        }
        String count = value(root, "http://a9.com/-/spec/opensearch/1.1/", "totalResults");
        return new Page(List.copyOf(papers), count.isBlank() ? papers.size() : Integer.parseInt(count));
    }

    private static String value(Element element, String namespace, String name) {
        Node node = element.getElementsByTagNameNS(namespace, name).item(0);
        return node == null ? "" : node.getTextContent().replaceAll("\\s+", " ").trim();
    }
}
