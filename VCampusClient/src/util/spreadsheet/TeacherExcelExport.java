package util.spreadsheet;
import entity.Teacher;import java.io.*;import java.util.List;
/** 导出 Teacher 实体的全部业务字段。 */
public final class TeacherExcelExport {
 private TeacherExcelExport(){}
 public static void export(List<Teacher> teachers,File outputFile)throws IOException{
  StudentExcelExport.exportRows(teachers,Teacher.class,"教师信息",outputFile);
 }
}
