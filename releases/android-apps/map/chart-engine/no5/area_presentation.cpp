// GPL-2.0-or-later. Cached S01/R04 categorical edge treatment before S52 objects/text.
#include "presentation.hpp"
#include "r04_area_shader.hpp"
#include "r04_area_optimized.hpp"
namespace no5 {
static GLuint areaShader(GLenum kind,const char* code){
 GLuint s=glCreateShader(kind);glShaderSource(s,1,&code,nullptr);glCompileShader(s);GLint ok=0;glGetShaderiv(s,GL_COMPILE_STATUS,&ok);
 if(!ok){glDeleteShader(s);throw std::runtime_error("S01 area shader compilation failed");}return s;
}
void AreaPresentation::init(bool simplified,bool worldAlpha){if(program&&programSimplified==simplified&&programWorldAlpha==worldAlpha)return;release();GLuint v=0,f=0,p=0;try{
 v=areaShader(GL_VERTEX_SHADER,"attribute vec2 position;varying vec2 uv;void main(){uv=position*.5+.5;gl_Position=vec4(position,0.,1.);}");
 auto source=simplified?r04AreaSimplifiedFragment():std::string(r04AreaFragment);
 if(worldAlpha){
  const std::string from="gl_FragColor=vec4(clamp(outc,0.,1.),1.);";
  const auto at=source.find(from);if(at==std::string::npos||source.find(from,at+from.size())!=std::string::npos)throw std::runtime_error("World alpha shader contract changed");
  source.replace(at,from.size(),"float a=texture2D(areas,uv).a;gl_FragColor=vec4(a>0.?clamp(outc,0.,1.):vec3(0.),a);");
 }
 f=areaShader(GL_FRAGMENT_SHADER,source.c_str());p=glCreateProgram();glAttachShader(p,v);glAttachShader(p,f);glBindAttribLocation(p,0,"position");glLinkProgram(p);
 GLint ok=0;glGetProgramiv(p,GL_LINK_STATUS,&ok);if(!ok)throw std::runtime_error("S01 area program link failed");
 glDeleteShader(v);glDeleteShader(f);v=f=0;
 const char* names[]={"areas","pixel","style","role[0]"};for(size_t i=0;i<uniforms.size();i++){uniforms[i]=glGetUniformLocation(p,names[i]);if(uniforms[i]<0)throw std::runtime_error("S01 area uniform unavailable");}
 program=p;programSimplified=simplified;programWorldAlpha=worldAlpha;p=0;links++;
 }catch(...){if(v)glDeleteShader(v);if(f)glDeleteShader(f);if(p)glDeleteProgram(p);release();throw;}}
void AreaPresentation::release(){if(program)glDeleteProgram(program);program=0;}
void AreaPresentation::draw(int w,int h,const std::array<S52color,7>& roles,GLuint texture,bool simplified,bool worldAlpha){
 DetailScope scope(RenderDetail::AreaDraw);
 if(!texture)throw std::invalid_argument("S01 input attachment unavailable");
 init(simplified,worldAlpha);glActiveTexture(GL_TEXTURE0);glBindTexture(GL_TEXTURE_2D,texture);
 glUseProgram(program);glUniform1i(uniforms[0],0);glUniform2f(uniforms[1],1.f/w,1.f/h);glUniform1i(uniforms[2],1);
 GLfloat colors[21];size_t i=0;for(auto c:roles){colors[i++]=c.R/255.f;colors[i++]=c.G/255.f;colors[i++]=c.B/255.f;}
 glUniform3fv(uniforms[3],7,colors);
 glDisable(GL_BLEND);glDisable(GL_SCISSOR_TEST);glDisable(GL_DEPTH_TEST);glBindBuffer(GL_ARRAY_BUFFER,0);glBindBuffer(GL_ELEMENT_ARRAY_BUFFER,0);
 const GLfloat quad[]={-1,-1,1,-1,-1,1,1,1};glVertexAttribPointer(0,2,GL_FLOAT,GL_FALSE,0,quad);glEnableVertexAttribArray(0);glDisableVertexAttribArray(1);
 glDrawArrays(GL_TRIANGLE_STRIP,0,4);draws++;
 glDisableVertexAttribArray(0);glUseProgram(0);glBindTexture(GL_TEXTURE_2D,0);
 if(glGetError()!=GL_NO_ERROR)throw std::runtime_error("S01 area pass failed");
 // No study glFinish, CPU readback, evidence dump or per-frame compilation.
}
}
