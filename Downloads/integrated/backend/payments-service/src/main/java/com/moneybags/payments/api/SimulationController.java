package com.moneybags.payments.api;
import com.moneybags.payments.core.SettlementSimulator;
import com.moneybags.integration.CurrentActor;
import jakarta.validation.Valid;
import java.util.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.transaction.annotation.Transactional;
@RestController @RequestMapping("/api/v1/payments/simulator")
public class SimulationController {
 private final SettlementSimulator simulator;
 public SimulationController(SettlementSimulator simulator){this.simulator=simulator;}
 @GetMapping @Transactional public Map<String,Object> overview(){
  simulator.requireRead();simulator.lock();simulator.ownReserve();
  return Map.of("banks",simulator.banks(),"cycles",simulator.cycles(),"payments",simulator.instructions(),"movements",simulator.movements());
 }
 @PostMapping("/incoming") public Contracts.Payment incoming(@Valid @RequestBody SettlementSimulator.Incoming request){return simulator.incoming(request);}
 @PostMapping("/cycles/{id}/settle") public Map<String,String> settle(@PathVariable long id){simulator.requireAdmin();simulator.closeCycle(id,CurrentActor.get().userId());simulator.settleCycle(id,CurrentActor.get().userId());return Map.of("status","CONFIRMED");}
 @PostMapping("/payments/{id}/reject") public Contracts.Payment reject(@PathVariable long id){return simulator.reject(id);}
}
