package util;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

import java.util.ArrayList;
import java.util.List;

/** Moves information management's secondary sidebars into compact top navigation bars. */
public final class InformationTopNavigation {
    private InformationTopNavigation() {}

    public static void install(Parent root) {
        List<BorderPane> panes = new ArrayList<>();
        collect(root, panes);
        panes.forEach(InformationTopNavigation::moveSidebarToTop);
    }

    private static void collect(Parent parent, List<BorderPane> panes) {
        if (parent instanceof BorderPane pane && pane.getLeft() != null && hasSidebar(pane.getLeft())) panes.add(pane);
        if (parent instanceof TabPane tabs) {
            for (Tab tab : tabs.getTabs()) {
                Node content = tab.getContent();
                if (content instanceof Parent nested) collect(nested, panes);
            }
        }
        for (Node child : parent.getChildrenUnmodifiable()) if (child instanceof Parent nested) collect(nested, panes);
    }

    private static boolean hasSidebar(Node node) {
        if (node instanceof VBox box && box.getStyleClass().contains("student-sidebar")) return true;
        if (node instanceof Parent parent) for (Node child : parent.getChildrenUnmodifiable()) if (hasSidebar(child)) return true;
        return false;
    }

    private static void moveSidebarToTop(BorderPane pane) {
        List<VBox> sidebars = new ArrayList<>();
        findSidebars(pane.getLeft(), sidebars);
        if (sidebars.isEmpty()) return;
        StackPane holder = new StackPane();
        holder.getStyleClass().add("information-top-navigation-holder");
        holder.setMaxWidth(Double.MAX_VALUE);
        for (VBox sidebar : sidebars) {
            HBox bar = new HBox(8);
            bar.setAlignment(Pos.CENTER_LEFT);
            bar.setPadding(new Insets(10, 18, 10, 18));
            bar.setMaxWidth(Double.MAX_VALUE);
            bar.getStyleClass().add("information-top-navigation");
            bar.visibleProperty().bind(sidebar.visibleProperty());
            bar.managedProperty().bind(sidebar.managedProperty());
            List<Button> buttons = sidebar.getChildren().stream().filter(Button.class::isInstance).map(Button.class::cast).toList();
            sidebar.getChildren().removeAll(buttons);
            for (Button button : buttons) {
                button.setMaxWidth(Region.USE_PREF_SIZE);
                button.setMinWidth(Region.USE_PREF_SIZE);
                bar.getChildren().add(button);
            }
            holder.getChildren().add(bar);
        }
        ScrollPane scroll = new ScrollPane(holder);
        scroll.setFitToHeight(true);
        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        scroll.setVbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scroll.setPannable(true);
        scroll.getStyleClass().add("information-top-navigation-scroll");
        pane.setLeft(null);
        pane.setTop(scroll);
    }

    private static void findSidebars(Node node, List<VBox> result) {
        if (node instanceof VBox box && box.getStyleClass().contains("student-sidebar")) { result.add(box); return; }
        if (node instanceof Parent parent) for (Node child : parent.getChildrenUnmodifiable()) findSidebars(child, result);
    }
}
