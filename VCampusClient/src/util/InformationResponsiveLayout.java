package util;

import javafx.application.Platform;
import javafx.geometry.Orientation;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TableView;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Region;
import javafx.scene.layout.TilePane;

import java.util.ArrayList;
import java.util.List;

/** Shared responsive behavior for the student and teacher information pages. */
public final class InformationResponsiveLayout {
    private InformationResponsiveLayout() {}

    public static void install(Region root) {
        if (root == null) return;
        Platform.runLater(() -> {
            prepareTree(root);
            update(root);
            root.widthProperty().addListener((observable, oldValue, newValue) -> update(root));
        });
    }

    private static void prepareTree(Parent parent) {
        for (Node node : new ArrayList<>(parent.getChildrenUnmodifiable())) {
            if (node instanceof Region region) region.setMinWidth(0);
            if (node instanceof ScrollPane scrollPane) {
                scrollPane.setFitToWidth(true);
                scrollPane.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
                if (scrollPane.getContent() instanceof Region content) {
                    content.setMinWidth(0);
                    content.setMaxWidth(Double.MAX_VALUE);
                }
            }
            if (node instanceof TableView<?> table) {
                table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
                table.setMinWidth(0);
            }
            if (node instanceof Parent child) prepareTree(child);
        }
        wrapToolbar(parent);
    }

    private static void wrapToolbar(Parent parent) {
        if (!(parent instanceof Pane pane)) return;
        List<Node> children = new ArrayList<>(pane.getChildren());
        for (int i = 0; i < children.size(); i++) {
            Node child = children.get(i);
            if (!(child instanceof javafx.scene.layout.HBox box)) continue;
            if (!box.getStyleClass().contains("student-search-bar")
                    && !box.getStyleClass().contains("student-pagination")) continue;
            FlowPane flow = new FlowPane(Orientation.HORIZONTAL, box.getSpacing(), 8);
            flow.setAlignment(box.getAlignment());
            flow.getStyleClass().setAll(box.getStyleClass());
            flow.setPadding(box.getPadding());
            List<Node> items = new ArrayList<>(box.getChildren());
            box.getChildren().clear();
            for (Node item : items) {
                if (item instanceof Region spacer && item.getClass() == Region.class) continue;
                flow.getChildren().add(item);
            }
            int actualIndex = pane.getChildren().indexOf(box);
            if (actualIndex >= 0) pane.getChildren().set(actualIndex, flow);
        }
    }

    private static void update(Region root) {
        double width = Math.max(480, root.getWidth());
        for (Node node : descendants(root)) {
            if (node instanceof Region region && region.getStyleClass().contains("student-sidebar")) {
                double sidebarWidth = width < 720 ? 124 : width < 980 ? 148 : 172;
                region.setMinWidth(sidebarWidth);
                region.setPrefWidth(sidebarWidth);
                region.setMaxWidth(sidebarWidth);
            }
            if (node instanceof TilePane tiles) {
                tiles.setPrefColumns(width < 650 ? 1 : width < 900 ? 2 : width < 1180 ? 3 : 4);
            }
        }
    }

    private static List<Node> descendants(Parent root) {
        List<Node> result = new ArrayList<>();
        collect(root, result);
        return result;
    }

    private static void collect(Parent parent, List<Node> result) {
        for (Node node : parent.getChildrenUnmodifiable()) {
            result.add(node);
            if (node instanceof Parent child) collect(child, result);
        }
    }
}
