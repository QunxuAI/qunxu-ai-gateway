package tech.qunxu.framework.tenant.core.db;

import com.baomidou.mybatisplus.core.metadata.TableInfo;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.extension.plugins.handler.TenantLineHandler;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.LongValue;
import tech.qunxu.framework.tenant.config.TenantProperties;
import tech.qunxu.framework.tenant.core.context.TenantContextHolder;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * 租户行处理器。
 * <p>
 * 实现 MyBatis Plus 的 {@link TenantLineHandler}：
 * <ul>
 *     <li>{@link #getTenantId()}：返回当前线程的租户编号（来自租户上下文）；</li>
 *     <li>{@link #ignoreTable(String)}：判断表是否需要忽略租户过滤。规则如下：
 *     <ol>
 *         <li>配置文件中显式声明的忽略表；</li>
 *         <li>实体类继承 {@link TenantBaseDO} 的表，按租户隔离，不忽略；</li>
 *         <li>其余表（如租户表、套餐表等全局表）默认忽略。</li>
 *     </ol>
 *     </li>
 * </ul>
 */
public class TenantLineHandlerImpl implements TenantLineHandler {

    /** 显式忽略的表名（大小写不敏感） */
    private final Set<String> ignoreTables = new HashSet<>();

    public TenantLineHandlerImpl(TenantProperties properties) {
        if (properties.getIgnoreTables() != null) {
            properties.getIgnoreTables().forEach(table -> ignoreTables.add(table.toLowerCase(Locale.ROOT)));
        }
    }

    @Override
    public Expression getTenantId() {
        Long tenantId = TenantContextHolder.getTenantId();
        return tenantId == null ? null : new LongValue(tenantId);
    }

    @Override
    public boolean ignoreTable(String tableName) {
        if (tableName == null) {
            return true;
        }
        String normalized = tableName.toLowerCase(Locale.ROOT);
        // 情况一：显式配置的忽略表
        if (ignoreTables.contains(normalized)) {
            return true;
        }
        // 情况二：根据实体类继承关系自动判断
        TableInfo tableInfo = TableInfoHelper.getTableInfo(tableName);
        if (tableInfo == null || tableInfo.getEntityType() == null) {
            // 找不到对应实体，说明不是框架内的业务表，忽略租户过滤
            return true;
        }
        // 继承了 TenantBaseDO 的表按租户隔离
        return !TenantBaseDO.class.isAssignableFrom(tableInfo.getEntityType());
    }

}
