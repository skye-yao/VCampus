package controller;

import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.scene.control.*;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import service.PaperSearchService;
import service.PaperSearchService.Paper;
import java.nio.file.*;
import java.util.concurrent.*;

public final class PaperSearchController {
    @FXML private TextField keyword;
    @FXML private ComboBox<String> sort;
    @FXML private ComboBox<String> sourceChoice;
    @FXML private Button searchButton, previousButton, nextButton, readButton, downloadButton, browserButton, sourceButton;
    @FXML private Label status, pageLabel, paperTitle, paperMeta, fileStatus;
    @FXML private TextArea abstractText;
    @FXML private ListView<Paper> results;
    private final PaperSearchService service = new PaperSearchService();
    private final service.EuropePmcService europePmc = new service.EuropePmcService();
    private boolean activeEurope = true;
    private int page, total;
    private String activeQuery = "";
    private boolean activeNewest, searching, downloading;
    private Paper cachedPaper;
    private byte[] cachedPdf;

    @FXML private void initialize() {
        sourceChoice.getItems().setAll("Europe PMC", "arXiv");
        sourceChoice.getSelectionModel().selectFirst();
        sort.getItems().setAll("相关度", "最新发表");
        sort.getSelectionModel().selectFirst();
        results.setPlaceholder(new Label("输入英文关键词，探索开放学术论文"));
        results.setCellFactory(list -> new ListCell<>() {
            private final Label title = new Label(), meta = new Label(), summary = new Label();
            private final VBox card = new VBox(7, title, meta, summary);
            {
                title.getStyleClass().add("paper-result-title");
                meta.getStyleClass().add("paper-meta");
                title.setWrapText(true); meta.setWrapText(true); summary.setWrapText(true);
                card.maxWidthProperty().bind(list.widthProperty().subtract(42));
                card.prefWidthProperty().bind(list.widthProperty().subtract(42));
                card.getStyleClass().add("paper-card");
            }
            @Override protected void updateItem(Paper paper, boolean empty) {
                super.updateItem(paper, empty); setText(null);
                if (empty || paper == null) { setGraphic(null); return; }
                title.setText(paper.title());
                meta.setText(paper.published() + " · " + paper.category() + " · " + paper.sourceName() + "\n" + paper.authors());
                summary.setText(paper.summary().length() > 230 ? paper.summary().substring(0, 230) + "…" : paper.summary());
                setGraphic(card);
            }
        });
        results.getSelectionModel().selectedItemProperty().addListener((o, old, paper) -> {
            paperTitle.setText(paper == null ? "选择一篇论文" : paper.title());
            paperMeta.setText(paper == null ? "在此查看摘要与全文" : paper.authors() + "\n" + paper.published()
                    + " · " + paper.category() + "\n" + paper.sourceName() + ": " + paper.id());
            abstractText.setText(paper == null ? "" : paper.summary());
            updateButtons();
        });
        updateButtons();
    }

    @FXML private void search() {
        if (searching) return;
        String query = keyword.getText().trim();
        if (query.isEmpty() || query.length() > 200) { status.setText("请输入 1–200 个字符的英文关键词"); return; }
        activeEurope = sourceChoice.getSelectionModel().getSelectedIndex() == 0;
        page = 0; total = 0;
        results.getItems().clear();
        load(query, 0, sort.getSelectionModel().getSelectedIndex() == 1);
    }
    @FXML private void webSearch() {
        String query = keyword.getText().trim();
        if (query.isEmpty() || query.length() > 200) { status.setText("请输入 1–200 个字符的英文关键词"); return; }
        if (sourceChoice.getSelectionModel().getSelectedIndex() == 0) {
            openBrowser("https://europepmc.org/search?query=" + java.net.URLEncoder.encode(query, java.nio.charset.StandardCharsets.UTF_8));
            return;
        }
        openBrowser("https://arxiv.org/search/?query="
                + java.net.URLEncoder.encode(query, java.nio.charset.StandardCharsets.UTF_8)
                + "&searchtype=all&abstracts=show&order=-announced_date_first&size=50");
    }
    @FXML private void previous() { if (!searching && page > 0) load(activeQuery, page - 1, activeNewest); }
    @FXML private void next() { if (!searching && (page + 1) * PaperSearchService.PAGE_SIZE < total && page < 99) load(activeQuery, page + 1, activeNewest); }
    private void load(String query, int requestedPage, boolean newest) {
        searching = true; updateButtons(); status.setText("正在检索 " + (activeEurope ? "Europe PMC" : "arXiv") + "，请稍候…");
        CompletableFuture.supplyAsync(() -> {
            try { return activeEurope ? europePmc.search(query, requestedPage, newest) : service.search(query, requestedPage, newest); }
            catch (Exception e) { throw new CompletionException(e); }
        }).whenComplete((found, error) -> Platform.runLater(() -> {
            searching = false;
            if (error != null) {
                status.setText("检索失败：" + message(error));
            } else {
                activeQuery = query; activeNewest = newest; page = requestedPage; total = found.total();
                results.getItems().setAll(found.papers());
                results.setPlaceholder(new Label("没有找到论文，请尝试更简短的英文关键词"));
                status.setText("“" + query + "” · 共 " + total + " 篇 · 来源 " + (activeEurope ? "Europe PMC（开放获取）" : "arXiv（含预印本）"));
                pageLabel.setText("第 " + (page + 1) + " / " + Math.max(1, Math.min(100, (total + 9) / 10)) + " 页");
                if (!found.papers().isEmpty()) results.getSelectionModel().selectFirst();
            }
            updateButtons();
        }));
    }

