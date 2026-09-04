package util.pdf;

import java.util.List;

/**
 * 学生学籍登记表 PDF 布局定义。
 *
 * <p>本类仅负责定义 student_profile_template.pdf 中
 * 各动态数据的写入位置、最大宽度和建议字号。</p>
 *
 * <p>坐标体系采用 PDFBox 坐标：
 * 左下角为 (0, 0)，x 向右增加，y 向上增加。</p>
 */
public final class StudentPdfLayout {

    /**
     * 当前模板的实际页面尺寸。
     */
    public static final float PAGE_WIDTH = 595.32f;
    public static final float PAGE_HEIGHT = 841.92f;

    /**
     * 模板原始正文大约为 10.56pt，
     * 动态数据建议稍微小一点，避免顶格。
     */
    public static final float DEFAULT_FONT_SIZE = 10.0f;

    public static final float SMALL_FONT_SIZE = 9.0f;

    /**
     * 数据与单元格右边框之间预留的安全距离。
     */
    private static final float RIGHT_PADDING = 5.0f;

    private StudentPdfLayout() {
    }


    // =========================================================
    // 一、顶部学生基本信息
    // =========================================================

    /**
     * 第一行：
     * 姓名 | 性别 | 出生年月
     */
    public static final TextSlot NAME =
            slot(118.80f, 121.32f, 184.08f);

    public static final TextSlot GENDER =
            slot(253.60f, 121.32f, 331.80f);

    public static final TextSlot BIRTH_DATE =
            slot(395.28f, 121.32f, 460.32f);


    /**
     * 第二行：
     * 籍贯 | 民族 | 政治面貌
     */
    public static final TextSlot NATIVE_PLACE =
            slot(118.80f, 144.48f, 184.08f);

    public static final TextSlot NATIONALITY =
            slot(253.60f, 144.48f, 331.80f);

    public static final TextSlot POLITICAL_STATUS =
            slot(395.28f, 144.48f, 460.32f);


    /**
     * 第三行：
     * 学历 | 学院 | 专业
     */
    public static final TextSlot EDUCATION =
            slot(118.80f, 167.64f, 184.08f);

    public static final TextSlot COLLEGE =
            slot(253.60f, 167.64f, 331.80f);

    public static final TextSlot MAJOR =
            slot(395.30f, 167.64f, 460.32f);


    /**
     * 第四行：
     * 学制 | 入学日期
     *
     * 入学日期所在的数据格横向较宽。
     */
    public static final TextSlot SCHOOLING_LENGTH =
            slot(118.80f, 190.92f, 184.08f);

    public static final TextSlot ADMISSION_DATE =
            slot(253.58f, 190.92f, 460.32f);


    /**
     * 第五行：
     * 证件号码 | 联系电话
     */
    public static final TextSlot ID_NUMBER =
            slot(118.80f, 214.08f, 331.80f);

    public static final TextSlot TELEPHONE =
            slot(395.28f, 214.08f, 538.08f);


    /**
     * 第六行：
     * 家庭地址 | 电子邮箱
     */
    public static final TextSlot FAMILY_ADDRESS =
            slot(118.80f, 237.24f, 331.80f);

    public static final TextSlot EMAIL =
            slot(395.28f, 237.24f, 538.08f);


    // =========================================================
    // 二、照片区域
    // =========================================================

    /**
     * 模板右上角预留照片区域。
     *
     * 如果暂时没有学生照片，可以完全不使用。
     */
    public static final Box PHOTO =
            boxFromTop(
                    460.80f,
                    106.08f,
                    77.28f,
                    92.28f
            );


    // =========================================================
    // 三、主要经历
    // =========================================================

    /**
     * 模板共预留 5 行主要经历。
     *
     * 每行：
     *
     * 何年月起至何年月 | 在何地、何单位 | 任何职务
     */
    public static final List<ExperienceRow> EXPERIENCE_ROWS =
            List.of(
                    experienceRow(283.68f),
                    experienceRow(306.96f),
                    experienceRow(330.12f),
                    experienceRow(353.28f),
                    experienceRow(376.56f)
            );


    // =========================================================
    // 四、奖励
    // =========================================================

    /**
     * 模板共预留 4 行奖励。
     *
     * 每行：
     *
     * 时间 | 何种奖励
     */
    public static final List<AwardRow> AWARD_ROWS =
            List.of(
                    awardRow(422.88f),
                    awardRow(446.16f),
                    awardRow(469.32f),
                    awardRow(492.48f)
            );


    // =========================================================
    // 五、主要社会关系
    // =========================================================

    /**
     * 模板共预留 4 行社会关系。
     *
     * 每行：
     *
     * 姓名 | 与本人关系 | 工作单位 | 联系电话
     */
    public static final List<SocialRelationRow>
            SOCIAL_RELATION_ROWS =
            List.of(
                    socialRelationRow(538.92f),
                    socialRelationRow(562.08f),
                    socialRelationRow(585.36f),
                    socialRelationRow(608.52f)
            );


