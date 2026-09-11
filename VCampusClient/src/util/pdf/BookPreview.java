package util.pdf;

import javafx.application.Platform;
import javafx.embed.swing.SwingFXUtils;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.image.ImageView;
import javafx.scene.layout.*;
import javafx.stage.Stage;
import javafx.stage.Window;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.rendering.PDFRenderer;
import java.util.ArrayList;
import java.util.List;

/** 连续纵向阅读 PDF；页面按窗口宽度自适应，并支持页码输入跳转。 */
public final class BookPreview {
    private boolean closed; private int pages;
    private final List<ImageView> pageViews = new ArrayList<>();
    private final Button previous = new Button("上一页"), next = new Button("下一页");
    private final TextField pageInput = new TextField(); private final Label total = new Label("/ — 页"), hint = new Label("正在加载 PDF…");
    private ScrollPane scroll; private VBox pagesBox;
    private boolean editingPage;
    public static void show(Window owner, String title, byte[] bytes) { new BookPreview().open(owner,title,bytes); }
    private void open(Window owner,String title,byte[] bytes) {
        Stage stage=new Stage(); stage.initOwner(owner); stage.setTitle("在线预览 · "+title);
        pagesBox=new VBox(18); pagesBox.setStyle("-fx-padding:14; -fx-alignment:top-center;");
        scroll=new ScrollPane(pagesBox); scroll.setFitToWidth(true); scroll.setPannable(true);
        BorderPane root=new BorderPane(scroll); pageInput.setPrefColumnCount(5); pageInput.setPromptText("页码"); pageInput.setOnAction(e->jump());
        pageInput.setOnMousePressed(e -> beginPageEdit());
        pageInput.focusedProperty().addListener((obs, old, focused) -> {
            if (focused) beginPageEdit();
            else {
                editingPage = false;
                updatePageIndicator();
            }
        });
        pageInput.setOnKeyPressed(e -> {
            if (e.getCode() == javafx.scene.input.KeyCode.ESCAPE) {
                finishPageEdit();
                e.consume();
            }
        });
        scroll.vvalueProperty().addListener((obs, old, value) -> updatePageIndicator());
        scroll.viewportBoundsProperty().addListener((obs, old, bounds) -> updatePageIndicator());
        pagesBox.heightProperty().addListener((obs, old, height) -> updatePageIndicator());
        previous.setOnAction(e->jumpRelative(-1)); next.setOnAction(e->jumpRelative(1));
        HBox bar=new HBox(10,previous,pageInput,total,next,hint); bar.setStyle("-fx-padding:10; -fx-alignment:center;"); root.setBottom(bar);
        stage.setOnHidden(e->closed=true); stage.setScene(new Scene(root,900,760)); stage.show(); load(bytes);
    }
    private void load(byte[] bytes) {
        previous.setDisable(true); next.setDisable(true); pageInput.setDisable(true);
        java.util.concurrent.CompletableFuture.supplyAsync(()->{ try(var document=Loader.loadPDF(bytes)) {
            int count=document.getNumberOfPages(); if(count==0)throw new java.io.IOException("PDF 没有可预览的页面");
            PDFRenderer renderer=new PDFRenderer(document); renderer.setSubsamplingAllowed(true); List<javafx.scene.image.Image> images=new ArrayList<>();
            for(int i=0;i<count;i++){var box=document.getPage(i).getCropBox();float edge=Math.max(box.getWidth(),box.getHeight());if(!Float.isFinite(edge)||edge<=0)throw new java.io.IOException("PDF 页面尺寸无效"); images.add(SwingFXUtils.toFXImage(renderer.renderImage(i,Math.min(1.5f,1600f/edge)),null));} return images;
        }catch(Exception e){throw new java.util.concurrent.CompletionException(e);}}).whenComplete((images,error)->Platform.runLater(()->{if(closed)return;if(error!=null){hint.setText("预览失败：PDF 损坏、加密或无法读取");return;} pages=images.size();pageViews.clear();pagesBox.getChildren().clear();for(var image:images){ImageView view=new ImageView(image);view.setPreserveRatio(true);view.setSmooth(true);pageViews.add(view);pagesBox.getChildren().add(view);}scroll.viewportBoundsProperty().addListener((o,a,b)->fitPages(b.getWidth()));fitPages(scroll.getViewportBounds().getWidth());pageInput.setDisable(false);total.setText("/ "+pages+" 页");previous.setDisable(true);next.setDisable(pages<=1);hint.setText("输入页码后按 Enter 跳转，滚动页面阅读");}));
    }
    private void fitPages(double width){double fit=Math.max(100,width-36);for(ImageView view:pageViews)view.setFitWidth(fit);Platform.runLater(this::updatePageIndicator);}
    // 以可视区域内占比最大的页面作为当前页，不在长页面滚到一半时提前跳号。
    private int currentPage() {
        double height = scroll.getViewportBounds().getHeight();
        double top = scroll.getVvalue() * Math.max(0, pagesBox.getHeight() - height);
        int current = 0;
        double largestVisible = -1;
        for (int i = 0; i < pageViews.size(); i++) {
            var bounds = pageViews.get(i).getBoundsInParent();
            double visible = Math.max(0, Math.min(top + height, bounds.getMaxY()) - Math.max(top, bounds.getMinY()));
            if (visible > largestVisible) {
                largestVisible = visible;
                current = i;
            }
        }
        return current;
    }
    private void updatePageIndicator() {
        if (closed || pageViews.isEmpty()) return;
        int current = currentPage();
        if (!editingPage) pageInput.setText(String.valueOf(current + 1));
        previous.setDisable(current == 0);
        next.setDisable(current == pages - 1);
    }
    private void beginPageEdit() {
        if (pages == 0 || editingPage) return;
        editingPage = true;
        pageInput.clear();
    }
    private void finishPageEdit() {
        editingPage = false;
        scroll.requestFocus();
        updatePageIndicator();
    }
    private void jumpRelative(int delta){jumpTo(Math.max(0,Math.min(pages-1,currentPage()+delta)));}
    private void jump() {
        if (pageInput.getText().isBlank()) {
            finishPageEdit();
            return;
        }
        try {
            int requested = Integer.parseInt(pageInput.getText().trim());
            if (requested < 1 || requested > pages) throw new NumberFormatException();
            jumpTo(requested - 1);
            finishPageEdit();
            hint.setText("输入页码后按 Enter 跳转，滚动页面阅读");
        } catch (NumberFormatException e) {
            hint.setText("请输入 1 到 " + pages + " 的页码");
        }
    }
    private void jumpTo(int index){if(pages==0)return;index=Math.max(0,Math.min(pages-1,index));double max=Math.max(1,pagesBox.getHeight()-scroll.getViewportBounds().getHeight());scroll.setVvalue(Math.max(0,Math.min(1,pageViews.get(index).getBoundsInParent().getMinY()/max)));pageInput.setText(String.valueOf(index+1));previous.setDisable(index==0);next.setDisable(index==pages-1);}
}
