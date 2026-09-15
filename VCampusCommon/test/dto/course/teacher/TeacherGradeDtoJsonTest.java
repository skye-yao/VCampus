package dto.course.teacher;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * 教师成绩草稿、方案、快照与审计公共契约的 JSON 保真测试。
 *
 * <p>覆盖：缺失分数与 0.00 必须保持不同，0.00 是合法分数；分数保留原始小数位；
 * 方案固定四项且拒绝重复组成代码；BIGINT 标识在线上保持精确十进制字符串；
 * 方案组成、内容行与行级错误在构造与反序列化两条路径上都不可变；
 * 提交内容只携带 enrollmentId 与分数，不携带客户端总评/绩点。
 */
public final class TeacherGradeDtoJsonTest {
    private static final Gson GSON = new Gson();

    private static final String OFFERING_ID = "9007199254740993";
    private static final String ENROLLMENT_ID = "9007199254740995";
    private static final String ENROLLMENT_ID_2 = "9007199254740997";
    private static final String SUBMISSION_ID = "9007199254740999";
    private static final String REVIEW_COMMENT = "总分与平时分不一致，请核对后重新提交";
    private static final String TEACHER_UID = "00001234";
    private static final String STUDENT_UID = "00005678";
    private static final String ROSTER_DIGEST =
            "3f1c0d2b4a5e6f708192a3b4c5d6e7f8a9b0c1d2e3f405162738495a6b7c8d9e";

    public static void main(String[] args) {
        briefSnippetKeepsMissingScoresDistinctFromZero();
        zeroScoreIsLegalAndNotNormalised();
        scoresKeepTheirExactScaleThroughJson();
        schemeKeepsFourDistinctComponentsAndRejectsDuplicates();
        contentKeepsExactIdsAndNeverCarriesClientTotals();
        collectionsAreDefensiveAndUnmodifiable();
        System.out.println("TeacherGradeDtoJsonTest passed (6 scenarios)");
    }

    /** 计划文档里的样例原样执行：0.00 与缺失分数必须是两件不同的事。 */
    private static void briefSnippetKeepsMissingScoresDistinctFromZero() {
        GradeScoresDTO scores = new GradeScoresDTO(new BigDecimal("0.00"), null, null, new BigDecimal("95.90"));
        String json = new Gson().toJson(scores);
        GradeScoresDTO copy = new Gson().fromJson(json, GradeScoresDTO.class);
        if (copy.getDailyScore().compareTo(BigDecimal.ZERO) != 0 || copy.getMidtermScore() != null)
            throw new AssertionError("zero and missing score must remain different");
        require(copy.getExperimentScore() == null,
                "an untouched experiment score must stay missing after JSON");
        require(new BigDecimal("95.90").compareTo(copy.getFinaltermScore()) == 0,
                "a present finalterm score must survive JSON");
    }

    private static void zeroScoreIsLegalAndNotNormalised() {
        GradeScoresDTO copy = GSON.fromJson(
                GSON.toJson(new GradeScoresDTO(new BigDecimal("0.00"), null, null, null)),
                GradeScoresDTO.class);

        require(copy.getDailyScore().compareTo(BigDecimal.ZERO) == 0,
                "0.00 must round-trip as the legal zero score");
        require(copy.getDailyScore().scale() == 2,
                "0.00 must not be normalised into a bare 0");
        require(copy.getMidtermScore() == null,
                "a missing score must not be turned into 0.00");
    }

    private static void scoresKeepTheirExactScaleThroughJson() {
        String json = GSON.toJson(
                new GradeScoresDTO(null, null, null, new BigDecimal("95.90")));

        require(json.contains("\"finaltermScore\":95.90"),
                "a two-decimal score must keep its exact text on the wire, saw " + json);
        require(json.contains("\"dailyScore\":null") || !json.contains("dailyScore"),
                "a missing score must stay absent/null on the wire, saw " + json);

        GradeScoresDTO copy = GSON.fromJson(json, GradeScoresDTO.class);
        require("95.90".equals(copy.getFinaltermScore().toPlainString()),
                "95.90 must not lose its trailing zero after a JSON round trip");
        require(copy.getFinaltermScore().equals(new BigDecimal("95.90")),
                "the score must keep both its value and its scale");
    }

