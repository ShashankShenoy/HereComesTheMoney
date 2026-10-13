package com.moneybags.payments.core;
import org.springframework.stereotype.Component;
import org.springframework.scheduling.annotation.Scheduled;
/** Runs only in the payments process; each cycle owns a separate rollback boundary. */
@Component
public class SimulationScheduler {
 private final SettlementSimulator simulator;
 public SimulationScheduler(SettlementSimulator simulator){this.simulator=simulator;}
 @Scheduled(fixedDelayString="${moneybags.payment-simulation.poll-ms:30000}",initialDelay=30000)
 public void run(){
  if(!simulator.enabled())return;
  for(long id:simulator.dueCycles())try{simulator.closeCycle(id,"SYSTEM");simulator.settleCycle(id,"SYSTEM");}
  catch(Exception error){simulator.recordFailure(id);}
 }
}
