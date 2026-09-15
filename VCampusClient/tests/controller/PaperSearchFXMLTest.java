package controller;

import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.Region;
import javafx.embed.swing.SwingFXUtils;
import service.PaperSearchService.Paper;
import java.nio.file.*;
import java.util.concurrent.*;
import javax.imageio.ImageIO;

/** Real FXML/controller/CSS smoke test, rendered offscreen without network calls. */
public class PaperSearchFXMLTest {
    public static void main(String[] args) throws Exception {
        CompletableFuture<Void> done = new CompletableFuture<>();
        Platform.startup(() -> {
            try {
                for (int width : new int[]{860, 1280}) {
                    var loader = new FXMLLoader(PaperSearchFXMLTest.class.getResource("/resources/fxml/PaperSearchView.fxml"));
                    Region root = loader.load();
                    root.getStyleClass().add("library-root");
                    root.getStylesheets().add(PaperSearchFXMLTest.class.getResource("/resources/css/library.css").toExternalForm());
                    new Scene(root, width, 680); root.resize(width, 680);
                    var nodes = loader.getNamespace();
                    var read = (Button) nodes.get("readButton");
                    require(read.isDisabled(), "Read disabled without selection");
                    @SuppressWarnings("unchecked") var list = (ListView<Paper>) nodes.get("results");
                    list.getItems().add(new Paper("1706.03762", "Attention Is All You Need", "Sample authors for layout verification",
                            "2017-06-12", "Layout test: this summary checks wrapping and the paper details panel. ".repeat(15),
                            "cs.CL", "https://arxiv.org/pdf/1706.03762", "https://arxiv.org/abs/1706.03762"));
                    list.getSelectionModel().selectFirst();
                    require(!read.isDisabled(), "Read enabled with PDF");
                    require(((TextArea) nodes.get("abstractText")).getText().contains("Layout test"), "Selection details");
                    root.applyCss(); root.layout();
                    var path = Path.of("build-check/papers/papers-" + width + ".png"); Files.createDirectories(path.getParent());
                    ImageIO.write(SwingFXUtils.fromFXImage(root.snapshot(null, null), null), "png", path.toFile());
                    list.getItems().clear();
                    require(read.isDisabled(), "Read disabled after clearing results");
                    ((Button) nodes.get("searchButton")).fire();
                    require(((Label) nodes.get("status")).getText().contains("200"), "Empty input validation");
                }
                done.complete(null);
            } catch (Throwable error) { done.completeExceptionally(error); }
        });
        try { done.get(30, TimeUnit.SECONDS); System.out.println("PaperSearchFXMLTest PASS (860/1280 px)"); }
        finally { Platform.exit(); }
    }
    private static void require(boolean condition, String label) { if (!condition) throw new AssertionError(label); }
}
