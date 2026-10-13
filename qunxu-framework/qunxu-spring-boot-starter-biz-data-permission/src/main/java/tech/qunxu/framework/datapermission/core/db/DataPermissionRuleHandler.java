package tech.qunxu.framework.datapermission.core.db;

import com.baomidou.mybatisplus.core.toolkit.PluginUtils;
import com.baomidou.mybatisplus.extension.plugins.inner.InnerInterceptor;
import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.session.ResultHandler;
import org.apache.ibatis.session.RowBounds;
import tech.qunxu.framework.datapermission.core.context.DataPermissionContext;

import java.sql.SQLException;
import java.util.Locale;
import java.util.Map;

/**
 * 数据权限 SQL 处理器。
 * <p>
 * 在查询执行前，为命中数据权限规则的表追加条件片段。
 * 注意：当前实现采用文本追加的方式，适用于 MyBatis Plus 生成的常规 SQL；
 * 复杂手写 SQL 建议改用 JSqlParser 解析方案。
 */
public class DataPermissionRuleHandler implements InnerInterceptor {

    /** SQL 关键字 */
    private static final String[] KEYWORDS = {" GROUP BY ", " ORDER BY ", " LIMIT ", " FOR UPDATE "};

    @Override
    public boolean willDoQuery(Executor executor, MappedStatement ms, Object parameter,
                               RowBounds rowBounds, ResultHandler resultHandler, BoundSql boundSql) {
        return true;
    }

    @Override
    public void beforeQuery(Executor executor, MappedStatement ms, Object parameter,
                            RowBounds rowBounds, ResultHandler resultHandler, BoundSql boundSql) throws SQLException {
        Map<String, String> expressions = DataPermissionContext.getExpressions();
        if (expressions == null || expressions.isEmpty()) {
            return;
        }
        String sql = boundSql.getSql();
        String newSql = appendExpressions(sql, expressions);
        if (!newSql.equals(sql)) {
            PluginUtils.mpBoundSql(boundSql).sql(newSql);
        }
    }

    /**
     * 为命中规则的表追加条件
     */
    private String appendExpressions(String sql, Map<String, String> expressions) {
        String result = sql;
        for (Map.Entry<String, String> entry : expressions.entrySet()) {
            String table = entry.getKey();
            String expression = entry.getValue();
            if (table == null || expression == null || expression.isBlank()) {
                continue;
            }
            if (!containsTable(result, table)) {
                continue;
            }
            result = appendExpression(result, expression);
        }
        return result;
    }

    /**
     * 在 SQL 的 WHERE 条件后追加 " AND (表达式)"
     */
    private String appendExpression(String sql, String expression) {
        String upperSql = sql.toUpperCase(Locale.ROOT);
        int whereIndex = upperSql.indexOf(" WHERE ");
        if (whereIndex == -1) {
            // 无 WHERE 条件：在关键字前插入 WHERE，否则追加到末尾
            int insertIndex = findKeywordIndex(upperSql, whereIndex);
            if (insertIndex == -1) {
                return sql + " WHERE (" + expression + ")";
            }
            return sql.substring(0, insertIndex) + " WHERE (" + expression + ") " + sql.substring(insertIndex);
        }
        // 有 WHERE 条件：在后续关键字前追加 AND (表达式)
        int insertIndex = findKeywordIndex(upperSql, whereIndex + " WHERE ".length());
        if (insertIndex == -1) {
            return sql + " AND (" + expression + ")";
        }
        return sql.substring(0, insertIndex) + " AND (" + expression + ") " + sql.substring(insertIndex);
    }

    /**
     * 查找 GROUP BY / ORDER BY / LIMIT / FOR UPDATE 关键字的位置
     */
    private int findKeywordIndex(String upperSql, int fromIndex) {
        int min = -1;
        for (String keyword : KEYWORDS) {
            int index = upperSql.indexOf(keyword, fromIndex);
            if (index != -1 && (min == -1 || index < min)) {
                min = index;
            }
        }
        return min;
    }

    /**
     * 判断 SQL 中是否包含指定表名（简单文本匹配）
     */
    private boolean containsTable(String sql, String table) {
        return sql.toUpperCase(Locale.ROOT).contains(table.toUpperCase(Locale.ROOT));
    }

}
