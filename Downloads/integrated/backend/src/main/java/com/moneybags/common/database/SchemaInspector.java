package com.moneybags.common.database;
import org.springframework.stereotype.Service;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.*;
/** Read-only comparison with the installed schema; never runs migrations. */
@Service
public class SchemaInspector {
    private final JdbcTemplate jdbc;
    public SchemaInspector(JdbcTemplate jdbc) { this.jdbc=jdbc; }
    public record TableCheck(String table,String module,int mappedColumns,List<String> missingColumns,List<String> extraColumns,List<String> generatedColumnMismatches,boolean present) {}
    public record SchemaCheck(boolean compatible,int mappedTables,int missingTables,int mismatchedTables,List<TableCheck> tables) {}
    public SchemaCheck inspect() {
        var rows=jdbc.queryForList("SELECT TABLE_NAME,COLUMN_NAME,VIRTUAL_COLUMN,IDENTITY_COLUMN FROM ALL_TAB_COLS WHERE OWNER=SYS_CONTEXT('USERENV','SESSION_USER') AND HIDDEN_COLUMN='NO' AND TABLE_NAME LIKE 'M0%' ORDER BY TABLE_NAME,COLUMN_ID");
        Map<String,Map<String,Boolean>> installed=new HashMap<>();
        for(var row:rows)installed.computeIfAbsent(row.get("TABLE_NAME").toString(),k->new LinkedHashMap<>()).put(row.get("COLUMN_NAME").toString(),"YES".equals(row.get("VIRTUAL_COLUMN"))||"YES".equals(row.get("IDENTITY_COLUMN")));
        List<TableCheck> checks=new ArrayList<>();int missing=0,mismatch=0;
        for(var table:SchemaTable.values()) {
            var actual=installed.getOrDefault(table.name(),Map.of());var expected=SchemaRepository.columns(table);
            var absent=expected.keySet().stream().filter(c->!actual.containsKey(c)).toList();
            var extra=actual.keySet().stream().filter(c->!expected.containsKey(c)).toList();
            var generated=expected.entrySet().stream().filter(c->actual.containsKey(c.getKey())&&actual.get(c.getKey())!=c.getValue().generated()).map(Map.Entry::getKey).toList();
            boolean present=installed.containsKey(table.name());if(!present)missing++;else if(!absent.isEmpty()||!generated.isEmpty())mismatch++;
            checks.add(new TableCheck(table.name(),table.module(),expected.size(),absent,extra,generated,present));
        }
        return new SchemaCheck(missing==0&&mismatch==0,checks.size(),missing,mismatch,checks);
    }
}
