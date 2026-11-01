#pragma once
#include <cstdint>
#include <cstring>
#include <cstdlib>
#include <cmath>
#include <algorithm>
#include <deque>
#include <vector>
using std::isfinite;
constexpr int INPUT=0,OUTPUT=1,LOW=0,HIGH=1,A0=14,A1=15,A2=16,A3=17,A6=20;
inline uint32_t clockMs=0;
inline int modes[32]={},levels[32]={},adc[32]={};
inline void pinMode(int p,int m){modes[p]=m;}
struct Event{uint32_t t;int p,v;}; inline std::vector<Event> events;inline int stuckHighPin=-1,stuckLowPin=-1;
inline void digitalWrite(int p,int v){levels[p]=p==stuckHighPin?HIGH:p==stuckLowPin?LOW:v;events.push_back({clockMs,p,levels[p]});}
inline int analogRead(int p){return adc[p];}
inline uint32_t millis(){return clockMs;}
inline void delay(uint32_t t){clockMs+=t;}
template<class T> T constrain(T x,T lo,T hi){return std::clamp(x,lo,hi);}
#define F(x) x
struct SerialMock {
 std::deque<char> in;
 void begin(int){}
 int available(){return in.size();}
 char read(){char c=in.front();in.pop_front();return c;}
 template<class... T>void print(T...){}
 template<class... T>void println(T...){}
 operator bool()const{return true;}
};
inline SerialMock Serial;
