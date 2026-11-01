/* Trim R3 firmware3.0. Passive sender / Nano / MCP4725 / LM358 / stock FBR211.
   Relay COM -> Yamaha PINK; NO -> complete sink AND measurement circuitry.
   D4 enables one coil transistor; D5 pulses charge pump driving second transistor.
   No ISR generates the heartbeat: it follows completed main-loop health checks.
   GPIO stuckHIGH/LOW cannot keep the AC-coupled watchdog alive.
   USB service: unplug J2/J3 and remove JP1. Real bootloader/analog tests separate. */
#include <Wire.h>
#include <EEPROM.h>
#include <math.h>
#include <stddef.h>
#include "PinContract.h"
#ifdef __AVR__
#include <avr/wdt.h>
#include <avr/io.h>
uint8_t resetCause __attribute__((section(".noinit")));
void earlyWatchdogOff(void) __attribute__((naked,section(".init3")));
void earlyWatchdogOff(void){resetCause=MCUSR;MCUSR=0;wdt_disable();}
#endif
struct Config {
 uint32_t magic; uint16_t version,rawLow,rawHigh,v5mV,ecuOpenmV;
 float sourceOhm,outLowOhm,outHighOhm;
 uint8_t autoRun,pointCount; uint16_t pointRaw[6],pointOhm[6],bandgapmV,crc;
};
Config cfg;
uint8_t dacAddress=0,fault=0,phase=0; //0 OFF,1 reference/heartbeat charge,2 contact settle,3 ACTIVE
bool armed=false,testMode=false,gotLow=false,gotHigh=false,heartbeatLevel=false;
float filtered=0,testOhm=100,targetV=0,requestedV=0;
uint16_t raw=0,sensorADC=0,exciteADC=0,boatADC=0,monitor=0,lastCode=0,supplymV=5000;
uint32_t lastSample=0,lastPrint=0,badSince=0,lastSupply=0,lastHeartbeat=0,phaseStarted=0;
bool csvLogging=false;
#ifndef __AVR__
uint16_t hostSupplymV=5000;
#endif
char command[96];uint8_t commandLen=0;bool discardLine=false;
uint16_t crc16(const uint8_t*p,size_t n){uint16_t c=0xffff;while(n--){c^=*p++;for(uint8_t i=0;i<8;i++)c=c&1?(c>>1)^0xa001:c>>1;}return c;}
void inhibit(){
 digitalWrite(RELAY,LOW);pinMode(RELAY,INPUT);digitalWrite(HEARTBEAT,LOW);pinMode(HEARTBEAT,INPUT);
 pinMode(GATE,INPUT);digitalWrite(GATE,LOW);armed=false;phase=0;heartbeatLevel=false;
}
void trip(uint8_t code){inhibit();fault=code;testMode=false;}
uint16_t readSupply(){
#ifdef __AVR__
 uint8_t mux=ADMUX;ADMUX=_BV(REFS0)|_BV(MUX3)|_BV(MUX2)|_BV(MUX1);delayMicroseconds(2000);
 ADCSRA|=_BV(ADSC);while(bit_is_set(ADCSRA,ADSC)){} // discard first conversion after MUX/reference switch
 ADCSRA|=_BV(ADSC);while(bit_is_set(ADCSRA,ADSC)){}uint16_t sample=ADC;ADMUX=mux;
 ADCSRA|=_BV(ADSC);while(bit_is_set(ADCSRA,ADSC)){} // settle restored channel as well
 return sample?((uint32_t)cfg.bandgapmV*1023UL)/sample:0;
#else
 return hostSupplymV;
#endif
}
uint16_t readSettled(uint8_t pin){analogRead(pin);uint32_t sum=0;for(uint8_t i=0;i<4;i++)sum+=analogRead(pin);return(sum+2)/4;}
float pinkVoltage(){return monitor*3.0f*supplymV/1023000.0f;}
float boatVoltage(){return boatADC*6.0f*supplymV/1023000.0f;}
float excitationVoltage(){return exciteADC*3.0f*supplymV/1023000.0f;}
uint16_t sampleSensor(){
 sensorADC=readSettled(SENSOR);exciteADC=readSettled(EXCITATION);boatADC=readSettled(BOAT);
 if(!exciteADC)return 1023;
 return constrain((uint32_t)sensorADC*2UL*1023UL/(3UL*exciteADC),0UL,1023UL);
}
float sensorOhm(float a){
 float parallel=1000.0f*a/(1023.0f-a);
 return parallel<19999?parallel*20000.0f/(20000.0f-parallel):INFINITY;
}
bool validElectrical(){
 return cfg.ecuOpenmV>=1000&&cfg.ecuOpenmV<=5500&&isfinite(cfg.sourceOhm)&&cfg.sourceOhm>=20&&cfg.sourceOhm<=2000&&
 (cfg.ecuOpenmV/1000.0f)/cfg.sourceOhm<=.04f;
}
bool healthySupply(){
 // Guaranteed common-mode is evaluated against CURRENT voltage, never cached5V.
 return supplymV>=4600&&supplymV<=5250&&boatVoltage()>=10.5f&&boatVoltage()<=15.0f&&
 cfg.ecuOpenmV/3.0f+100<=supplymV-2000&&boatADC*6.0f*supplymV/1023.0f/6+50<=supplymV-2000;
}
bool healthySensor(){return exciteADC>=222&&exciteADC<=349&&raw<960;}
bool validConfig(){return cfg.magic==0x59545231UL&&cfg.version==3&&cfg.crc==crc16((uint8_t*)&cfg,offsetof(Config,crc))&&cfg.rawLow<960&&cfg.rawHigh<960&&abs((int)cfg.rawHigh-(int)cfg.rawLow)>=5&&validElectrical()&&cfg.bandgapmV>=900&&cfg.bandgapmV<=1300&&cfg.pointCount<=6&&isfinite(cfg.outLowOhm)&&isfinite(cfg.outHighOhm)&&cfg.outLowOhm>=5&&cfg.outLowOhm<=500&&cfg.outHighOhm>=5&&cfg.outHighOhm<=500;}
void defaults(){memset(&cfg,0,sizeof(cfg));cfg.magic=0x59545231UL;cfg.version=3;cfg.bandgapmV=1100;cfg.v5mV=5000;cfg.ecuOpenmV=5027;cfg.sourceOhm=201.9219f;cfg.outLowOhm=5;cfg.outHighOhm=500;}
bool curveValid(){
 uint16_t lo=cfg.rawLow<cfg.rawHigh?cfg.rawLow:cfg.rawHigh,hi=cfg.rawLow>cfg.rawHigh?cfg.rawLow:cfg.rawHigh;
 int direction=(cfg.outHighOhm-cfg.outLowOhm)*(int(cfg.rawHigh)-int(cfg.rawLow))>=0?1:-1;
 float previous=cfg.rawLow<cfg.rawHigh?cfg.outLowOhm:cfg.outHighOhm;uint16_t rawPrevious=lo;
 for(uint8_t i=0;i<cfg.pointCount;i++){if(cfg.pointRaw[i]<=rawPrevious||cfg.pointRaw[i]>=hi||cfg.pointOhm[i]<5||cfg.pointOhm[i]>500||direction*(cfg.pointOhm[i]-previous)<0)return false;previous=cfg.pointOhm[i];rawPrevious=cfg.pointRaw[i];}
 return direction*((cfg.rawLow<cfg.rawHigh?cfg.outHighOhm:cfg.outLowOhm)-previous)>=0;
}
float mappedOhm(float a){
 uint16_t lo=cfg.rawLow<cfg.rawHigh?cfg.rawLow:cfg.rawHigh,hi=cfg.rawLow>cfg.rawHigh?cfg.rawLow:cfg.rawHigh;
 float x=sensorOhm(constrain(a,float(lo),float(hi))),px=sensorOhm(lo),py=cfg.rawLow<cfg.rawHigh?cfg.outLowOhm:cfg.outHighOhm;
 for(uint8_t i=0;i<=cfg.pointCount;i++){float nx=sensorOhm(i<cfg.pointCount?cfg.pointRaw[i]:hi),ny=i<cfg.pointCount?cfg.pointOhm[i]:(cfg.rawLow<cfg.rawHigh?cfg.outHighOhm:cfg.outLowOhm);if(x<=nx||i==cfg.pointCount)return py+(ny-py)*(x-px)/(nx-px);px=nx;py=ny;}return py;
}
bool dacWrite(uint16_t code){
 Wire.clearWireTimeoutFlag();Wire.beginTransmission(dacAddress);Wire.write((uint8_t)((code>>8)&15));Wire.write((uint8_t)(code&255));
 bool ok=Wire.endTransmission()==0&&!Wire.getWireTimeoutFlag();if(ok)lastCode=code;return ok;
}
bool outputOhm(float ohm,bool slew=false){
 if(!healthySupply()){trip(7);return false;}
 if(!isfinite(ohm)||ohm<5||ohm>500){trip(4);return false;}
 requestedV=cfg.ecuOpenmV/1000.0f*ohm/(cfg.sourceOhm+ohm);
 float code=requestedV/(supplymV/1000.0f)*4096;
 if(!isfinite(code)||code<1||code>4094){trip(4);return false;}
 // Slew PHYSICAL voltage, not an old DAC code: VCC movement is compensated immediately.
 if(slew)targetV+=constrain(requestedV-targetV,-.04f,.04f);else targetV=requestedV;
 code=targetV*4096000.0f/supplymV;
 if(!dacWrite((uint16_t)(code+.5f))){trip(2);return false;}return true;
}
void armOutput(){
 inhibit();fault=0;if(!dacAddress){trip(2);return;}
 if(!validElectrical()){trip(4);return;}
 if(!testMode&&(!validConfig()||!curveValid())){trip(1);return;}
 supplymV=readSupply();raw=sampleSensor();filtered=raw;
 if(!healthySupply()){trip(7);return;}if(!healthySensor()){trip(3);return;}
 if(!outputOhm(testMode?testOhm:mappedOhm(filtered)))return;
 // Preset the actual chosen value BEFORE either coil switch can close.
 // No deliberate full-scale starting value is sent to Yamaha.
 digitalWrite(GATE,LOW);pinMode(GATE,OUTPUT);digitalWrite(RELAY,LOW);pinMode(RELAY,OUTPUT);
 digitalWrite(HEARTBEAT,LOW);pinMode(HEARTBEAT,OUTPUT);heartbeatLevel=false;lastHeartbeat=millis();phaseStarted=millis();phase=1;badSince=0;
}
void printStatus(){
 Serial.print(F("raw_normalized="));Serial.print(raw);Serial.print(F(" sensor_ohm="));Serial.print(sensorOhm(filtered),1);Serial.print(F(" requested_V="));Serial.print(requestedV,3);Serial.print(F(" command_V="));Serial.print(targetV,3);Serial.print(F(" pink_V="));Serial.print(pinkVoltage(),3);Serial.print(F(" supply_mV="));Serial.print(supplymV);Serial.print(F(" boat_V="));Serial.print(boatVoltage(),2);Serial.print(F(" phase="));Serial.print(phase);Serial.print(F(" armed="));Serial.print(armed);Serial.print(F(" fault="));Serial.println(fault);
}
bool number(char*text,float&value){if(!text||!*text)return false;char*end;value=strtod(text,&end);return*end==0&&isfinite(value);}
void printConfig(){
 Serial.print(F("FW=3.0 cfg_version="));Serial.print(cfg.version);Serial.print(F(" measured_v5_mV="));Serial.print(supplymV);Serial.print(F(" bandgap_mV="));Serial.print(cfg.bandgapmV);Serial.print(F(" ecu_open_mV="));Serial.print(cfg.ecuOpenmV);Serial.print(F(" source_ohm="));Serial.print(cfg.sourceOhm,3);Serial.print(F(" raw_low="));Serial.print(cfg.rawLow);Serial.print(F(" raw_high="));Serial.print(cfg.rawHigh);Serial.print(F(" autorun="));Serial.print(cfg.autoRun);Serial.print(F(" curve_points="));Serial.print(cfg.pointCount);Serial.print(F(" saved_valid="));Serial.println(validConfig()&&curveValid());
}
void handle(char *line){
 char *op=strtok(line," \r\n");if(!op)return;
 if(!strcmp(op,"help")){Serial.println(F("status | config | disarm | cal low | cal high | range <5..500> <5..500> | v5 <mV> | ecu <open_mV> <source_ohm> | save | run <0|1> | arm | test <5..500> | endtest"));return;}
 if(!strcmp(op,"log")){char *p=strtok(NULL," ");if(p&&(!strcmp(p,"0")||!strcmp(p,"1"))){csvLogging=!strcmp(p,"1");if(csvLogging)Serial.println(F("ms,sensor_adc,excite_adc,raw_normalized,sensor_ohm,requested_V,command_V,pink_V,phase,fault,supply_mV,boat_V"));}return;}
 if(!strcmp(op,"status")){printStatus();return;}
 if(!strcmp(op,"config")){printConfig();return;}
 if(!strcmp(op,"disarm")){inhibit();testMode=false;Serial.println(F("INHIBITED"));return;}
 if(!strcmp(op,"arm")){armOutput();return;}
 if(!strcmp(op,"endtest")){inhibit();testMode=false;return;}
 if(!strcmp(op,"test")){inhibit();testMode=false;float v;if(!number(strtok(NULL," "),v)||v<5||v>500){Serial.println(F("ERR test 5..500"));return;}testOhm=v;testMode=true;Serial.println(F("BENCH ONLY: Yamaha disconnected; measured ECU model required. Check config, then arm."));return;}
 // Every calibration edit disables the output and invalidates the in-RAM CRC.
 inhibit();testMode=false;
 if(!strcmp(op,"points")){char *p=strtok(NULL," ");if(p&&!strcmp(p,"clear")){cfg.pointCount=0;cfg.crc=0;}return;}
 if(!strcmp(op,"point")){
  float r,o;char *a=strtok(NULL," "),*b=strtok(NULL," ");
  if(!number(a,r)||!number(b,o)||r<0||r>=960||floor(r)!=r||o<5||o>500||floor(o)!=o||cfg.pointCount>=6){Serial.println(F("ERR point <normalized raw0..959> <ohm 5..500>"));return;}
  uint8_t at=0;while(at<cfg.pointCount&&cfg.pointRaw[at]<r)at++;
  if(at<cfg.pointCount&&cfg.pointRaw[at]==r){Serial.println(F("ERR duplicate raw"));return;}
  for(uint8_t i=cfg.pointCount;i>at;i--){cfg.pointRaw[i]=cfg.pointRaw[i-1];cfg.pointOhm[i]=cfg.pointOhm[i-1];}
  cfg.pointRaw[at]=r;cfg.pointOhm[at]=o;cfg.pointCount++;cfg.crc=0;return;
 }
 if(!strcmp(op,"bandgap")){float v;if(number(strtok(NULL," "),v)&&v>=900&&v<=1300&&floor(v)==v){cfg.bandgapmV=v;cfg.crc=0;}return;}
 if(!strcmp(op,"cal")){char *p=strtok(NULL," ");raw=sampleSensor();if(p&&!strcmp(p,"low")){cfg.rawLow=raw;gotLow=true;}else if(p&&!strcmp(p,"high")){cfg.rawHigh=raw;gotHigh=true;}else return;cfg.crc=0;Serial.println(raw);return;}
 if(!strcmp(op,"range")){char *a=strtok(NULL," "),*b=strtok(NULL," ");float x,y;if(number(a,x)&&number(b,y)&&x>=5&&x<=500&&y>=5&&y<=500){cfg.outLowOhm=x;cfg.outHighOhm=y;cfg.crc=0;}else Serial.println(F("ERR range 5..500 5..500"));return;}
 if(!strcmp(op,"v5")){float v;if(number(strtok(NULL," "),v)&&v>=4750&&v<=5250&&floor(v)==v){uint16_t measured=readSupply();if(measured){cfg.bandgapmV=(uint32_t)cfg.bandgapmV*(uint16_t)v/measured;cfg.v5mV=(uint16_t)v;cfg.crc=0;}}else Serial.println(F("ERR v5: integer mV 4750..5250"));return;}
 if(!strcmp(op,"ecu")){char *a=strtok(NULL," "),*b=strtok(NULL," ");float v,r;if(number(a,v)&&number(b,r)&&v>=1000&&v<=5500&&floor(v)==v&&r>=20&&r<=2000){cfg.ecuOpenmV=(uint16_t)v;cfg.sourceOhm=r;cfg.crc=0;}else Serial.println(F("ERR ecu: integer mV 1000..5500 and ohm 20..2000"));return;}
 if(!strcmp(op,"run")){char *p=strtok(NULL," ");if(p&&(!strcmp(p,"0")||!strcmp(p,"1"))){cfg.autoRun=!strcmp(p,"1");cfg.crc=0;}else Serial.println(F("ERR run 0|1"));return;}
 if(!strcmp(op,"save")){
  cfg.crc=crc16((uint8_t*)&cfg,offsetof(Config,crc));
  if(!gotLow||!gotHigh||!validConfig()||!curveValid()){cfg.crc=0;trip(1);Serial.println(F("ERR calibration/config"));return;}
  EEPROM.put(0,cfg);fault=0;Serial.println(F("SAVED; output OFF; arm required unless explicitly configured run1"));return;
 }
 Serial.println(F("ERR command; help"));
}
void setup(){
 inhibit();pinMode(LED,OUTPUT);Serial.begin(115200);Wire.begin();Wire.setClock(100000);Wire.setWireTimeout(25000,true);
 uint8_t found=0;for(uint8_t a=0x60;a<=0x67;a++){Wire.beginTransmission(a);if(Wire.endTransmission()==0){dacAddress=a;found++;}}
 if(found!=1){dacAddress=0;trip(2);}EEPROM.get(0,cfg);bool ok=validConfig()&&curveValid();
 if(!ok){defaults();fault=fault?fault:1;}else gotLow=gotHigh=true;
 supplymV=readSupply();raw=sampleSensor();filtered=raw;monitor=readSettled(MONITOR);
 Serial.println(F("YamahaTrimAdapter3.0 / new input circuit: R2 calibration invalid; help; log1; bandgap mV"));
 if(dacAddress&&!dacWrite(4095))trip(2);
#ifdef __AVR__
 if(resetCause&(_BV(WDRF)|_BV(BORF))){trip(8);}else if(ok&&cfg.autoRun&&dacAddress)armOutput();
 wdt_enable(WDTO_1S);
#else
 if(ok&&cfg.autoRun&&dacAddress)armOutput();
#endif
}
void loop(){
 // Bound serial processing; an incoming flood cannot starve health checks forever.
 for(uint8_t n=0;n<32&&Serial.available();n++){
  char c=Serial.read();if(c=='\n'||c=='\r'){if(discardLine){discardLine=false;commandLen=0;continue;}if(commandLen){command[commandLen]=0;handle(command);commandLen=0;}}
  else if(discardLine)continue;else if(commandLen<sizeof(command)-1)command[commandLen++]=c;else{commandLen=0;discardLine=true;trip(5);}
 }
 uint32_t t=millis();bool checked=false;
 if(t-lastSupply>=100){lastSupply=t;supplymV=readSupply();}
 if(t-lastSample>=20){
  lastSample=t;raw=sampleSensor();filtered+=.12f*(raw-filtered);monitor=readSettled(MONITOR);checked=true;
  if(phase){
   if(!healthySupply())trip(7);
   else if(!healthySensor())trip(3);
   else if(outputOhm(testMode?testOhm:mappedOhm(filtered),phase==3)){
    if(phase==3){float tolerance=fmaxf(.025f,.03f*targetV);if(fabs(pinkVoltage()-targetV)>tolerance){if(!badSince)badSince=t;if(t-badSince>=2000)trip(6);}else badSince=0;}
   }
  }
 }
 if(phase&&checked){
  if(phase==1&&t-phaseStarted>=200){digitalWrite(RELAY,HIGH);phase=2;phaseStarted=t;}
  if(phase==2&&t-phaseStarted>=40){phase=3;armed=true;badSince=0;}
 }
 // Pulses follow main-loop health work; never catch up missed edges in a burst.
 // Require a sample no older than30ms, and a current validated supply reading.
 if(phase&&!fault&&t-lastSample<=30&&t-lastSupply<=120&&t-lastHeartbeat>=10){heartbeatLevel=!heartbeatLevel;digitalWrite(HEARTBEAT,heartbeatLevel);lastHeartbeat=t;}
 if(!phase){digitalWrite(HEARTBEAT,LOW);heartbeatLevel=false;}
 uint16_t ledPhase=t%3000;digitalWrite(LED,fault?(ledPhase<fault*300&&ledPhase%300<100):armed);
 if(t-lastPrint>=(csvLogging?100:1000)){lastPrint=t;if(Serial){
  if(csvLogging){Serial.print(t);Serial.print(',');Serial.print(sensorADC);Serial.print(',');Serial.print(exciteADC);Serial.print(',');Serial.print(raw);Serial.print(',');Serial.print(sensorOhm(filtered),2);Serial.print(',');Serial.print(requestedV,4);Serial.print(',');Serial.print(targetV,4);Serial.print(',');Serial.print(pinkVoltage(),4);Serial.print(',');Serial.print(phase);Serial.print(',');Serial.print(fault);Serial.print(',');Serial.print(supplymV);Serial.print(',');Serial.println(boatVoltage(),3);}else printStatus();
 }}
#ifdef __AVR__
 wdt_reset();
#endif
}
