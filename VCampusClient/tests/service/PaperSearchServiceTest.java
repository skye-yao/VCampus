package service;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

public class PaperSearchServiceTest {
    public static void main(String[] args) throws Exception {
        long now = java.time.Instant.parse("2026-09-15T00:00:00Z").toEpochMilli();
        require(PaperSearchService.cooldownUntil(null, now) == now + 60000, "Default cooldown");
        require(PaperSearchService.cooldownUntil("120", now) == now + 120000, "Server cooldown seconds");
        require(PaperSearchService.cooldownUntil("Tue, 15 Sep 2026 00:05:00 GMT", now) == now + 300000, "Server cooldown date");
        require(PaperSearchService.cooldownUntil("invalid", now) == now + 60000, "Invalid cooldown fallback");
        var proxyProperties = new java.util.Properties();
        require(PaperNetworkConfig.parse(proxyProperties) == null, "Default proxy selection");
        proxyProperties.setProperty("proxy.type", "direct");
        require(PaperNetworkConfig.parse(proxyProperties) == java.net.Proxy.NO_PROXY, "Explicit direct connection");
        proxyProperties.setProperty("proxy.type", "http");
        proxyProperties.setProperty("proxy.host", "127.0.0.1");
        proxyProperties.setProperty("proxy.port", "7890");
        var configuredProxy = PaperNetworkConfig.parse(proxyProperties);
        require(configuredProxy.type() == java.net.Proxy.Type.HTTP
                && ((java.net.InetSocketAddress) configuredProxy.address()).getPort() == 7890, "HTTP proxy");
        proxyProperties.setProperty("proxy.port", "70000");
        try { PaperNetworkConfig.parse(proxyProperties); throw new AssertionError("Invalid proxy port accepted"); }
        catch (java.io.IOException expected) { }
        String feed = """
                <feed xmlns="http://www.w3.org/2005/Atom" xmlns:op="http://a9.com/-/spec/opensearch/1.1/">
                <op:totalResults>23</op:totalResults><entry>
                <id>http://arxiv.org/abs/1706.03762v7</id><title>Attention Is\n All You Need</title>
                <author><name>First Author</name></author><author><name>Second Author</name></author>
                <published>2017-06-12T00:00:00Z</published><summary>A &amp; B</summary><category term="cs.CL"/>
                <link title="pdf" href="http://arxiv.org/pdf/1706.03762v7" type="application/pdf"/>
                </entry></feed>
                """;
        var page = parse(feed);
        var paper = page.papers().get(0);
        require(page.total() == 23 && page.papers().size() == 1, "Pagination metadata");
        require(paper.title().equals("Attention Is All You Need"), "Multiline title");
        require(paper.authors().equals("First Author, Second Author"), "Authors");
        require(paper.pdfUrl().equals("https://arxiv.org/pdf/1706.03762v7"), "HTTPS PDF and version");
        require(paper.summary().equals("A & B") && paper.published().equals("2017-06-12"), "Summary/date");
        require(parse(feed.replace("<link title=\"pdf\" href=\"http://arxiv.org/pdf/1706.03762v7\" type=\"application/pdf\"/>", ""))
                .papers().get(0).pdfUrl().isEmpty(), "Missing PDF");
        require(parse("<feed xmlns=\"http://www.w3.org/2005/Atom\"/>").papers().isEmpty(), "Empty search");
        try { parse("<!DOCTYPE feed [<!ENTITY x SYSTEM 'file:///missing'>]>" + feed); throw new AssertionError("XXE accepted"); }
        catch (org.xml.sax.SAXException expected) { }
        try { parse("<html/>"); throw new AssertionError("HTML accepted"); }
        catch (java.io.IOException expected) { }
        if (args.length > 0 && args[0].equals("pdf")) {
            byte[] bytes = new PaperSearchService().download(paper);
            try (var doc = org.apache.pdfbox.Loader.loadPDF(bytes)) {
                require(doc.getNumberOfPages() > 0, "Live PDF readable");
                System.out.println("Live PDF: " + bytes.length + " bytes, " + doc.getNumberOfPages() + " pages");
            }
        }
        if (args.length > 0 && args[0].equals("live")) {
            var service = new PaperSearchService();
            var live = service.search("electron", 0, false);
            require(!live.papers().isEmpty(), "Live results");
            System.out.println("Live search: " + live.total() + " results; " + live.papers().get(0).title());
            byte[] pdf = service.download(live.papers().get(0));
            try (var doc = org.apache.pdfbox.Loader.loadPDF(pdf)) {
                require(doc.getNumberOfPages() > 0, "Readable PDF");
                System.out.println("Live PDF: " + pdf.length + " bytes, " + doc.getNumberOfPages() + " pages");
            }
        }
        System.out.println("PaperSearchServiceTest PASS");
    }
    private static PaperSearchService.Page parse(String xml) throws Exception {
        return PaperSearchService.parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
    }
    private static void require(boolean condition, String label) { if (!condition) throw new AssertionError(label); }
}
