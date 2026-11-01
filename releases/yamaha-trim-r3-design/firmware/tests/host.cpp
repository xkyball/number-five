#include "Arduino.h"
#include <cassert>
#include <iostream>
#include "../YamahaTrimAdapter/YamahaTrimAdapter.ino"
void cmd(const char*s){char a[100];strncpy(a,s,99);a[99]=0;handle(a);}
void inputs(float senderOhm=100,float boat=12){
 float excitation=hostSupplymV/1000.f-.65f, parallel=senderOhm*20000/(senderOhm+20000),vs=excitation*parallel/(1000+parallel);
 adc[A0]=lround(vs/2/(hostSupplymV/1000.f)*1023);adc[A3]=lround(excitation/3/(hostSupplymV/1000.f)*1023);adc[A6]=lround(boat/6/(hostSupplymV/1000.f)*1023);
}
void feedback(float realV=-1){adc[A1]=lround((realV<0?targetV:realV)/3/(hostSupplymV/1000.f)*1023);}
void ticks(int ms,bool follow=true){for(int i=0;i<ms;i+=10){clockMs+=10;if(follow)feedback();loop();}}
void fresh(int responders=1){
 Wire=WireMock{};Wire.responders=responders;EEPROM=EEPROMMock{};Serial=SerialMock{};events.clear();stuckHighPin=stuckLowPin=-1;
 dacAddress=fault=phase=0;armed=testMode=gotLow=gotHigh=false;clockMs=lastSample=lastPrint=lastSupply=lastHeartbeat=badSince=0;hostSupplymV=5000;commandLen=0;discardLine=false;inputs();setup();
}
void bench(float value=100){fresh();cmd(("test "+std::to_string(value)).c_str());cmd("arm");assert(phase==1&&!armed&&levels[RELAY]==LOW);ticks(260);assert(armed&&phase==3);}
int main(){
 fresh();assert(!armed&&!phase&&fault==1&&levels[RELAY]==LOW&&levels[HEARTBEAT]==LOW);cmd("arm");assert(fault==1&&!phase);
 for(float r:{5.f,100.f,500.f}){bench(r);float v=5.027f*r/(201.9219f+r);assert(fabs(targetV-v)<.0001);assert(abs(int(lastCode)-lround(v/5*4096))<=1);}
 std::cout<<"PASS chosen_target_preloaded_before_contact_no_full_scale_start\n";
 bench();auto e=std::find_if(events.begin(),events.end(),[](Event e){return e.p==RELAY&&e.v==HIGH;});assert(e!=events.end()&&e->t>=200);size_t pulses=0;for(auto e:events)if(e.p==HEARTBEAT&&e.v==HIGH)pulses++;assert(pulses>=10);
 std::cout<<"PASS nonblocking_precharge_and_50Hz_main_loop_heartbeat\n";
 cmd("disarm");assert(!phase&&levels[RELAY]==LOW&&levels[HEARTBEAT]==LOW&&modes[GATE]==INPUT);
 for(bool timeout:{false,true}){bench();Wire.failed=!timeout;Wire.forceTimeout=timeout;ticks(20);assert(!phase&&fault==2&&levels[RELAY]==LOW&&levels[HEARTBEAT]==LOW);}
 std::cout<<"PASS DAC_NACK_and_bus_timeout_stop_both_controls\n";
 for(int mv:{4200,5600}){bench();hostSupplymV=mv;inputs();ticks(120);assert(!phase&&fault==7);}
 std::cout<<"PASS supply_low_high_off\n";
 bench(500);uint16_t initial=lastCode;hostSupplymV=4600;inputs();ticks(2500);assert(armed&&lastCode>initial);assert(fabs(lastCode*4.6f/4096-targetV)<.0012);
 feedback(targetV*.92f);ticks(2200,false);assert(!phase&&fault==6);
 std::cout<<"PASS 4600mV_compensated_and_wrong_physical_voltage_detected\n";
 bench();inputs(1e9);ticks(20);assert(!phase&&fault==3);std::cout<<"PASS sender_open_off\n";
 bench();adc[A3]=0;ticks(20);assert(!phase&&fault==3);std::cout<<"PASS excitation_ground_or_reference_loss_off\n";
 bench();inputs(100,8);ticks(20);assert(!phase&&fault==7);std::cout<<"PASS relay_12V_supply_missing_off\n";
 bench();stuckHighPin=RELAY;levels[RELAY]=HIGH;trip(2);assert(levels[RELAY]==HIGH&&levels[HEARTBEAT]==LOW&&!phase);size_t n=events.size();ticks(400);for(size_t i=n;i<events.size();i++)assert(events[i].p!=HEARTBEAT||events[i].v==LOW);
 std::cout<<"PASS stuck_enable_stops_heartbeat_hardware_decay_tested_in_SPICE\n";
 fresh();inputs(0);cmd("cal low");inputs(500);cmd("cal high");cmd("range 50 500");cmd("point 150 200");cmd("save");assert(validConfig()&&curveValid());assert(fabs(mappedOhm(150)-200)<.001);assert(!cfg.autoRun);cmd("arm");ticks(260);assert(armed);cmd("range 100 500");assert(!phase&&cfg.crc==0);
 std::cout<<"PASS endpoints_extra_points_and_config_edit_off\n";
 cfg.version=2;cfg.crc=crc16((uint8_t*)&cfg,offsetof(Config,crc));EEPROM.put(0,cfg);setup();assert(cfg.version==3&&!validConfig()&&!phase);std::cout<<"PASS R2_EEPROM_rejected_requires_new_calibration\n";
 fresh();for(const char*s:{"test nan","test 501","test 5junk","test inf"}){cmd(s);assert(!testMode&&!phase);}
 fresh(0);assert(fault==2);fresh(2);assert(fault==2);std::cout<<"PASS malformed_commands_and_zero_or_multiple_DACs\n";
 bench();std::string overflow(110,'x');overflow+="arm\n";for(char c:overflow)Serial.in.push_back(c);for(int i=0;i<5;i++)loop();assert(!phase&&fault==5);
 bench();EEPROM.bytes[0]^=1;setup();assert(!phase&&fault==1);std::cout<<"PASS serial_overflow_and_bad_EEPROM_off\n";
 fresh();inputs(500);cmd("cal low");inputs(0);cmd("cal high");cmd("range 50 500");cmd("point 150 200");cmd("save");assert(validConfig()&&curveValid());assert(fabs(mappedOhm(cfg.rawLow)-50)<.001&&fabs(mappedOhm(cfg.rawHigh)-500)<.001);
 std::cout<<"PASS reversed_calibration\n";
 bench();cmd("log 1");assert(csvLogging);cmd("log 0");assert(!csvLogging);
 fresh();inputs(0);cmd("cal low");inputs(500);cmd("cal high");cmd("save");inputs(0);cmd("arm");ticks(260);assert(armed);std::cout<<"PASS zero_short_is_indistinguishable_from_valid_zero_endpoint\n";
 return 0;
}
