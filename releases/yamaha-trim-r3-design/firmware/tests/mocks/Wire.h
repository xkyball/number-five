#pragma once
#include <cstdint>
#include <vector>
struct WireMock {
 int address=0,responders=1;bool failed=false,forceTimeout=false;std::vector<uint8_t> bytes;std::vector<uint16_t> codes;
 void begin(){} void setClock(int){} void setWireTimeout(int,bool){}
 void clearWireTimeoutFlag(){} bool getWireTimeoutFlag(){return forceTimeout;}
 void beginTransmission(int a){address=a;bytes.clear();}
 void write(uint8_t b){bytes.push_back(b);}
 int endTransmission(){if(forceTimeout){clockMs+=25;return 0;}if(bytes.size()==2&&!failed)codes.push_back(((bytes[0]&15)<<8)|bytes[1]);return failed||address<0x60||address>=0x60+responders?2:0;}
};
inline WireMock Wire;