    private void updateButtons() {
        searchButton.setDisable(searching); sort.setDisable(searching); sourceChoice.setDisable(searching);
        previousButton.setDisable(searching || page == 0);
        nextButton.setDisable(searching || (page + 1) * PaperSearchService.PAGE_SIZE >= total || page >= 99);
        Paper paper = results.getSelectionModel().getSelectedItem();
        boolean noPdf = paper == null || paper.pdfUrl().isBlank();
        readButton.setDisable(downloading || noPdf); downloadButton.setDisable(downloading || noPdf);
        browserButton.setDisable(noPdf); sourceButton.setDisable(paper == null);
    }
    @FXML private void read() { retrieve(true); }
    @FXML private void download() { retrieve(false); }
    @FXML private void browser() {
        Paper paper = results.getSelectionModel().getSelectedItem();
        if (paper != null && !paper.pdfUrl().isBlank()) openBrowser(paper.pdfUrl());
    }
    @FXML private void source() {
        Paper paper = results.getSelectionModel().getSelectedItem();
        if (paper != null) openBrowser(paper.sourceUrl());
    }
    private void openBrowser(String url) {
        CompletableFuture.runAsync(() -> {
            try {
                if (!java.awt.Desktop.isDesktopSupported() || !java.awt.Desktop.getDesktop().isSupported(java.awt.Desktop.Action.BROWSE))
                    throw new java.io.IOException("系统不支持打开浏览器");
                java.awt.Desktop.getDesktop().browse(java.net.URI.create(url));
            } catch (Exception e) { throw new CompletionException(e); }
        }).whenComplete((ignored, error) -> Platform.runLater(() -> {
            if (error != null) fileStatus.setText("浏览器打开失败：" + message(error));
        }));
    }
    private void retrieve(boolean preview) {
        Paper paper = results.getSelectionModel().getSelectedItem();
        if (paper == null || downloading || paper.pdfUrl().isBlank()) return;
        java.io.File destination;
        if (!preview) {
            FileChooser chooser = new FileChooser(); chooser.setTitle("保存论文全文");
            chooser.setInitialFileName(paper.sourceName().replace(' ', '-') + "-" + paper.id().replace('/', '-') + ".pdf");
            chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("PDF 论文", "*.pdf"));
            destination = chooser.showSaveDialog(results.getScene().getWindow());
            if (destination == null) return;
        } else destination = null;
        downloading = true; updateButtons(); fileStatus.setText("正在获取 " + paper.id() + " 的 PDF…");
        byte[] existing = paper.equals(cachedPaper) ? cachedPdf : null;
        CompletableFuture.supplyAsync(() -> {
            try {
                byte[] bytes = existing != null ? existing : service.download(paper);
                if (destination != null) {
                    Path target = destination.toPath().toAbsolutePath();
                    Path temporary = Files.createTempFile(target.getParent(), ".paper-", ".part");
                    try { Files.write(temporary, bytes); Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING); }
                    finally { Files.deleteIfExists(temporary); }
                }
                return bytes;
            } catch (Exception e) { throw new CompletionException(e); }
        }).whenComplete((bytes, error) -> Platform.runLater(() -> {
            downloading = false; updateButtons();
            if (error != null) { fileStatus.setText("全文获取失败：" + message(error) + "。可尝试浏览器打开。"); return; }
            cachedPaper = paper; cachedPdf = bytes;
            if (preview) {
                util.pdf.BookPreview.show(results.getScene().getWindow(), paper.title(), bytes);
                fileStatus.setText("已打开 " + paper.id() + " 的内部阅读窗口");
            } else fileStatus.setText("已保存：" + destination.getAbsolutePath());
        }));
    }
    private static String message(Throwable error) {
        while (error.getCause() != null) error = error.getCause();
        return error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
    }
}
