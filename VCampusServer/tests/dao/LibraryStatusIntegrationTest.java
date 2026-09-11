package dao;

/** 保留旧测试入口；全部状态组合现由使用临时表的集成测试覆盖，不再新建数据库。 */
public class LibraryStatusIntegrationTest {
    public static void main(String[] args) throws Exception {
        LibraryCirculationIntegrationTest.main(args);
    }
}