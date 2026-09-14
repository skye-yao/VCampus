package util;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.geometry.Rectangle2D;
import javafx.scene.Cursor;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.*;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import javafx.embed.swing.SwingFXUtils;
import javafx.stage.Window;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.Optional;

/** 固定取景框，拖动图片和缩放均围绕当前裁剪中心计算。 */
public final class AvatarCropDialog {
    private static final double AREA = 360, FRAME = 260, EDGE = (AREA - FRAME) / 2;
    private final BufferedImage source;
    private final Image image;
    private final Canvas canvas = new Canvas(AREA, AREA);
    private final ImageView preview = new ImageView();
    private final Slider zoom = new Slider(1, 5, 1);
    private double centerX, centerY, pressX, pressY;

    private AvatarCropDialog(BufferedImage source) {
        this.source = source;
        image = SwingFXUtils.toFXImage(source, null);
        centerX = source.getWidth() / 2.0;
        centerY = source.getHeight() / 2.0;
    }

    public static Optional<byte[]> show(Window owner, BufferedImage source) {
        return new AvatarCropDialog(source).show(owner);
    }

    private double scale() { return FRAME / Math.min(source.getWidth(), source.getHeight()) * zoom.getValue(); }

    private void redraw() {
        double scale = scale(), side = FRAME / scale, half = side / 2;
        centerX = Math.max(half, Math.min(source.getWidth() - half, centerX));
        centerY = Math.max(half, Math.min(source.getHeight() - half, centerY));
        GraphicsContext g = canvas.getGraphicsContext2D();
        g.setFill(Color.web("#e9eeea")); g.fillRect(0, 0, AREA, AREA);
        g.drawImage(image, AREA / 2 - centerX * scale, AREA / 2 - centerY * scale,
                source.getWidth() * scale, source.getHeight() * scale);
        g.setFill(Color.rgb(0, 0, 0, 0.48));
        g.fillRect(0, 0, AREA, EDGE); g.fillRect(0, AREA - EDGE, AREA, EDGE);
        g.fillRect(0, EDGE, EDGE, FRAME); g.fillRect(AREA - EDGE, EDGE, EDGE, FRAME);
        g.setStroke(Color.WHITE); g.setLineWidth(2); g.strokeRect(EDGE, EDGE, FRAME, FRAME);
        preview.setViewport(new Rectangle2D(centerX - half, centerY - half, side, side));
    }

    private Optional<byte[]> show(Window owner) {
        return createDialog(owner).showAndWait();
    }

    private Dialog<byte[]> createDialog(Window owner) {
        Dialog<byte[]> dialog = new Dialog<>();
        dialog.setTitle("调整头像");
        if (owner != null) dialog.initOwner(owner);
        ButtonType save = new ButtonType("保存头像", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(save, ButtonType.CANCEL);
        dialog.getDialogPane().getStylesheets().add(AvatarCropDialog.class.getResource("/resources/css/style.css").toExternalForm());
        dialog.getDialogPane().setStyle("-fx-background-color: #f7f9f7;");
        dialog.getDialogPane().lookupButton(save).getStyleClass().add("btn-primary");
        ((Button) dialog.getDialogPane().lookupButton(ButtonType.CANCEL)).setText("取消");
        Label title = new Label("拖动图片，调整头像位置"); title.getStyleClass().add("subpanel-title");
        Label hint = new Label("滚轮或滑块缩放 · 白框内为保存区域"); hint.getStyleClass().add("hint-text");
        preview.setImage(image); preview.setFitWidth(112); preview.setFitHeight(112);
        preview.setClip(new Circle(56, 56, 56));
        StackPane previewPane = new StackPane(preview);
        previewPane.setMinSize(112,112); previewPane.setMaxSize(112,112);
        previewPane.setStyle("-fx-background-color: #e9eeea; -fx-background-radius: 56;");
        Label previewNote = new Label("圆形头像预览\n保存为 512 × 512 图片");
        previewNote.getStyleClass().add("hint-text");
        VBox previewBox = new VBox(14, new Label("头像预览"), previewPane, previewNote);
        previewBox.setAlignment(Pos.CENTER); previewBox.setPrefWidth(160);
        HBox images = new HBox(22, canvas, previewBox); images.setAlignment(Pos.CENTER_LEFT);
        Button reset = new Button("重置"); reset.getStyleClass().add("btn-secondary");
        HBox zoomRow = new HBox(12, new Label("缩小"), zoom, new Label("放大"), reset);
        zoomRow.setAlignment(Pos.CENTER_LEFT); HBox.setHgrow(zoom, Priority.ALWAYS);
        Label error = new Label(); error.setStyle("-fx-text-fill: #b63b3b;"); error.setWrapText(true);
        error.setManaged(false); error.setVisible(false);
        VBox content = new VBox(12, title, hint, images, zoomRow, error); content.setPadding(new Insets(16));
        dialog.getDialogPane().setContent(content);
        canvas.setCursor(Cursor.OPEN_HAND);
        canvas.setOnMousePressed(e -> { pressX = e.getX(); pressY = e.getY(); canvas.setCursor(Cursor.CLOSED_HAND); });
        canvas.setOnMouseDragged(e -> {
            centerX -= (e.getX() - pressX) / scale(); centerY -= (e.getY() - pressY) / scale();
            pressX = e.getX(); pressY = e.getY(); redraw();
        });
        canvas.setOnMouseReleased(e -> canvas.setCursor(Cursor.OPEN_HAND));
        canvas.setOnScroll(e -> {
            zoom.setValue(Math.max(1, Math.min(5, zoom.getValue() * Math.exp(e.getDeltaY() * 0.002))));
            e.consume();
        });
        zoom.valueProperty().addListener((obs, oldValue, newValue) -> redraw());
        reset.setOnAction(e -> { centerX = source.getWidth()/2.0; centerY = source.getHeight()/2.0; zoom.setValue(1); redraw(); });
        byte[][] result = {null};
        dialog.getDialogPane().lookupButton(save).addEventFilter(javafx.event.ActionEvent.ACTION, e -> {
            try {
                int side = Math.max(1, (int)Math.floor(FRAME / scale()));
                int x = Math.max(0, Math.min(source.getWidth()-side, (int)Math.round(centerX-side/2.0)));
                int y = Math.max(0, Math.min(source.getHeight()-side, (int)Math.round(centerY-side/2.0)));
                result[0] = AvatarImages.crop(source, x, y, side);
            } catch (IOException | RuntimeException failure) {
                error.setText("生成头像失败：" + failure.getMessage()); error.setVisible(true); error.setManaged(true); e.consume();
            }
        });
        dialog.setResultConverter(button -> button == save ? result[0] : null);
        redraw();
        return dialog;
    }
}
