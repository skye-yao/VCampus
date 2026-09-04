package controller;
import app.ClientMain; import javafx.fxml.FXML;
public class InformationSelectController {
 @FXML private void back(){ClientMain.switchScene("/resources/fxml/MainView.fxml");}
 @FXML private void openStudent(){ClientMain.switchScene("/resources/fxml/StudentView.fxml");}
 @FXML private void openTeacher(){ClientMain.switchScene("/resources/fxml/TeacherView.fxml");}
}
