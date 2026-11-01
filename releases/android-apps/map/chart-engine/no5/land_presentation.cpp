// GPL-2.0-or-later. Live ENC tessellation provider and land-only GLES treatment.
#include "presentation.hpp"
#include <chrono>
namespace no5 {
namespace {
using Clock=std::chrono::steady_clock;
bool rasterTriangle(std::vector<unsigned char>& mask,int w,int h,const std::array<double,6>& p,unsigned char value,const std::vector<uint8_t>* owners,uint8_t source,const std::shared_ptr<std::atomic<bool>>& cancelled,const BenchmarkRunSnapshot& run){
 double area=(p[2]-p[0])*(p[5]-p[1])-(p[3]-p[1])*(p[4]-p[0]);if(!std::isfinite(area)||std::abs(area)<1e-8)return true;
 int loY=std::max(0,int(std::ceil(std::min({p[1],p[3],p[5]})-.5))),hiY=std::min(h-1,int(std::floor(std::max({p[1],p[3],p[5]})-.5)));
 for(int y=loY;y<=hiY;y++){if(cancelled->load())return false;if(run.attached())run.checkpoint();double intersections[3];int n=0;for(int a=0,b=2;a<3;b=a++){double ya=p[2*a+1],yb=p[2*b+1],py=y+.5;if((ya<=py&&yb>py)||(yb<=py&&ya>py))intersections[n++]=p[2*a]+(py-ya)*(p[2*b]-p[2*a])/(yb-ya);}if(n<2)continue;int x0=std::max(0,int(std::ceil(std::min(intersections[0],intersections[1])-.5))),x1=std::min(w-1,int(std::floor(std::max(intersections[0],intersections[1])-.5)));if(x0<=x1){if(!owners)std::fill(mask.begin()+size_t(y)*w+x0,mask.begin()+size_t(y)*w+x1+1,value);else for(int x=x0;x<=x1;x++){const auto at=size_t(y)*w+x;if((*owners)[at]==source)mask[at]=value;}}}return true;
}
// Exact squared Euclidean distance transform of the sampled ENC mask.
// Lower envelopes of parabolas; all scratch is local and bounded to one frame.
std::vector<float> distance(const std::vector<unsigned char>& mask,int w,int h,const std::shared_ptr<std::atomic<bool>>& cancelled,const BenchmarkRunSnapshot& run){
 if(cancelled->load())return {};if(run.attached())run.checkpoint();
 std::vector<float> out(size_t(w)*h),temp(out.size());int len=std::max(w,h);std::vector<float> f(len),d(len),z(len+1);std::vector<int> v(len);
 auto transform=[&](int n){int k=0;v[0]=0;z[0]=-1e20f;z[1]=1e20f;for(int q=1;q<n;q++){float s;do{s=((f[q]+float(q)*q)-(f[v[k]]+float(v[k])*v[k]))/(2.f*(q-v[k]));if(s<=z[k])--k;else break;}while(k>=0);++k;v[k]=q;z[k]=s;z[k+1]=1e20f;}k=0;for(int q=0;q<n;q++){while(z[k+1]<q)++k;d[q]=float(q-v[k])*(q-v[k])+f[v[k]];}};
 for(int y=0;y<h;y++){if(cancelled->load())return {};if(run.attached())run.checkpoint();for(int x=0;x<w;x++)f[x]=!mask[size_t(y)*w+x]||x==0||x==w-1||y==0||y==h-1?0:1e12f;transform(w);std::copy_n(d.begin(),w,temp.begin()+size_t(y)*w);}
 for(int x=0;x<w;x++){if(cancelled->load())return {};if(run.attached())run.checkpoint();for(int y=0;y<h;y++)f[y]=temp[size_t(y)*w+x];transform(h);for(int y=0;y<h;y++)out[size_t(y)*w+x]=std::sqrt(d[y]);}return out;
}
GLuint compile(GLenum kind,const char* code){GLuint s=glCreateShader(kind);glShaderSource(s,1,&code,nullptr);glCompileShader(s);GLint ok=0;glGetShaderiv(s,GL_COMPILE_STATUS,&ok);if(!ok){glDeleteShader(s);throw std::runtime_error("Land presentation shader compile failed");}return s;}
}
std::shared_ptr<const LandResources> prepareLandResources(int w,int h,std::string key,std::vector<LandTriangle> triangles,const std::shared_ptr<std::atomic<bool>>& cancelled,const std::shared_ptr<LandWorkTelemetry>& telemetry,const std::shared_ptr<const std::vector<uint8_t>>& owners,const BenchmarkRunSnapshot& run)try{
 if(owners&&owners->size()!=size_t(w)*h)throw std::invalid_argument("Decorative land coverage dimensions");
 struct Finished {const std::shared_ptr<LandWorkTelemetry>& t;~Finished(){if(t){t->completed=true;t->outcome=t->produced?"complete":std::uncaught_exceptions()?"failed":"cancelled";}}} finished{telemetry};
 LandWorkScope total(telemetry,LandWorkPhase::Total);
 auto started=Clock::now();auto p=std::make_shared<LandResources>();p->width=w;p->height=h;p->key=std::move(key);
 std::vector<unsigned char> land,buildings,runway;
 {LandWorkScope scope(telemetry,LandWorkPhase::MaskAllocation);if(run.attached())run.checkpoint();land.resize(size_t(w)*h);buildings.resize(land.size());runway.resize(land.size());}
 {LandWorkScope scope(telemetry,LandWorkPhase::Raster);
 for(int group=0;group<4;group++)for(const auto& t:triangles){if(cancelled->load())return {};if(run.attached())run.checkpoint();if(t.kind!=group)continue;if(!rasterTriangle(group==0||group==1?land:group==2?buildings:runway,w,h,t.p,group==1?0:1,owners.get(),t.sourceOwner,cancelled,run))return {};}
 }
 std::vector<float> dist,bdist;
 {LandWorkScope scope(telemetry,LandWorkPhase::LandDistance);dist=distance(land,w,h,cancelled,run);}
 if(cancelled->load())return {};if(run.attached())run.checkpoint();
 {LandWorkScope scope(telemetry,LandWorkPhase::BuildingDistance);bdist=distance(buildings,w,h,cancelled,run);}
 if(cancelled->load())return {};
 std::vector<float> heights;
 {LandWorkScope scope(telemetry,LandWorkPhase::Heights);if(run.attached())run.checkpoint();heights.resize(land.size());for(int y=0;y<h;y++){if(cancelled->load())return {};if(run.attached())run.checkpoint();for(int x=0;x<w;x++){const auto i=size_t(y)*w+x;if(land[i]){heights[i]=12.f-2.8f*std::exp(-dist[i]/4.f);if(buildings[i])heights[i]+=10.f-2.2f*std::exp(-bdist[i]/1.4f);}}}
 }
 // The R07 height levels are decorative image units, never surveyed elevation.
 // A separable 0.8-pixel Gaussian smooths building/land bevels.
 if(cancelled->load())return {};if(run.attached())run.checkpoint();
 std::vector<float> blur(heights.size());const float kernel[]={.02193f,.22851f,.49912f,.22851f,.02193f};
 {LandWorkScope scope(telemetry,LandWorkPhase::BlurX);
 for(int y=0;y<h;y++){if(cancelled->load())return {};if(run.attached())run.checkpoint();for(int x=0;x<w;x++){float sum=0;for(int k=-2;k<=2;k++)sum+=heights[size_t(y)*w+std::clamp(x+k,0,w-1)]*kernel[k+2];blur[size_t(y)*w+x]=sum;}}
 }
 {LandWorkScope scope(telemetry,LandWorkPhase::BlurY);
 for(int y=0;y<h;y++){if(cancelled->load())return {};if(run.attached())run.checkpoint();for(int x=0;x<w;x++){float sum=0;for(int k=-2;k<=2;k++)sum+=blur[size_t(std::clamp(y+k,0,h-1))*w+x]*kernel[k+2];heights[size_t(y)*w+x]=sum;}}
 }
 {LandWorkScope scope(telemetry,LandWorkPhase::LightingPacking);
 if(cancelled->load())return {};if(run.attached())run.checkpoint();
 p->field.resize(land.size()*4);p->light.resize(land.size()*4);
 for(int y=0;y<h;y++){if(cancelled->load())return {};if(run.attached())run.checkpoint();for(int x=0;x<w;x++){size_t i=size_t(y)*w+x,at=(size_t(h-1-y)*w+x)*4;bool inner=x>0&&y>0&&x<w-1&&y<h-1&&land[i]&&land[i-1]&&land[i+1]&&land[i-w]&&land[i+w];p->field[at]=inner?255:0;p->field[at+1]=std::lround(std::min(dist[i],40.f)*255.f/40.f);p->field[at+3]=255;
  if(!inner)continue;p->landPixels++;if(buildings[i])p->buildingPixels++;
  float gx=(heights[i+1]-heights[i-1])*.5f,gy=(heights[i+w]-heights[i-w])*.5f,nz=1/std::sqrt(1+gx*gx+gy*gy);float lambert=std::max(0.f,(-gx*nz)*(-.49f)+(-gy*nz)*(-.54f)+nz*.69f);float ao=0,shadow=0;
  for(int a=0;a<8;a++){float angle=a*3.14159265f/4.f,horizon=0;for(int r:{2,4,8,16,28}){int xx=std::clamp(x+int(std::lround(std::cos(angle)*r*2)),0,w-1),yy=std::clamp(y+int(std::lround(std::sin(angle)*r*2)),0,h-1);horizon=std::max(horizon,std::max(heights[size_t(yy)*w+xx]-heights[i],0.f)/(r*2+2)*std::exp(-r/32.f));}ao+=std::atan(horizon)/(3.14159265f/2.f)/8.f;}
  for(int r=1;r<37;r+=2){int xx=std::clamp(x-int(std::lround(.67*r*2)),0,w-1),yy=std::clamp(y-int(std::lround(.74*r*2)),0,h-1);if(heights[size_t(yy)*w+xx]-heights[i]>r*2*.75f+.7f)shadow=std::max(shadow,std::exp(-r/65.f));}
  float shade=(.75f+.48f*lambert)*(1-std::min(ao*.95f,.42f))*(1-shadow*.40f);
  p->light[at]=std::lround(std::clamp(shade/1.5f,0.f,1.f)*255);p->light[at+1]=buildings[i]?255:0;p->light[at+2]=runway[i]?255:0;p->light[at+3]=255;
 }}}if(telemetry)telemetry->produced=true;
 p->preparationMs=std::chrono::duration<double,std::milli>(Clock::now()-started).count();return p;
}catch(const BenchmarkRunCancelled&){if(telemetry){telemetry->completed=true;telemetry->produced=false;telemetry->outcome="cancelled";}return {};
}
void LandPresentation::init(){if(program)return;GLuint v=0,f=0,candidate=0;try{
 v=compile(GL_VERTEX_SHADER,"attribute vec2 position;varying vec2 uv;void main(){uv=position*.5+.5;gl_Position=vec4(position,0.,1.);}");
 f=compile(GL_FRAGMENT_SHADER,R"GLSL(
precision highp float;
varying vec2 uv;uniform sampler2D base,field,relief;uniform vec2 texel;uniform int treatment,useRelief;uniform vec3 dry0,dry1;
bool same(vec3 a,vec3 b){return max(max(abs(a.r-b.r),abs(a.g-b.g)),abs(a.b-b.b))<1.6/255.;}
vec3 linear(vec3 c){return mix(c/12.92,pow((c+.055)/1.055,vec3(2.4)),step(vec3(.04045),c));}
vec3 srgb(vec3 c){return mix(c*12.92,1.055*pow(max(c,vec3(0.)),vec3(1./2.4))-.055,step(vec3(.0031308),c));}
void main(){vec4 b=texture2D(base,uv),m=texture2D(field,uv);if(m.r<.5||!(same(b.rgb,dry0)||same(b.rgb,dry1))){gl_FragColor=b;return;}vec3 c=b.rgb;
 if(treatment>0){float d=m.g*40.;float gx=(texture2D(field,uv+vec2(texel.x*2.,0.)).g-texture2D(field,uv-vec2(texel.x*2.,0.)).g)*10.;float gy=(texture2D(field,uv+vec2(0.,texel.y*2.)).g-texture2D(field,uv-vec2(0.,texel.y*2.)).g)*10.;float light=dot(normalize(vec2(gx,gy)+vec2(.00001)),normalize(vec2(-.65,.76)));float band=exp(-d/7.5),rim=exp(-d/2.4);if(treatment==1){c*=1.-band*max(-light,0.)*.48;c=mix(c,vec3(.99,.96,.83),band*max(light,0.)*.44+rim*.08);}else{c*=1.-band*max(-light,0.)*.63;c=mix(c,vec3(.16,.76,.73),band*max(light,0.)*.34+rim*.16);}}
 if(useRelief!=0){vec4 l=texture2D(relief,uv);vec3 material=b.rgb;bool dark=dot(dry0,vec3(.2126,.7152,.0722))<100./255.;if(l.g>.5)material=dark?vec3(129.,144.,145.)/255.:vec3(230.,204.,146.)/255.;if(l.b>.5)material=dark?vec3(71.,89.,94.)/255.:vec3(221.,202.,165.)/255.;vec3 shaded=srgb(linear(material)*(l.r*1.5));c=clamp(shaded*(c/max(b.rgb,vec3(1./255.))),0.,1.);}
 gl_FragColor=vec4(c,b.a);}
)GLSL");candidate=glCreateProgram();glAttachShader(candidate,v);glAttachShader(candidate,f);glBindAttribLocation(candidate,0,"position");glLinkProgram(candidate);GLint ok=0;glGetProgramiv(candidate,GL_LINK_STATUS,&ok);if(!ok)throw std::runtime_error("Land presentation program link failed");glDeleteShader(v);glDeleteShader(f);v=f=0;program=candidate;candidate=0;
 const char* names[]={"base","field","relief","texel","treatment","useRelief","dry0","dry1"};for(int i=0;i<8;i++)uniforms[i]=glGetUniformLocation(program,names[i]);glGenTextures(3,texture);links++;
 for(auto t:texture){glBindTexture(GL_TEXTURE_2D,t);glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_MIN_FILTER,GL_NEAREST);glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_MAG_FILTER,GL_NEAREST);glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_WRAP_S,GL_CLAMP_TO_EDGE);glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_WRAP_T,GL_CLAMP_TO_EDGE);}glBindTexture(GL_TEXTURE_2D,0);
 }catch(...){if(v)glDeleteShader(v);if(f)glDeleteShader(f);if(candidate)glDeleteProgram(candidate);release();throw;}}