    private static void schemeKeepsFourDistinctComponentsAndRejectsDuplicates() {
        GradeSchemeDTO copy = GSON.fromJson(GSON.toJson(fullScheme()), GradeSchemeDTO.class);
        require(copy.getComponents().size() == 4, "all four components must survive JSON");
        require(copy.getComponents().get(0).getCode() == GradeComponentCodeDTO.DAILY
                        && copy.getComponents().get(3).getCode() == GradeComponentCodeDTO.FINALTERM,
                "component codes must survive JSON in order");
        require(copy.getComponents().get(1).isEnabled()
                        && !copy.getComponents().get(2).isEnabled(),
                "the enabled flag must survive JSON");
        require(copy.getComponents().get(1).getWeightBasisPoints() == 3000
                        && copy.getComponents().get(3).getWeightBasisPoints() == 7000,
                "weights must survive JSON as integer basis points");

        List<GradeComponentDTO> duplicated = new ArrayList<>(List.of(
                component(GradeComponentCodeDTO.DAILY, true, 3000),
                component(GradeComponentCodeDTO.DAILY, true, 7000),
                component(GradeComponentCodeDTO.EXPERIMENT, false, 0),
                component(GradeComponentCodeDTO.FINALTERM, true, 0)));
        expectIllegalArgument(() -> new GradeSchemeDTO(duplicated),
                "a duplicate component code must be rejected, not silently merged");

        List<GradeComponentDTO> missingOne = new ArrayList<>(List.of(
                component(GradeComponentCodeDTO.DAILY, true, 3000),
                component(GradeComponentCodeDTO.MIDTERM, true, 7000)));
        expectIllegalArgument(() -> new GradeSchemeDTO(missingOne),
                "a scheme must carry exactly the four fixed components");
    }