    // =========================================================
    // 六、本人承诺 / 签字区域
    // =========================================================

    /**
     * “本人签字：”后面的空白区域。
     *
     * 如果最终 PDF 不需要电子签名，可以不使用，
     * 保持空白供打印后手写。
     */
    public static final TextSlot SIGNATURE =
            new TextSlot(
                    400.0f,
                    pdfY(677.40f),
                    55.0f,
                    DEFAULT_FONT_SIZE
            );

    /**
     * 签字日期。
     *
     * 模板右下角已经固定印有：
     * 年 月 日
     *
     * 因此这里只写数字。
     */
    public static final TextSlot SIGN_YEAR =
            new TextSlot(
                    445.0f,
                    pdfY(677.40f),
                    21.0f,
                    DEFAULT_FONT_SIZE
            );

    public static final TextSlot SIGN_MONTH =
            new TextSlot(
                    482.0f,
                    pdfY(677.40f),
                    11.0f,
                    DEFAULT_FONT_SIZE
            );

    public static final TextSlot SIGN_DAY =
            new TextSlot(
                    509.0f,
                    pdfY(677.40f),
                    11.0f,
                    DEFAULT_FONT_SIZE
            );


    // =========================================================
    // 布局数据结构
    // =========================================================

    /**
     * 一个可以写入文字的区域。
     *
     * @param x        PDFBox X 坐标
     * @param y        PDFBox Y 坐标
     * @param maxWidth 文字最大允许宽度
     * @param fontSize 建议字号
     */
    public record TextSlot(
            float x,
            float y,
            float maxWidth,
            float fontSize
    ) {
    }


    /**
     * 图片等矩形区域。
     */
    public record Box(
            float x,
            float y,
            float width,
            float height
    ) {
    }


    /**
     * 一行主要经历。
     */
    public record ExperienceRow(
            TextSlot dateRange,
            TextSlot placeAndUnit,
            TextSlot duty
    ) {
    }


    /**
     * 一行奖励。
     */
    public record AwardRow(
            TextSlot date,
            TextSlot description
    ) {
    }


    /**
     * 一行主要社会关系。
     */
    public record SocialRelationRow(
            TextSlot name,
            TextSlot relation,
            TextSlot workUnit,
            TextSlot telephone
    ) {
    }


    // =========================================================
    // 内部布局创建方法
    // =========================================================

    private static ExperienceRow experienceRow(
            float baselineFromTop) {

        return new ExperienceRow(

                // 何年月起至何年月
                slot(
                        118.80f,
                        baselineFromTop,
                        212.40f
                ),

                // 在何地、何单位
                slot(
                        218.07f,
                        baselineFromTop,
                        460.32f
                ),

                // 任何职务
                slot(
                        466.13f,
                        baselineFromTop,
                        538.08f
                )
        );
    }


    private static AwardRow awardRow(
            float baselineFromTop) {

        return new AwardRow(

                // 时间
                slot(
                        118.80f,
                        baselineFromTop,
                        184.08f
                ),

                // 何种奖励
                slot(
                        189.77f,
                        baselineFromTop,
                        538.08f
                )
        );
    }


    private static SocialRelationRow socialRelationRow(
            float baselineFromTop) {

        return new SocialRelationRow(

                // 姓名
                slot(
                        118.80f,
                        baselineFromTop,
                        184.08f
                ),

                // 与本人关系
                slot(
                        189.77f,
                        baselineFromTop,
                        247.92f
                ),

                // 工作单位
                slot(
                        253.55f,
                        baselineFromTop,
                        460.32f
                ),

                // 联系电话
                slot(
                        466.13f,
                        baselineFromTop,
                        538.08f
                )
        );
    }


    /**
     * 根据模板中的“从页面顶部计算的文字基线”
     * 创建 PDFBox TextSlot。
     */
    private static TextSlot slot(
            float x,
            float baselineFromTop,
            float rightBoundary) {

        float maxWidth =
                rightBoundary
                        - x
                        - RIGHT_PADDING;

        return new TextSlot(
                x,
                pdfY(baselineFromTop),
                maxWidth,
                DEFAULT_FONT_SIZE
        );
    }


    /**
     * 将模板使用的“从顶部向下”的 Y 坐标
     * 转换为 PDFBox 的“从底部向上”坐标。
     */
    private static float pdfY(
            float baselineFromTop) {

        return PAGE_HEIGHT - baselineFromTop;
    }


    /**
     * 把以左上角为基准的矩形区域转换成 PDFBox 坐标。
     */
    private static Box boxFromTop(
            float x,
            float top,
            float width,
            float height) {

        return new Box(
                x,
                PAGE_HEIGHT - top - height,
                width,
                height
        );
    }
}