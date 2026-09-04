package util.pdf;

import org.apache.fontbox.ttf.TrueTypeCollection;
import org.apache.fontbox.ttf.TrueTypeFont;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.font.PDType0Font;

import java.io.File;
import java.io.IOException;

/**
 * PDF 字体配置。
 *
 * <p>约定：
 * 中文（含全角标点）使用宋体（SimSun），
 * 英文、数字使用 Times New Roman。</p>
 *
 * <p>二者均为 Windows 系统自带字体，
 * 无需在项目中额外打包字体文件。</p>
 */
public final class PdfFontManage {

    private static final String WINDOWS_FONT_DIR =
            "C:/Windows/Fonts/";

    /**
     * 中文：宋体（SimSun）。
     *
     * <p>simsun.ttc 是字体集合（TTC），
     * 需要从中取出 SimSun 这一款。</p>
     */
    private static final String SIMSUN_PATH =
            WINDOWS_FONT_DIR + "simsun.ttc";

    private static final String SIMSUN_NAME =
            "SimSun";

    /**
     * 英文：Times New Roman。
     */
    private static final String TIMES_NEW_ROMAN_PATH =
            WINDOWS_FONT_DIR + "times.ttf";

    private PdfFontManage() {
    }

    /**
     * 加载中文字体：宋体（SimSun）。
     *
     * @param document 目标 PDF 文档
     */
    public static PDType0Font loadChineseFont(
            PDDocument document) throws IOException {

        File fontFile =
                new File(SIMSUN_PATH);

        if (!fontFile.exists()) {
            throw new IOException(
                    "未找到中文字体（宋体）："
                            + fontFile.getAbsolutePath());
        }

        try (TrueTypeCollection collection =
                     new TrueTypeCollection(fontFile)) {

            TrueTypeFont simsun =
                    collection.getFontByName(SIMSUN_NAME);

            if (simsun == null) {
                throw new IOException(
                        "字体集合中未找到宋体："
                                + SIMSUN_NAME);
            }

            return PDType0Font.load(
                    document,
                    simsun,
                    true);
        }
    }

    /**
     * 加载英文字体：Times New Roman。
     *
     * @param document 目标 PDF 文档
     */
    public static PDType0Font loadTimesNewRoman(
            PDDocument document) throws IOException {

        File fontFile =
                new File(TIMES_NEW_ROMAN_PATH);

        if (!fontFile.exists()) {
            throw new IOException(
                    "未找到英文字体（Times New Roman）："
                            + fontFile.getAbsolutePath());
        }

        return PDType0Font.load(
                document,
                fontFile);
    }
}