    private static void contentKeepsExactIdsAndNeverCarriesClientTotals() {
        GradeBookContentDTO content = content();
        JsonObject json = GSON.toJsonTree(content).getAsJsonObject();

        requireStringId(json, "offeringId", OFFERING_ID);
        JsonObject row = json.getAsJsonArray("rows").get(0).getAsJsonObject();
        requireStringId(row, "enrollmentId", ENROLLMENT_ID);
        require(!row.has("totalScore") && !row.has("gradePoint"),
                "the submit content must not carry a client-computed total or grade point");
        require(json.getAsJsonObject("scheme").getAsJsonArray("components").size() == 4,
                "the submitted content must carry its complete scheme");

        GradeBookContentDTO copy = GSON.fromJson(GSON.toJson(content), GradeBookContentDTO.class);
        require(OFFERING_ID.equals(copy.getOfferingId()),
                "the offering ID must stay exact beyond the JavaScript safe integer");
        require(ENROLLMENT_ID.equals(copy.getRows().get(0).getEnrollmentId()),
                "the first row enrollment ID must stay exact");
        require(copy.getExpectedRevision() == 3,
                "the expected draft revision must survive JSON as an integer");
        require(ROSTER_DIGEST.equals(copy.getRosterDigest()),
                "the roster digest must survive JSON unchanged");

        TeacherGradeBookDTO book = GSON.fromJson(GSON.toJson(book()), TeacherGradeBookDTO.class);
        JsonObject bookJson = GSON.toJsonTree(book()).getAsJsonObject();
        requireStringId(bookJson, "offeringId", OFFERING_ID);
        requireStringId(bookJson, "lastSubmissionId", SUBMISSION_ID);
        require(OFFERING_ID.equals(book.getOfferingId())
                        && SUBMISSION_ID.equals(book.getLastSubmissionId()),
                "the book offering and submission IDs must stay exact");
        require(ENROLLMENT_ID.equals(book.getRows().get(0).getEnrollmentId())
                        && STUDENT_UID.equals(book.getRows().get(0).getStudentUid()),
                "a row must keep its enrollment ID and the leading zeroes of the student UID");

        // 审核意见是只读状态界面要显示的那句话：有值时必须原样过 JSON，没有时保持 null。
        TeacherGradeBookDTO reviewed = new TeacherGradeBookDTO(OFFERING_ID, 4, ROSTER_DIGEST,
                "REJECTED", fullScheme(), List.of(gradeRow()), SUBMISSION_ID, null, true, null,
                false, REVIEW_COMMENT);
        TeacherGradeBookDTO reviewedCopy =
                GSON.fromJson(GSON.toJson(reviewed), TeacherGradeBookDTO.class);
        require(REVIEW_COMMENT.equals(reviewedCopy.getReviewComment()),
                "the review comment must survive JSON unchanged");
        JsonElement withoutComment = GSON.toJsonTree(book()).getAsJsonObject().get("reviewComment");
        require(withoutComment == null || withoutComment.isJsonNull(),
                "a book without a review comment must stay absent/null, never an empty string");

        TeacherGradeOfferingDTO offering = GSON.fromJson(
                GSON.toJson(offeringRow()), TeacherGradeOfferingDTO.class);
        require(OFFERING_ID.equals(offering.getOffering().getOfferingId()),
                "the grade offering row must keep the nested offering ID exact");
        require("PENDING".equals(offering.getState()) && offering.getEnteredCount() == 58
                        && offering.getMissingCount() == 2,
                "the grade offering state and counts must survive JSON");
        require(SUBMISSION_ID.equals(offering.getLastSubmissionId()),
                "the grade offering submission ID must stay exact");
    }

    private static void collectionsAreDefensiveAndUnmodifiable() {
        List<GradeComponentDTO> components = new ArrayList<>(fullScheme().getComponents());
        GradeSchemeDTO scheme = new GradeSchemeDTO(components);
        components.clear();
        require(scheme.getComponents().size() == 4, "the scheme must copy the caller's components");
        requireUnmodifiable(scheme.getComponents(), "constructed scheme components");

        List<GradeRowInputDTO> rows = new ArrayList<>(content().getRows());
        GradeBookContentDTO content = new GradeBookContentDTO(
                OFFERING_ID, 3, ROSTER_DIGEST, scheme, rows);
        rows.clear();
        require(content.getRows().size() == 1, "the content must copy the caller's rows");
        requireUnmodifiable(content.getRows(), "constructed content rows");

        List<String> errors = new ArrayList<>(List.of("平时成绩最多两位小数"));
        TeacherGradeRowDTO row = new TeacherGradeRowDTO(ENROLLMENT_ID, STUDENT_UID, "张三",
                new GradeScoresDTO(new BigDecimal("101.00"), null, null, null),
                null, null, false, errors);
        errors.clear();
        require(row.getErrors().size() == 1, "the row must copy the caller's error list");
        requireUnmodifiable(row.getErrors(), "constructed row errors");

        List<TeacherGradeRowDTO> bookRows = new ArrayList<>(List.of(row));
        TeacherGradeBookDTO book = new TeacherGradeBookDTO(OFFERING_ID, 4, ROSTER_DIGEST, "DRAFT",
                scheme, bookRows, null, null, true, null, false);
        bookRows.clear();
        require(book.getRows().size() == 1, "the book must copy the caller's rows");
        requireUnmodifiable(book.getRows(), "constructed book rows");

        TeacherGradeBookDTO empty = new TeacherGradeBookDTO(OFFERING_ID, 0, ROSTER_DIGEST, "DRAFT",
                null, null, null, null, true, null, false);
        require(empty.getRows().isEmpty(),
                "a virtual draft without rows must read back as an empty list");
        requireUnmodifiable(empty.getRows(), "null rows must read back unmodifiable");

        TeacherGradeBookDTO copy = GSON.fromJson(GSON.toJson(book), TeacherGradeBookDTO.class);
        requireUnmodifiable(copy.getRows(), "deserialized book rows");
        requireUnmodifiable(copy.getScheme().getComponents(), "deserialized scheme components");
        requireUnmodifiable(copy.getRows().get(0).getErrors(), "deserialized row errors");

        TeacherGradeRowDTO nullRow = new TeacherGradeRowDTO(ENROLLMENT_ID, STUDENT_UID, "张三",
                null, null, null, false, null);
        require(nullRow.getErrors().isEmpty(),
                "a row without errors must read back as an empty list");
        requireUnmodifiable(nullRow.getErrors(), "null row errors must read back unmodifiable");
    }

