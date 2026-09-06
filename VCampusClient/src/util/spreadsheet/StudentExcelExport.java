package util.spreadsheet;

import entity.Student;
import java.io.*;
import java.lang.reflect.*;
import java.nio.charset.StandardCharsets;
import java.sql.Date;
import java.util.*;
import java.util.zip.*;

/** 将 Student 实体的全部业务字段导出为无需额外依赖的标准 XLSX 工作簿。 */
public final class StudentExcelExport {
    private StudentExcelExport(){}

    public static void export(List<Student> students,File outputFile) throws IOException{
        if(students==null||students.isEmpty())throw new IllegalArgumentException("学生列表不能为空");
        exportRows(students,Student.class,"学生信息",outputFile);
    }

    static void exportRows(List<?> rows,Class<?> entityType,String sheetName,File outputFile) throws IOException{
        if(rows==null||rows.isEmpty())throw new IllegalArgumentException("导出列表不能为空");
        if(outputFile==null)throw new IllegalArgumentException("输出文件不能为空");
        List<Field> columns=Arrays.stream(entityType.getDeclaredFields()).filter(field->!Modifier.isStatic(field.getModifiers())).peek(field->field.setAccessible(true)).toList();
        try(ZipOutputStream zip=new ZipOutputStream(new FileOutputStream(outputFile))){
            write(zip,"[Content_Types].xml",contentTypes());
            write(zip,"_rels/.rels",rootRelationships());
            write(zip,"xl/workbook.xml",workbook(sheetName));
            write(zip,"xl/_rels/workbook.xml.rels",workbookRelationships());
            write(zip,"xl/styles.xml",styles());
            write(zip,"xl/worksheets/sheet1.xml",sheet(rows,columns));
        }
    }

    private static String sheet(List<?> students,List<Field> columns){
        StringBuilder xml=new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?><worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\"><sheetViews><sheetView workbookViewId=\"0\"><pane ySplit=\"1\" topLeftCell=\"A2\" activePane=\"bottomLeft\" state=\"frozen\"/></sheetView></sheetViews><cols>");
        for(int i=0;i<columns.size();i++)xml.append("<col min=\"").append(i+1).append("\" max=\"").append(i+1).append("\" width=\"").append(columnWidth(columns.get(i))).append("\" customWidth=\"1\"/>");
        xml.append("</cols><sheetData><row r=\"1\">");
        for(int i=0;i<columns.size();i++)cell(xml,i,1,columns.get(i).getName(),1);
        xml.append("</row>");
        for(int row=0;row<students.size();row++){
            xml.append("<row r=\"").append(row+2).append("\">");
            for(int col=0;col<columns.size();col++)cell(xml,col,row+2,value(columns.get(col),students.get(row)),0);
            xml.append("</row>");
        }
        return xml.append("</sheetData><autoFilter ref=\"A1:").append(columnName(columns.size()-1)).append(students.size()+1).append("\"/></worksheet>").toString();
    }

    private static void cell(StringBuilder xml,int column,int row,String value,int style){
        xml.append("<c r=\"").append(columnName(column)).append(row).append("\" t=\"inlineStr\" s=\"").append(style).append("\"><is><t xml:space=\"preserve\">").append(escape(value)).append("</t></is></c>");
    }

    private static String value(Field field,Object student){
        try{
            Object value=field.get(student);
            if(value==null)return "";
            if(value instanceof Boolean booleanValue)return booleanValue?"是":"否";
            if(value instanceof Date date)return date.toLocalDate().toString();
            return String.valueOf(value);
        }catch(IllegalAccessException exception){throw new IllegalStateException("无法读取字段："+field.getName(),exception);}
    }

    private static double columnWidth(Field field){
        String name=field.getName().toLowerCase();
        if(name.contains("address")||name.contains("residence")||name.contains("school"))return 24;
        if(name.contains("college")||name.contains("major")||name.contains("email"))return 20;
        if(name.contains("date"))return 13;
        return 15;
    }

    private static String columnName(int index){
        StringBuilder name=new StringBuilder();
        for(int value=index+1;value>0;value=(value-1)/26)name.insert(0,(char)('A'+(value-1)%26));
        return name.toString();
    }

    private static String escape(String value){return value.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;").replace("\"","&quot;");}
    private static void write(ZipOutputStream zip,String path,String content) throws IOException{zip.putNextEntry(new ZipEntry(path));zip.write(content.getBytes(StandardCharsets.UTF_8));zip.closeEntry();}
    private static String contentTypes(){return "<?xml version=\"1.0\" encoding=\"UTF-8\"?><Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\"><Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/><Default Extension=\"xml\" ContentType=\"application/xml\"/><Override PartName=\"/xl/workbook.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml\"/><Override PartName=\"/xl/worksheets/sheet1.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/><Override PartName=\"/xl/styles.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml\"/></Types>";}
    private static String rootRelationships(){return "<?xml version=\"1.0\" encoding=\"UTF-8\"?><Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\"><Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"xl/workbook.xml\"/></Relationships>";}
    private static String workbook(String sheetName){return "<?xml version=\"1.0\" encoding=\"UTF-8\"?><workbook xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\"><sheets><sheet name=\""+escape(sheetName)+"\" sheetId=\"1\" r:id=\"rId1\"/></sheets></workbook>";}
    private static String workbookRelationships(){return "<?xml version=\"1.0\" encoding=\"UTF-8\"?><Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\"><Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\" Target=\"worksheets/sheet1.xml\"/><Relationship Id=\"rId2\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles\" Target=\"styles.xml\"/></Relationships>";}
    private static String styles(){return "<?xml version=\"1.0\" encoding=\"UTF-8\"?><styleSheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\"><fonts count=\"2\"><font><sz val=\"11\"/><name val=\"Microsoft YaHei\"/></font><font><b/><color rgb=\"FFFFFFFF\"/><sz val=\"11\"/><name val=\"Microsoft YaHei\"/></font></fonts><fills count=\"3\"><fill><patternFill patternType=\"none\"/></fill><fill><patternFill patternType=\"gray125\"/></fill><fill><patternFill patternType=\"solid\"><fgColor rgb=\"FF496A4F\"/><bgColor indexed=\"64\"/></patternFill></fill></fills><borders count=\"1\"><border><left/><right/><top/><bottom/><diagonal/></border></borders><cellStyleXfs count=\"1\"><xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\"/></cellStyleXfs><cellXfs count=\"2\"><xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\" xfId=\"0\"/><xf numFmtId=\"0\" fontId=\"1\" fillId=\"2\" borderId=\"0\" xfId=\"0\" applyFont=\"1\" applyFill=\"1\" applyAlignment=\"1\"><alignment horizontal=\"center\"/></xf></cellXfs></styleSheet>";}
}
