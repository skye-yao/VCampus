package service;

import com.google.gson.JsonParser;

public class EuropePmcServiceTest {
    public static void main(String[] args) throws Exception {
        var fixture = JsonParser.parseString("""
            {"hitCount":1,"resultList":{"result":[{"id":"123","pmcid":"PMC123","title":"Example",
            "abstractText":"<p>An abstract</p>","fullTextUrlList":{"fullTextUrl":[
            {"documentStyle":"pdf","availabilityCode":"F","url":"https://europepmc.org/articles/PMC123?pdf=render"}]}}]}}
            """).getAsJsonObject();
        var parsed = EuropePmcService.parse(fixture);
        require(parsed.total() == 1 && parsed.papers().get(0).sourceName().equals("Europe PMC"), "Source and count");
        require(parsed.papers().get(0).summary().equals("An abstract"), "Abstract markup");
        require(!parsed.papers().get(0).pdfUrl().isEmpty(), "Open PDF");
        require(!PaperSearchService.trustedHost("europepmc.org.evil.test"), "Host validation");
        if (args.length > 0) {
            var service = new EuropePmcService();
            var first = service.search("machine learning", 0, false);
            require(!first.papers().isEmpty(), "Live results");
            System.out.println("Live search: " + first.total() + " results; " + first.papers().get(0).title());
            var second = service.search("machine learning", 1, false);
            require(!second.papers().isEmpty() && !first.papers().get(0).id().equals(second.papers().get(0).id()), "Cursor pagination");
            System.out.println("Second page: " + second.papers().size() + " results");
            var paper = first.papers().stream().filter(p -> !p.pdfUrl().isBlank()).findFirst().orElseThrow();
            var pdf = new PaperSearchService().download(paper);
            try (var document = org.apache.pdfbox.Loader.loadPDF(pdf)) {
                require(document.getNumberOfPages() > 0, "Readable PDF");
                System.out.println("Live PDF: " + pdf.length + " bytes, " + document.getNumberOfPages() + " pages");
            }
        }
        System.out.println("EuropePmcServiceTest PASS");
    }
    private static void require(boolean condition, String label) { if (!condition) throw new AssertionError(label); }
}
