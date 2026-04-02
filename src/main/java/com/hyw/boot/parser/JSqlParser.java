package com.hyw.boot.parser;

import lombok.Data;
import net.sf.jsqlparser.JSQLParserException;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.schema.Column;
import net.sf.jsqlparser.schema.Table;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.delete.Delete;
import net.sf.jsqlparser.statement.insert.Insert;
import net.sf.jsqlparser.statement.merge.Merge;
import net.sf.jsqlparser.statement.select.PlainSelect;
import net.sf.jsqlparser.statement.select.Select;
import net.sf.jsqlparser.statement.select.SelectItem;
import net.sf.jsqlparser.statement.update.Update;
import net.sf.jsqlparser.statement.update.UpdateSet;
import net.sf.jsqlparser.util.TablesNamesFinder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Component
public class JSqlParser implements SqlParser {

    private static final Logger log = LoggerFactory.getLogger(JSqlParser.class);
    private static final Set<String> DML_TYPES = new HashSet<>(
            java.util.Arrays.asList("INSERT", "UPDATE", "DELETE", "MERGE")
    );

    @Override
    public SqlParseResult parse(String sql) {
        SqlParseResult result = new SqlParseResult();
        result.setOriginalSql(sql);

        try {
            Statement statement = CCJSqlParserUtil.parse(sql);
            result.setStatement(statement);
            result.setSqlType(determineSqlType(statement));
            result.setTables(extractTables(statement));

            if (statement instanceof Select) {
                parseSelect((Select) statement, result);
            } else if (statement instanceof Update) {
                parseUpdate((Update) statement, result);
            } else if (statement instanceof Insert) {
                parseInsert((Insert) statement, result);
            } else if (statement instanceof Delete) {
                parseDelete((Delete) statement, result);
            } else if (statement instanceof Merge) {
                parseMerge((Merge) statement, result);
            }

        } catch (JSQLParserException e) {
            log.debug("SQL解析失败（非标准SQL）: {}", e.getMessage());
            result.setSqlType("UNKNOWN");
        }

        return result;
    }

    @Override
    public String getSqlType(String sql) {
        try {
            Statement statement = CCJSqlParserUtil.parse(sql);
            return determineSqlType(statement);
        } catch (Exception e) {
            return "UNKNOWN";
        }
    }

    @Override
    public List<String> extractTableNames(String sql) {
        try {
            Statement statement = CCJSqlParserUtil.parse(sql);
            return extractTables(statement);
        } catch (Exception e) {
            log.debug("提取表名失败: {}", e.getMessage());
            return new ArrayList<>();
        }
    }

    @Override
    public boolean isSelect(String sql) {
        return "SELECT".equalsIgnoreCase(getSqlType(sql));
    }

    @Override
    public boolean isDml(String sql) {
        String type = getSqlType(sql);
        return DML_TYPES.contains(type);
    }

    private void parseSelect(Select select, SqlParseResult result) {
        try {
            PlainSelect plainSelect = (PlainSelect) select;

            if (plainSelect.getWhere() != null) {
                result.setWhereCondition(plainSelect.getWhere().toString());
            }
            if (plainSelect.getOrderByElements() != null && !plainSelect.getOrderByElements().isEmpty()) {
                result.setOrderBy(plainSelect.getOrderByElements().toString());
            }
            if (plainSelect.getLimit() != null) {
                result.setLimit(plainSelect.getLimit().toString());
            }
            result.setHasJoin(plainSelect.getJoins() != null && !plainSelect.getJoins().isEmpty());
            result.setHasSubQuery(hasSubQuery(plainSelect));
            parseSelectItems(plainSelect.getSelectItems(), result);

        } catch (Exception e) {
            log.debug("解析SELECT失败: {}", e.getMessage());
        }
    }

    private void parseSelectItems(List<SelectItem<?>> selectItems, SqlParseResult result) {
        if (selectItems == null || selectItems.isEmpty()) {
            return;
        }
        List<String> columns = new ArrayList<>();
        for (SelectItem<?> item : selectItems) {
            try {
                columns.add(item.toString());
            } catch (Exception e) {
                columns.add("UNKNOWN_COLUMN");
            }
        }
        result.setColumns(columns);
    }

    private boolean hasSubQuery(PlainSelect plainSelect) {
        if (plainSelect.getWhere() != null) {
            String whereStr = plainSelect.getWhere().toString().toUpperCase();
            return whereStr.contains("SELECT") && whereStr.contains("FROM");
        }
        return false;
    }

    // ====================== 🔥 100% 匹配你三份源码的最终版 ======================
    private void parseUpdate(Update update, SqlParseResult result) {
        List<String> tables = new ArrayList<>();
        Table table = update.getTable();
        if (table != null) {
            tables.add(table.getName());
        }
        result.setTables(tables);

        if (update.getWhere() != null) {
            result.setWhereCondition(update.getWhere().toString());
        }

        List<String> columns = new ArrayList<>();
        List<UpdateSet> updateSets = update.getUpdateSets();
        if (updateSets != null) {
            for (UpdateSet set : updateSets) {
                if (set.getColumns() != null) {
                    for (Column col : set.getColumns()) {
                        columns.add(col.getColumnName());
                    }
                }
            }
        }
        result.setColumns(columns);
    }

    private void parseInsert(Insert insert, SqlParseResult result) {
        if (insert.getTable() != null) {
            result.setTables(java.util.Collections.singletonList(insert.getTable().getName()));
        }

        if (insert.getColumns() != null && !insert.getColumns().isEmpty()) {
            List<String> columns = new ArrayList<>();
            for (Column column : insert.getColumns()) {
                columns.add(column.getColumnName());
            }
            result.setColumns(columns);
        }
    }

    private void parseDelete(Delete delete, SqlParseResult result) {
        if (delete.getTable() != null) {
            result.setTables(java.util.Collections.singletonList(delete.getTable().getName()));
        }

        if (delete.getWhere() != null) {
            result.setWhereCondition(delete.getWhere().toString());
        }
    }

    private void parseMerge(Merge merge, SqlParseResult result) {
        if (merge.getTable() != null) {
            result.setTables(java.util.Collections.singletonList(merge.getTable().getName()));
        }
        result.setSqlType("MERGE");
    }

    private String determineSqlType(Statement statement) {
        if (statement instanceof Select) return "SELECT";
        if (statement instanceof Insert) return "INSERT";
        if (statement instanceof Update) return "UPDATE";
        if (statement instanceof Delete) return "DELETE";
        if (statement instanceof Merge) return "MERGE";
        return "OTHER";
    }

    private List<String> extractTables(Statement statement) {
        TablesNamesFinder tablesNamesFinder = new TablesNamesFinder();
        return tablesNamesFinder.getTableList(statement);
    }

    @Data
    public static class SqlParseResult {
        private String originalSql;
        private Statement statement;
        private String sqlType;
        private List<String> tables = new ArrayList<>();
        private List<String> columns = new ArrayList<>();
        private String whereCondition;
        private String orderBy;
        private String limit;
        private boolean hasJoin;
        private boolean hasSubQuery;
    }
}