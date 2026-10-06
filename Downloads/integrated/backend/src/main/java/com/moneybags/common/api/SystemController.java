package com.moneybags.common.api;
import com.moneybags.common.database.SchemaInspector;
import com.moneybags.common.database.SchemaTable;
import org.springframework.web.bind.annotation.*;
import org.springframework.security.access.prepost.PreAuthorize;
import java.util.*;
@RestController @RequestMapping("/api/v1/system")
public class SystemController {
    private final SchemaInspector inspector;
    public SystemController(SchemaInspector inspector) { this.inspector=inspector; }
    public record ModuleInfo(int number,String code,String name,String status,long mappedTables) {}
    @GetMapping("/modules") public ApiResponse<List<ModuleInfo>> modules() {
        String[] codes={"iam","cif","product","account","transaction","payment","treasury","loan","privacy","statement","currency"};
        String[] names={"Identity and Access Management","CIF and KYC","Product Master","Account Management","Transaction Processing","Payments Clearing","Treasury and Reserves","Loan Origination and Servicing","Privacy and Compliance","Statements and Reporting","Currency and External Integration"};
        // Counts describe original module tables; currency uses the two new extension tables.
        long[] counts={18,14,20,16,19,18,12,27,12,10,2};
        List<ModuleInfo> list=new ArrayList<>();for(int i=0;i<codes.length;i++)list.add(new ModuleInfo(i+1,codes[i],names[i],"INTEGRATED",counts[i]));
        return ApiResponse.of(list);
    }
    @GetMapping("/schema") @PreAuthorize("hasAuthority('SYSTEM_SCHEMA_READ')")
    public ApiResponse<SchemaInspector.SchemaCheck> schema() { return ApiResponse.of(inspector.inspect()); }
}