    private static GradeComponentDTO component(GradeComponentCodeDTO code, boolean enabled,
                                               int weightBasisPoints) {
        return new GradeComponentDTO(code, enabled, weightBasisPoints);
    }

    private static GradeSchemeDTO fullScheme() {
        return new GradeSchemeDTO(List.of(
                component(GradeComponentCodeDTO.DAILY, true, 3000),
                component(GradeComponentCodeDTO.MIDTERM, true, 3000),
                component(GradeComponentCodeDTO.EXPERIMENT, false, 0),
                component(GradeComponentCodeDTO.FINALTERM, true, 7000)));
    }

    private static GradeScoresDTO scores() {
        return new GradeScoresDTO(new BigDecimal("0.00"), null, null, new BigDecimal("95.90"));
    }

    private static GradeBookContentDTO content() {
        return new GradeBookContentDTO(OFFERING_ID, 3, ROSTER_DIGEST, fullScheme(),
                List.of(new GradeRowInputDTO(ENROLLMENT_ID, scores())));
    }

    private static TeacherGradeRowDTO gradeRow() {
        return new TeacherGradeRowDTO(ENROLLMENT_ID, STUDENT_UID, "张三", scores(),
                new BigDecimal("87.00"), new BigDecimal("3.5"), true, List.of());
    }

    private static TeacherGradeBookDTO book() {
        return new TeacherGradeBookDTO(OFFERING_ID, 4, ROSTER_DIGEST, "PENDING", fullScheme(),
                List.of(gradeRow(), new TeacherGradeRowDTO(ENROLLMENT_ID_2, "00005679", "李四",
                        null, null, null, false, List.of("缺少启用项分数"))),
                SUBMISSION_ID, null, false, null, false);
    }

    private static TeacherGradeOfferingDTO offeringRow() {
        return new TeacherGradeOfferingDTO(
                new TeacherOfferingDTO(OFFERING_ID, "CS101-2026-2-A", "程序设计基础 CS101-2026-2-A",
                        "9007199254741001", "CS101", "程序设计基础", 3.0, 2026, 2, 60, 30, "OPEN",
                        true, false),
                "PENDING", 58, 2, SUBMISSION_ID);
    }

    private static void requireStringId(JsonObject json, String key, String expected) {
        require(json.getAsJsonPrimitive(key).isString(), key + " must be a JSON string");
        require(expected.equals(json.get(key).getAsString()), key + " must remain exact on the wire");
    }

    private static void expectIllegalArgument(Runnable action, String message) {
        try {
            action.run();
        } catch (IllegalArgumentException expected) {
            return;
        }
        throw new AssertionError(message);
    }

    private static <T> void requireUnmodifiable(List<T> values, String label) {
        try {
            values.add(null);
            throw new AssertionError(label + " allowed append");
        } catch (UnsupportedOperationException expected) {
            // Expected immutable contract.
        }
        if (!values.isEmpty()) {
            try {
                values.set(0, values.get(0));
                throw new AssertionError(label + " allowed replacement");
            } catch (UnsupportedOperationException expected) {
                // Expected immutable contract.
            }
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
