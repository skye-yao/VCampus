package util.control;

import javafx.scene.control.Label;
import javafx.scene.layout.*;
import vo.StudentOverviewVO;
import vo.TeacherOverviewVO;
import java.util.List;

/** Read-only related records, including every business field in the information forms. */
public final class InformationReviewRecords {
    private InformationReviewRecords() {}

    private static final String[] FAMILY = {
        "name:姓名", "relationship:与本人关系", "birthDate:出生年月", "healthStatus:健康状况",
        "registeredResidence:户口所在地", "phone:联系电话", "workplace:工作单位", "workplaceAddress:工作单位地址"
    };

    public static void student(VBox target, StudentOverviewVO value) {
        target.getChildren().clear();
        if (value == null) return;
        section(target, "奖励信息", value.getAwards(), "awardName:奖励名称", "awardType:奖励类型",
            "awardLevel:奖励级别", "awardDate:获奖日期", "organization:颁发单位", "description:备注");
        section(target, "资助信息", value.getAids(), "aidName:资助名称", "aidType:资助类型",
            "amount:金额", "aidDate:资助日期", "provider:资助单位", "status:状态", "description:备注");
        section(target, "主要学习经历（当前档案）", value.getExperiences(), "startDate:开始年月",
            "endDate:结束年月", "schoolName:学校名称", "educationLevel:学习阶段", "description:备注");
        section(target, "家庭主要关系成员（当前档案）", value.getFamilyMembers(), FAMILY);
    }

    public static void teacher(VBox target, TeacherOverviewVO value) {
        target.getChildren().clear();
        if (value == null) return;
        section(target, "工作经历", value.getWorkExperiences(), "startDate:开始年月", "endDate:结束年月",
            "organization:工作单位", "department:所在部门", "position:职务", "description:工作内容");
        section(target, "社会关系成员", value.getFamilyMembers(), FAMILY);
    }

    private static void section(VBox target, String title, List<?> records, String... fields) {
        Label heading = new Label(title);
        heading.getStyleClass().add("student-section-title");
        VBox panel = new VBox(8, heading);
        panel.getStyleClass().add("student-panel");
        target.getChildren().add(panel);
        if (records == null || records.isEmpty()) {
            panel.getChildren().add(new Label("暂无记录"));
            return;
        }
        for (Object record : records) {
            GridPane grid = new GridPane();
            grid.setHgap(12);
            grid.setVgap(9);
            for (int pair = 0; pair < 2; pair++) {
                ColumnConstraints key = new ColumnConstraints(136);
                ColumnConstraints value = new ColumnConstraints();
                value.setHgrow(Priority.ALWAYS);
                value.setMinWidth(0);
                grid.getColumnConstraints().addAll(key, value);
            }
            for (int i = 0; i < fields.length; i++) {
                String[] field = fields[i].split(":", 2);
                Label key = new Label(field[1]);
                key.getStyleClass().add("student-field-key");
                Label value = new Label(read(record, field[0]));
                value.getStyleClass().add("student-field-value");
                value.setWrapText(true);
                value.setMinWidth(0);
                value.setMaxWidth(Double.MAX_VALUE);
                grid.add(key, (i % 2) * 2, i / 2);
                grid.add(value, (i % 2) * 2 + 1, i / 2);
            }
            VBox card = new VBox(grid);
            card.getStyleClass().add("student-info-record-card");
            panel.getChildren().add(card);
        }
    }

    private static String read(Object record, String field) {
        try {
            Object value = record.getClass().getMethod("get" + Character.toUpperCase(field.charAt(0))
                + field.substring(1)).invoke(record);
            if (value instanceof enums.StudentAwardType type) return switch (type) {
                case SCHOLARSHIP -> "奖学金";
                case HONOR -> "荣誉称号";
                case COMPETITION -> "竞赛奖励";
                case RESEARCH -> "科研奖励";
                case PRACTICE -> "实践奖励";
                case OTHER -> "其他";
            };
            if (value instanceof enums.StudentAidStatus status) return switch (status) {
                case PENDING -> "待发放";
                case ISSUED -> "已发放";
                case CANCELLED -> "已取消";
            };
            return value == null || value.toString().isBlank() ? "-" : value.toString();
        } catch (ReflectiveOperationException exception) {
            throw new IllegalArgumentException("无法读取审核字段：" + field, exception);
        }
    }
}
