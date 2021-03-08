package org.alex.platform.util;

import com.alibaba.druid.filter.Filter;
import com.alibaba.druid.pool.DruidDataSource;
import com.alibaba.druid.pool.DruidDataSourceFactory;
import com.alibaba.druid.wall.WallConfig;
import com.alibaba.druid.wall.WallFilter;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.alibaba.fastjson.serializer.SerializerFeature;
import org.alex.platform.exception.SqlException;
import org.apache.commons.lang.ArrayUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.SQLException;
import java.util.*;

@SuppressWarnings({"rawtypes", "uncheck"})
public class JdbcUtil {

    private static final Logger LOG = LoggerFactory.getLogger(JdbcUtil.class);

    private JdbcUtil() {

    }

    /**
     * 数据库预检
     * @param url 数据库连接地址
     * @param username 用户名
     * @param password 密码
     * @return 是否连接
     */
    public static String checkJdbcConnection(String url, String username, String password) {
        String msg = "连接成功";
        DruidDataSource ds;
        try {
            HashMap<String, Object> map = new HashMap<>();
            map.put("init","false");
            map.put("url", url);
            map.put("username", username);
            map.put("password", password);
            ds = (DruidDataSource) DruidDataSourceFactory.createDataSource(map);
            // 连通性检查
            JdbcTemplate jdbc = new JdbcTemplate(initDataSourceConfig(ds));
            jdbc.queryForList("select 1");
            ds.close();
        } catch (Exception e) {
            msg = "连接失败，" + e.getMessage();
            LOG.error("JDBC TEMPLATE 连接失败， errorMsg={}", ExceptionUtil.msg(e));
        }
        return msg;
    }

    /**
     * @param url 数据库url
     * @param username 连接用户名
     * @param password 连接密码
     * @return spring template
     */
    public static DruidDataSource getDruidDataSource(String url, String username, String password) throws Exception {
        HashMap<String, Object> map = new HashMap<>();
        map.put("init","false");
        map.put("url", url);
        map.put("username", username);
        map.put("password", password);
        DruidDataSource ds = (DruidDataSource) DruidDataSourceFactory.createDataSource(map);
        // 配置数据源
        initDataSourceConfig(ds);
        return ds;
    }

    /**
     * 查询首行首列，带参数
     * @param url 数据库url
     * @param username 连接用户名
     * @param password 连接密码
     * @param sql sql预计
     * @param params 参数，第一个参数为json path表达式，如果为空或者大小为0，那么返回首行首列结果
     * @return 查询结果
     * @throws SqlException 数据库异常
     */
    public static String selectFirst(String url, String username, String password, String sql, Object[] params) throws SqlException {
        String resultStr;
        try {
            resultStr = "";

            DruidDataSource druidDataSource = getDruidDataSource(url, username, password);
            JdbcTemplate jdbcTemplate = new JdbcTemplate(druidDataSource);

            if (null == params || params.length == 0) {
                List<Map<String, Object>> list = jdbcTemplate.queryForList(sql, params);
                if (list.isEmpty()) {
                    throw new SqlException("查询结果为空");
                }
                Map result = list.get(0);
                for (Object key : result.keySet()) {
                    resultStr = result.get(key).toString();
                    break;
                }
            } else {
                String jsonPath = (String) params[0];
                Object[] removeAfterParams = ArrayUtils.remove(params, 0);
                List<Map<String, Object>> list = jdbcTemplate.queryForList(sql, removeAfterParams);
                ArrayList sqlResultArray = JSONObject.parseObject(ParseUtil.parseJson(JSON.toJSONString(list, SerializerFeature.WriteMapNullValue), jsonPath), ArrayList.class);
                if (sqlResultArray.isEmpty()) {
                    LOG.warn("sql语句提取参数为空, sql={}, json path={}, params={}", sql, jsonPath, removeAfterParams);
                    throw new SqlException(String.format("sql语句提取参数为空, sql=%s, json path=%s, params=%s", sql, jsonPath, Arrays.toString(removeAfterParams)));
                }
                if (sqlResultArray.size() == 1) {
                    Object o = sqlResultArray.get(0);
                    if (o == null) {
                        LOG.warn("sql语句提取参数为空, sql={}, json path={}, params={}", sql, jsonPath, removeAfterParams);
                        throw new SqlException(String.format("sql语句提取参数为空, sql=%s, json path=%s, params=%s", sql, jsonPath, Arrays.toString(removeAfterParams)));
                    }
                    return o.toString();
                } else {
                    return JSON.toJSONString(sqlResultArray);
                }
            }
            druidDataSource.close();
        } catch (Exception e) {
            LOG.error("JDBC TEMPLATE 连接失败， errorMsg={}", ExceptionUtil.msg(e));
            throw new SqlException("SQL执行异常，" + e.getMessage());
        }
        return resultStr;
    }

    /**
     * 查询单列结果值
     * @param url jdbc url
     * @param username 用户名
     * @param password 密码
     * @param sql sql
     * @param elementType 元素类型
     * @param <T> 枚举
     * @return list
     * @throws Exception 异常
     */
    public static <T> List<T> queryForList(String url, String username, String password, String sql, Class<T> elementType) throws Exception {
        DruidDataSource druidDataSource = getDruidDataSource(url, username, password);
        JdbcTemplate jdbcTemplate = new JdbcTemplate(druidDataSource);
        return jdbcTemplate.queryForList(sql, elementType);
    }

    /**
     * 初始化jdbc过滤器
     * @return jdbc配置
     */
    private static List<Filter> initWallFilters () {
        WallFilter wallFilter = new WallFilter();
        WallConfig wallConfig = new WallConfig();
        // 配置仅允许查询
        wallConfig.setDeleteAllow(false);
        wallConfig.setUpdateAllow(false);
        wallConfig.setInsertAllow(false);
        wallConfig.setDropTableAllow(false);
        wallConfig.setAlterTableAllow(false);

        // 将配置加入过滤器
        wallFilter.setConfig(wallConfig);
        List<Filter> wallFilters = new ArrayList<>();
        wallFilters.add(wallFilter);

        return wallFilters;
    }

    /**
     * 初始化数据源配置
     * @param ds 数据源
     * @return 数据源
     * @throws SQLException SQLException
     */
    private static DruidDataSource initDataSourceConfig(DruidDataSource ds) throws SQLException {
        ds.setProxyFilters(initWallFilters());
        ds.setFailFast(true);
        ds.setConnectionErrorRetryAttempts(1);
        ds.setBreakAfterAcquireFailure(true);
        ds.init();
        return ds;
    }

}
