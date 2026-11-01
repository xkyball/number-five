#pragma once
#include <array>
#include <cstring>
struct EEPROMMock {
 std::array<unsigned char,1024> bytes;
 EEPROMMock(){bytes.fill(255);}
 template<class T>void get(int i,T& v){memcpy(&v,bytes.data()+i,sizeof v);}
 template<class T>void put(int i,const T& v){memcpy(bytes.data()+i,&v,sizeof v);}
};
inline EEPROMMock EEPROM;