void LandPresentation::release(){if(program)glDeleteProgram(program);glDeleteTextures(3,texture);program=0;texture[0]=texture[1]=texture[2]=0;width=height=0;uploadedKey.clear();uploadedLightKey.clear();}
void LandPresentation::upload(const LandResources& p,bool useRelief){
 init();bool fieldChanged=uploadedKey!=p.key;
 if(fieldChanged){glActiveTexture(GL_TEXTURE1);glBindTexture(GL_TEXTURE_2D,texture[1]);glTexImage2D(GL_TEXTURE_2D,0,GL_RGBA,p.width,p.height,0,GL_RGBA,GL_UNSIGNED_BYTE,p.field.data());uploads++;}
 if(useRelief&&uploadedLightKey!=p.key){glActiveTexture(GL_TEXTURE2);glBindTexture(GL_TEXTURE_2D,texture[2]);glTexImage2D(GL_TEXTURE_2D,0,GL_RGBA,p.width,p.height,0,GL_RGBA,GL_UNSIGNED_BYTE,p.light.data());uploads++;uploadedLightKey=p.key;}
 else if(!useRelief&&fieldChanged){const unsigned char empty[4]={};glActiveTexture(GL_TEXTURE2);glBindTexture(GL_TEXTURE_2D,texture[2]);glTexImage2D(GL_TEXTURE_2D,0,GL_RGBA,1,1,0,GL_RGBA,GL_UNSIGNED_BYTE,empty);uploads++;uploadedLightKey.clear();}
 glActiveTexture(GL_TEXTURE0);if(glGetError()!=GL_NO_ERROR)throw std::runtime_error("Land resource upload failed");uploadedKey=p.key;
}
void LandPresentation::draw(const LandResources& p,int effect,bool useRelief,S52color land,S52color built){
 upload(p,useRelief);int w=p.width,h=p.height;
 glActiveTexture(GL_TEXTURE0);glBindTexture(GL_TEXTURE_2D,texture[0]);if(w!=width||h!=height){glCopyTexImage2D(GL_TEXTURE_2D,0,GL_RGBA,0,0,w,h,0);width=w;height=h;allocations++;}else glCopyTexSubImage2D(GL_TEXTURE_2D,0,0,0,0,0,w,h);copies++;
 glUseProgram(program);for(int i=0;i<3;i++){glActiveTexture(GL_TEXTURE0+i);glBindTexture(GL_TEXTURE_2D,texture[i]);}glUniform1i(uniforms[0],0);glUniform1i(uniforms[1],1);glUniform1i(uniforms[2],2);glUniform2f(uniforms[3],1.f/w,1.f/h);glUniform1i(uniforms[4],effect);glUniform1i(uniforms[5],useRelief?1:0);glUniform3f(uniforms[6],land.R/255.f,land.G/255.f,land.B/255.f);glUniform3f(uniforms[7],built.R/255.f,built.G/255.f,built.B/255.f);
 glDisable(GL_BLEND);glDisable(GL_SCISSOR_TEST);glDisable(GL_DEPTH_TEST);glBindBuffer(GL_ARRAY_BUFFER,0);glBindBuffer(GL_ELEMENT_ARRAY_BUFFER,0);const GLfloat quad[]={-1,-1,1,-1,-1,1,1,1};glVertexAttribPointer(0,2,GL_FLOAT,GL_FALSE,0,quad);glEnableVertexAttribArray(0);glDisableVertexAttribArray(1);glDrawArrays(GL_TRIANGLE_STRIP,0,4);draws++;glDisableVertexAttribArray(0);glUseProgram(0);for(int i=2;i>=0;i--){glActiveTexture(GL_TEXTURE0+i);glBindTexture(GL_TEXTURE_2D,0);}if(glGetError()!=GL_NO_ERROR)throw std::runtime_error("Land presentation GL error");
}
}
