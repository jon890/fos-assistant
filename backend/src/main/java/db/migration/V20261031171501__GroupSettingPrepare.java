package db.migration;

import java.sql.Statement;
import java.util.List;
import java.util.Locale;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

/**
 * 그룹 모델 설정 셋({@code model_tier_definition}, {@code model_tier_group_setting}, {@code model_hidden})을
 * {@code group_setting} 하나로 합치는 첫 단계다. 결정은 ADR-20261101 / model-setting 의 「데이터 모델」 이 갖는다.
 *
 * <p>새 표를 만들지 않고 기본 단계 표의 이름을 바꿔 넓힌다. 이 단계는 표 이름과 칸만 바꾼다. 줄은
 * {@code V20261031171502} 가 옮기고 {@code V20261031171503} 이 옛 칸과 표를 지운다. 표 설명을 바꾸는 문법이 MySQL 과
 * H2 에서 달라 SQL 파일이 아니라 여기서 나눠 실행한다. 여러 칸을 한 문장으로 더하는 문법도 둘이 달라 칸마다 따로 더한다.
 */
public class V20261031171501__GroupSettingPrepare extends BaseJavaMigration {

    private static final String TABLE_COMMENT = "그룹 설정. 그룹과 설정 종류마다 JSON 한 줄을 둔다";


    @Override
    public void migrate(Context context) throws Exception {
        boolean mysql = context.getConnection().getMetaData().getDatabaseProductName().toLowerCase(Locale.ROOT).contains("mysql");
        try (Statement statement = context.getConnection().createStatement()) {
            statement.execute("ALTER TABLE model_tier_group_setting RENAME TO group_setting");
            statement.execute(mysql
                ? "ALTER TABLE group_setting COMMENT = '" + TABLE_COMMENT + "'"
                : "COMMENT ON TABLE group_setting IS '" + TABLE_COMMENT + "'");
            for (String column : List.of(
                "setting_key VARCHAR(32) NULL COMMENT '설정 종류. MODEL 은 단계별 모델과 그룹 기본 단계, 숨김 목록이다'",
                "setting_value TEXT NULL COMMENT '설정 JSON 문자열. 형식과 버전은 Control Plane 이 저장할 때 검사한다'",
                "updated_at DATETIME(6) NULL COMMENT '설정을 마지막으로 바꾼 UTC 시각, 옮겨 온 줄은 바꾼 시각을 몰라 NULL'")) {
                statement.execute("ALTER TABLE group_setting ADD COLUMN " + column);
            }
        }
    }
}
