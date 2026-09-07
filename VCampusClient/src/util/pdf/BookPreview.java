package util.pdf;

import javafx.application.Platform;
import javafx.embed.swing.SwingFXUtils;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.image.ImageView;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.stage.Stage;
import javafx.stage.Window;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.rendering.PDFRenderer;

/** 每次只渲染当前页，PDF 解析与渲染在后台进行。 */
public final class BookPreview {
    private int page;
    private int pages;
    private boolean closed;
    private final Button previous = new Button("上一页");
    private final Button next = new Button("下一页");
    private final Label status = new Label();
    private final ImageView image = new ImageView();

    public static void show(Window owner, String title, byte[] bytes) {
        new BookPreview().open(owner, title, bytes);
    }

    private void open(Window owner, String title, byte[] bytes) {
        Stage stage = new Stage();
        stage.initOwner(owner);
        stage.setTitle("在线预览 · " + title);
        image.setPreserveRatio(true);
        ScrollPane scroll = new ScrollPane(image);
        scroll.viewportBoundsProperty().addListener((obs, before, bounds) -> image.setFitWidth(Math.max(100, bounds.getWidth() - 20)));
        BorderPane root = new BorderPane(scroll);
        HBox bar = new HBox(15, previous, status, next);
        bar.setStyle("-fx-padding: 12; -fx-alignment: center;");
        root.setBottom(bar);
        previous.setOnAction(e -> { page--; render(bytes); });
        next.setOnAction(e -> { page++; render(bytes); });
        stage.setOnHidden(e -> closed = true);
        stage.setScene(new Scene(root, 850, 700));
        stage.show();
        render(bytes);
    }

    private void render(byte[] bytes) {
        previous.setDisable(true);
        next.setDisable(true);
        status.setText("正在加载第 " + (page + 1) + " 页…");
        int requestedPage = page;
        java.util.concurrent.CompletableFuture.supplyAsync(() -> {
            try (var document = Loader.loadPDF(bytes)) {
                int count = document.getNumberOfPages();
                if (count == 0) throw new java.io.IOException("PDF 没有可预览的页面");
                var box = document.getPage(requestedPage).getCropBox();
                float edge = Math.max(box.getWidth(), box.getHeight());
                if (!Float.isFinite(edge) || edge <= 0) throw new java.io.IOException("PDF 页面尺寸无效");
                var renderer = new PDFRenderer(document);
                renderer.setSubsamplingAllowed(true);
                var rendered = renderer.renderImage(requestedPage, Math.min(1.5f, 1600f / edge));
                return new Object[] {count, SwingFXUtils.toFXImage(rendered, null)};
            } catch (Exception e) { throw new java.util.concurrent.CompletionException(e); }
        }).whenComplete((result, error) -> Platform.runLater(() -> {
            if (closed) return;
            if (error != null) {
                status.setText("预览失败：PDF 损坏、加密或无法读取");
                previous.setDisable(page <= 0);
                return;
            }
            pages = (Integer) result[0];
            image.setImage((javafx.scene.image.Image) result[1]);
            status.setText((page + 1) + " / " + pages + " 页");
            previous.setDisable(page <= 0);
            next.setDisable(page >= pages - 1);
        }));
    }
}
