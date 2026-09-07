package util.control;

import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;

/** 只清空关联的搜索框，不触发查询或重置其他条件。 */
public class SearchClearButton extends Button {
    private TextField target;

    public SearchClearButton() {
        super("空");
        setAlignment(Pos.CENTER);
        setMinSize(26, 26);
        setPrefSize(26, 26);
        setMaxSize(26, 26);
        setTooltip(new Tooltip("清空此搜索条件"));
        setAccessibleText("清空此搜索条件");
        getStyleClass().add("information-search-clear");
        getStylesheets().add(getClass().getResource("/resources/css/information-search.css").toExternalForm());
        setOnAction(event -> {
            if (target != null) {
                target.clear();
                target.requestFocus();
            }
        });
    }

    public TextField getTarget() { return target; }

    public void setTarget(TextField target) { this.target = target; }
}
